#include "engine/AudioEngine.h"
#include "common/Log.h"

#include <algorithm>
#include <chrono>
#include <cstring>

namespace tfx {

static constexpr int kBlockFrames = 256;
static constexpr int kTargetBufferedFrames = 4096;  // ~85 ms @ 48 kHz: edit-to-ear latency

AudioEngine& AudioEngine::instance() {
    static AudioEngine* engine = new AudioEngine();  // intentionally leaked: lives for process
    return *engine;
}

AudioEngine::AudioEngine() = default;
AudioEngine::~AudioEngine() { release(); }

bool AudioEngine::ensureStream() {
    if (mStream) return true;
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    if (mRate > 0) {
        // Keep the mixer rate stable across device changes; Oboe resamples if needed.
        b.setSampleRate(mRate)->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
    }
    oboe::Result r = b.openStream(mStream);
    if (r != oboe::Result::OK) {
        LOGE("openStream failed: %s", oboe::convertToText(r));
        mStream.reset();
        if (mRate == 0) mRate = 48000;
        return false;
    }
    if (mRate == 0) mRate = mStream->getSampleRate();
    // Double-buffering of the burst size keeps latency low but robust.
    mStream->setBufferSizeInFrames(mStream->getFramesPerBurst() * 2);
    LOGI("Output stream open: %d Hz, burst %d", mStream->getSampleRate(), mStream->getFramesPerBurst());
    return true;
}

void AudioEngine::closeStream() {
    if (mStream) {
        mStream->stop();
        mStream->close();
        mStream.reset();
    }
}

int AudioEngine::sampleRate() {
    std::lock_guard<std::mutex> lk(mControlMutex);
    if (mRate == 0) ensureStream();
    return mRate > 0 ? mRate : 48000;
}

void AudioEngine::startFeeder() {
    if (mFeederRun.load()) return;
    mFeederRun = true;
    mFeeder = std::thread(&AudioEngine::feederLoop, this);
}

void AudioEngine::parkFeeder() {
    if (!mFeederRun.load()) return;
    std::unique_lock<std::mutex> lk(mParkMutex);
    mParkRequest = true;
    mParkCv.wait(lk, [this] { return mParked || !mFeederRun.load(); });
}

void AudioEngine::unparkFeeder() {
    {
        std::lock_guard<std::mutex> lk(mParkMutex);
        mParkRequest = false;
    }
    mParkCv.notify_all();
}

// Must be called with the feeder parked.
void AudioEngine::flushBuffers() {
    const bool streamRunning = mStream && mStream->getState() == oboe::StreamState::Started;
    const uint32_t req = mFlushReq.load() + 1;
    if (streamRunning) {
        mFlushReq.store(req);
        auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(300);
        while (mFlushAck.load() != req && std::chrono::steady_clock::now() < deadline) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        if (mFlushAck.load() == req) return;
        LOGW("flush ack timeout; stopping stream to reset buffers");
        mStream->stop();
    }
    // No consumer is running: we can reset directly.
    mPcm.discardAll();
    mMarkers.discardAll();
    mHaveMarker = false;
    mCurConsumed = 0;
    mFlushReq.store(req);
    mFlushAck.store(req);
}

bool AudioEngine::setProject(const std::string& json, std::string& error) {
    std::lock_guard<std::mutex> lk(mControlMutex);
    ProjectDesc desc;
    if (!parseProject(json, desc, error)) return false;
    if (mRate == 0) ensureStream();
    if (mRate == 0) mRate = 48000;
    parkFeeder();
    const int64_t keep = mPlayhead.load();
    if (!mMixer) mMixer = std::make_unique<MultitrackMixer>(mRate, false);
    mMixer->setProject(desc);
    const int64_t len = mMixer->lengthFrames();
    const int64_t pos = std::clamp<int64_t>(keep, 0, std::max<int64_t>(0, len));
    flushBuffers();
    mMixer->seek(pos);
    mFeedPos = pos;
    mPlayhead = pos;
    mFeederDone = false;
    mEnded = false;
    {
        std::lock_guard<std::mutex> plk(mPendingMutex);
        mPending = PendingUpdates{};
        mHasPending = false;
    }
    startFeeder();
    unparkFeeder();
    return true;
}

void AudioEngine::clearProject() {
    std::lock_guard<std::mutex> lk(mControlMutex);
    mPlaying = false;
    if (mStream) mStream->stop();
    parkFeeder();
    flushBuffers();
    mMixer.reset();
    mPlayhead = 0;
    mFeedPos = 0;
    mRangeStart = 0;
    mRangeEnd = 0;
    mLoop = false;
    mSkipStart = -1;
    mSkipEnd = -1;
    unparkFeeder();
}

void AudioEngine::play() {
    std::lock_guard<std::mutex> lk(mControlMutex);
    if (!mMixer) return;
    if (mEnded.load() || mFeederDone.load()) {
        // Restart from the beginning of the active range.
        parkFeeder();
        flushBuffers();
        const int64_t start = mRangeStart.load();
        mMixer->seek(start);
        mFeedPos = start;
        mPlayhead = start;
        mFeederDone = false;
        mEnded = false;
        unparkFeeder();
    }
    if (!ensureStream()) return;
    mPlaying = true;
    if (mStream->getState() != oboe::StreamState::Started) {
        oboe::Result r = mStream->requestStart();
        if (r != oboe::Result::OK) {
            LOGE("requestStart failed: %s", oboe::convertToText(r));
            mPlaying = false;
        }
    }
}

void AudioEngine::pause() {
    std::lock_guard<std::mutex> lk(mControlMutex);
    mPlaying = false;
    // Synchronous stop: after it returns no callback is running, so buffered audio is kept and
    // resume continues seamlessly.
    if (mStream) mStream->stop();
}

void AudioEngine::seekUs(int64_t us) {
    std::lock_guard<std::mutex> lk(mControlMutex);
    if (!mMixer) return;
    parkFeeder();
    flushBuffers();
    const int64_t f = std::clamp<int64_t>(usToFrames(us), 0, mMixer->lengthFrames());
    mMixer->seek(f);
    mFeedPos = f;
    mPlayhead = f;
    mFeederDone = false;
    mEnded = false;
    unparkFeeder();
}

int64_t AudioEngine::positionUs() const {
    if (mRate <= 0) return 0;
    return mPlayhead.load() * 1000000 / mRate;
}

int64_t AudioEngine::durationUs() const {
    if (mRate <= 0) return 0;
    // mMixer pointer is only replaced under mControlMutex; length is read-only data.
    auto* m = mMixer.get();
    return m ? m->lengthFrames() * 1000000 / mRate : 0;
}

void AudioEngine::setPlayRange(int64_t startUs, int64_t endUs, bool loop) {
    mRangeStart = std::max<int64_t>(0, usToFrames(startUs));
    mRangeEnd = endUs > 0 ? usToFrames(endUs) : 0;
    mLoop = loop;
}

void AudioEngine::setSkipRange(int64_t startUs, int64_t endUs) {
    if (startUs < 0 || endUs <= startUs) {
        mSkipStart = -1;
        mSkipEnd = -1;
    } else {
        mSkipEnd = usToFrames(endUs);
        mSkipStart = usToFrames(startUs);
    }
}

bool AudioEngine::updateClip(const std::string& json) {
    ClipDesc clip;
    std::string err;
    if (!parseClip(json, clip, err)) return false;
    std::lock_guard<std::mutex> lk(mPendingMutex);
    mPending.clips.push_back(std::move(clip));
    mHasPending = true;
    return true;
}

bool AudioEngine::setMasterDsp(const std::string& json) {
    DspParams p;
    std::string err;
    if (!parseDspParams(json, p, err)) return false;
    std::lock_guard<std::mutex> lk(mPendingMutex);
    mPending.master = p;
    mHasPending = true;
    return true;
}

void AudioEngine::setTrackState(int64_t trackId, float gainDb, bool muted, bool solo) {
    std::lock_guard<std::mutex> lk(mPendingMutex);
    mPending.tracks.push_back({trackId, gainDb, muted, solo});
    mHasPending = true;
}

void AudioEngine::applyPendingUpdates() {
    if (!mHasPending.load() || !mMixer) return;
    PendingUpdates p;
    {
        std::lock_guard<std::mutex> lk(mPendingMutex);
        p = std::move(mPending);
        mPending = PendingUpdates{};
        mHasPending = false;
    }
    for (auto& c : p.clips) mMixer->updateClip(c);
    if (p.master) mMixer->setMasterDsp(*p.master);
    for (auto& t : p.tracks) mMixer->setTrackState(t.id, t.gainDb, t.muted, t.solo);
}

void AudioEngine::feederLoop() {
    std::vector<float> block(kBlockFrames * 2);
    while (mFeederRun.load()) {
        if (mParkRequest.load()) {
            std::unique_lock<std::mutex> lk(mParkMutex);
            mParked = true;
            mParkCv.notify_all();
            mParkCv.wait(lk, [this] { return !mParkRequest.load() || !mFeederRun.load(); });
            mParked = false;
            continue;
        }
        if (!mMixer || mFeederDone.load()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            continue;
        }
        const size_t buffered = mPcm.availableToRead() / 2;
        if (buffered >= static_cast<size_t>(kTargetBufferedFrames) ||
            mPcm.availableToWrite() < static_cast<size_t>(kBlockFrames * 2) ||
            mMarkers.availableToWrite() == 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
            continue;
        }
        applyPendingUpdates();

        int64_t pos = mFeedPos.load();
        const int64_t length = mMixer->lengthFrames();
        const int64_t rangeEnd = mRangeEnd.load() > 0 ? std::min(mRangeEnd.load(), length) : length;
        const int64_t skipS = mSkipStart.load(), skipE = mSkipEnd.load();

        if (skipS >= 0 && pos >= skipS && pos < skipE) {
            pos = std::min(skipE, rangeEnd);
            mMixer->seek(pos);
        }
        if (pos >= rangeEnd) {
            if (mLoop.load() && rangeEnd > mRangeStart.load()) {
                pos = mRangeStart.load();
                mMixer->seek(pos);
            } else {
                mFeederDone = true;
                continue;
            }
        }
        if (mMixer->position() != pos) mMixer->seek(pos);

        int64_t n = std::min<int64_t>(kBlockFrames, rangeEnd - pos);
        if (skipS >= 0 && pos < skipS) n = std::min<int64_t>(n, skipS - pos);
        n = std::max<int64_t>(1, n);
        mMixer->render(block.data(), static_cast<int>(n));
        mPcm.write(block.data(), static_cast<size_t>(n) * 2);
        mMarkers.push(BlockMarker{pos, static_cast<int32_t>(n)});
        mFeedPos = pos + n;
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream*, void* audioData,
                                                   int32_t numFrames) {
    auto* out = static_cast<float*>(audioData);
    const uint32_t req = mFlushReq.load(std::memory_order_acquire);
    if (req != mFlushAck.load(std::memory_order_relaxed)) {
        mPcm.discardAll();
        mMarkers.discardAll();
        mHaveMarker = false;
        mCurConsumed = 0;
        mFlushAck.store(req, std::memory_order_release);
    }
    if (!mPlaying.load(std::memory_order_acquire)) {
        std::memset(out, 0, sizeof(float) * 2 * numFrames);
        return oboe::DataCallbackResult::Continue;
    }
    int32_t done = 0;
    while (done < numFrames) {
        if (!mHaveMarker || mCurConsumed >= mCurMarker.frames) {
            if (!mMarkers.pop(mCurMarker)) break;  // underrun or end
            mHaveMarker = true;
            mCurConsumed = 0;
        }
        const int32_t want = std::min(numFrames - done, mCurMarker.frames - mCurConsumed);
        const size_t got = mPcm.read(out + done * 2, static_cast<size_t>(want) * 2) / 2;
        mCurConsumed += static_cast<int32_t>(got);
        done += static_cast<int32_t>(got);
        mPlayhead.store(mCurMarker.position + mCurConsumed, std::memory_order_release);
        if (static_cast<int32_t>(got) < want) break;
    }
    if (done < numFrames) {
        std::memset(out + done * 2, 0, sizeof(float) * 2 * (numFrames - done));
        if (mFeederDone.load(std::memory_order_acquire) && mMarkers.availableToRead() == 0 &&
            (!mHaveMarker || mCurConsumed >= mCurMarker.frames)) {
            mEnded.store(true, std::memory_order_release);
            mPlaying.store(false, std::memory_order_release);
        }
    }
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream*, oboe::Result error) {
    // Device disconnected (e.g. headphones unplugged). Re-open on the same rate; resume if playing.
    LOGW("Stream closed: %s; reopening", oboe::convertToText(error));
    std::thread([this] {
        std::lock_guard<std::mutex> lk(mControlMutex);
        mStream.reset();
        if (ensureStream() && mPlaying.load()) mStream->requestStart();
    }).detach();
}

void AudioEngine::release() {
    {
        std::lock_guard<std::mutex> lk(mControlMutex);
        mPlaying = false;
        closeStream();
    }
    if (mFeederRun.exchange(false)) {
        unparkFeeder();
        mParkCv.notify_all();
        if (mFeeder.joinable()) mFeeder.join();
    }
    mMixer.reset();
}

}  // namespace tfx
