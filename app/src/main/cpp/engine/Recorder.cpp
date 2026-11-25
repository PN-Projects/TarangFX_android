#include "engine/Recorder.h"
#include "common/Log.h"

#include <algorithm>
#include <chrono>
#include <cmath>

namespace tfx {

Recorder& Recorder::instance() {
    static Recorder* r = new Recorder();
    return *r;
}

bool Recorder::start(const std::string& path, const EncoderConfig& cfg, int channels,
                     bool liveDenoise, std::string& error) {
    if (mActive.load()) {
        error = "Recorder already active";
        return false;
    }
    mChannels = std::clamp(channels, 1, 2);
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Input)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(mChannels)
        ->setSampleRate(cfg.sampleRate > 0 ? cfg.sampleRate : 48000)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        // VoiceRecognition = no AGC / minimal processing on most devices (hi-fi capture).
        ->setInputPreset(oboe::InputPreset::VoiceRecognition)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    oboe::Result r = b.openStream(mStream);
    if (r != oboe::Result::OK) {
        error = std::string("Cannot open microphone: ") + oboe::convertToText(r);
        mStream.reset();
        return false;
    }
    mRate = mStream->getSampleRate();
    mChannels = mStream->getChannelCount();
    // ~2 seconds of headroom between the RT callback and the writer thread.
    mRing = std::make_unique<SpscRingBuffer<float>>(static_cast<size_t>(mRate) * mChannels * 2);

    EncoderConfig ec = cfg;
    ec.sampleRate = mRate;
    ec.channels = mChannels;
    if (!mEncoder.open(path, ec, mRate, mChannels)) {
        error = mEncoder.error();
        mStream->close();
        mStream.reset();
        return false;
    }
    mDenoiser = createDenoiser("fast", mRate);  // nullptr if models unavailable
    mDenoiseOn = liveDenoise && mDenoiser != nullptr;
    mWrittenFrames = 0;
    mWriteFailed = false;
    mPaused = false;
    mStopRequested = false;
    {
        std::lock_guard<std::mutex> lk(mPeaksMutex);
        mPeaks.clear();
    }
    mActive = true;
    mWriter = std::thread(&Recorder::writerLoop, this);
    r = mStream->requestStart();
    if (r != oboe::Result::OK) {
        error = std::string("Cannot start microphone: ") + oboe::convertToText(r);
        stop();
        return false;
    }
    return true;
}

void Recorder::setGainDb(float db) { mGain = std::pow(10.f, std::clamp(db, -24.f, 24.f) / 20.f); }

int64_t Recorder::recordedUs() const {
    return mRate > 0 ? mWrittenFrames.load() * 1000000 / mRate : 0;
}

std::vector<float> Recorder::drainPeaks() {
    std::lock_guard<std::mutex> lk(mPeaksMutex);
    std::vector<float> out;
    out.swap(mPeaks);
    return out;
}

oboe::DataCallbackResult Recorder::onAudioReady(oboe::AudioStream*, void* data, int32_t frames) {
    if (mRing) mRing->write(static_cast<const float*>(data), static_cast<size_t>(frames) * mChannels);
    return oboe::DataCallbackResult::Continue;
}

void Recorder::onErrorAfterClose(oboe::AudioStream*, oboe::Result error) {
    LOGW("Input stream closed: %s", oboe::convertToText(error));
    mStopRequested = true;
}

void Recorder::writerLoop() {
    const int chunk = 1024;
    std::vector<float> in(static_cast<size_t>(chunk) * mChannels);
    std::vector<float> stereo(static_cast<size_t>(chunk) * 2);
    const int peakBucket = std::max(1, mRate / 50);
    int bucketCount = 0;
    float bucketPeak = 0.f;
    while (true) {
        const size_t avail = mRing->availableToRead() / mChannels;
        if (avail < static_cast<size_t>(chunk)) {
            if (mStopRequested.load() && avail == 0) break;
            if (!mStopRequested.load()) {
                std::this_thread::sleep_for(std::chrono::milliseconds(5));
                continue;
            }
        }
        const int n = static_cast<int>(std::min<size_t>(avail, chunk));
        if (n == 0) break;
        mRing->read(in.data(), static_cast<size_t>(n) * mChannels);
        if (mPaused.load()) continue;  // discard while paused, keep stream warm

        const float g = mGain.load();
        float lvl = 0.f;
        for (int i = 0; i < n * mChannels; ++i) in[i] *= g;
        if (mDenoiseOn.load() && mDenoiser) {
            for (int i = 0; i < n; ++i) {
                stereo[2 * i] = in[i * mChannels];
                stereo[2 * i + 1] = in[i * mChannels + (mChannels - 1)];
            }
            mDenoiser->process(stereo.data(), n);
            for (int i = 0; i < n; ++i) {
                if (mChannels == 1) in[i] = 0.5f * (stereo[2 * i] + stereo[2 * i + 1]);
                else { in[2 * i] = stereo[2 * i]; in[2 * i + 1] = stereo[2 * i + 1]; }
            }
        }
        for (int i = 0; i < n; ++i) {
            float p = 0.f;
            for (int c = 0; c < mChannels; ++c) p = std::max(p, std::fabs(in[i * mChannels + c]));
            lvl = std::max(lvl, p);
            bucketPeak = std::max(bucketPeak, p);
            if (++bucketCount >= peakBucket) {
                std::lock_guard<std::mutex> lk(mPeaksMutex);
                if (mPeaks.size() < 100000) mPeaks.push_back(std::min(1.f, bucketPeak));
                bucketPeak = 0.f;
                bucketCount = 0;
            }
        }
        mLevel = std::min(1.f, lvl);
        // Encoder expects interleaved float with mChannels channels.
        if (!mEncoder.write(in.data(), n)) {
            LOGE("Recorder write failed: %s", mEncoder.error().c_str());
            mWriteFailed = true;
            break;
        }
        mWrittenFrames += n;
    }
}

int64_t Recorder::stop() {
    if (!mActive.load()) return -1;
    if (mStream) {
        mStream->stop();
        mStream->close();
        mStream.reset();
    }
    mStopRequested = true;
    if (mWriter.joinable()) mWriter.join();
    const bool ok = !mWriteFailed.load() && mEncoder.finish();
    mDenoiser.reset();
    mRing.reset();
    mActive = false;
    mLevel = 0.f;
    return ok ? recordedUs() : -1;
}

}  // namespace tfx
