#pragma once
// Real-time playback engine.
//
//   Kotlin (JNI) --control--> AudioEngine --park/flush--> feeder thread (MultitrackMixer)
//                                                             | SPSC ring (PCM) + SPSC ring (block markers)
//                                                             v
//                                          Oboe data callback (real-time thread)
//
// The Oboe callback never allocates, locks, or does I/O: it only pops PCM from a lock-free
// ring buffer and updates an atomic playhead. Each block written by the feeder carries a
// marker with its timeline position, so the reported playhead is exact even across loops
// and skip regions.
#include <oboe/Oboe.h>
#include <atomic>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <vector>

#include "common/SpscRingBuffer.h"
#include "engine/MultitrackMixer.h"

namespace tfx {

class AudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    static AudioEngine& instance();

    bool setProject(const std::string& json, std::string& error);
    void clearProject();
    void play();
    void pause();
    void seekUs(int64_t us);
    int64_t positionUs() const;
    int64_t durationUs() const;
    bool isPlaying() const { return mPlaying.load(); }
    int sampleRate();

    // Restrict playback to [startUs, endUs) (endUs <= 0 => to end). Optional looping.
    void setPlayRange(int64_t startUs, int64_t endUs, bool loop);
    // Jump over [startUs, endUs) during playback (Trim "delete selected" preview).
    void setSkipRange(int64_t startUs, int64_t endUs);
    bool updateClip(const std::string& clipJson);
    bool setMasterDsp(const std::string& dspJson);
    void setTrackState(int64_t trackId, float gainDb, bool muted, bool solo);

    void release();

    // Oboe callbacks
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    AudioEngine();
    ~AudioEngine() override;

    struct BlockMarker {
        int64_t position;  // timeline frame of the first frame in this block
        int32_t frames;
    };
    struct PendingUpdates {
        std::vector<ClipDesc> clips;
        std::optional<DspParams> master;
        struct TrackState { int64_t id; float gainDb; bool muted; bool solo; };
        std::vector<TrackState> tracks;
    };

    bool ensureStream();
    void closeStream();
    void startFeeder();
    void feederLoop();
    void parkFeeder();
    void unparkFeeder();
    void flushBuffers();
    void applyPendingUpdates();
    int64_t usToFrames(int64_t us) const { return us * mRate / 1000000; }

    std::mutex mControlMutex;  // serialises all JNI control calls
    std::shared_ptr<oboe::AudioStream> mStream;
    int mRate = 0;

    std::unique_ptr<MultitrackMixer> mMixer;  // touched only by feeder or while it is parked

    SpscRingBuffer<float> mPcm{16384 * 2};
    SpscRingBuffer<BlockMarker> mMarkers{512};

    // Feeder thread control
    std::thread mFeeder;
    std::atomic<bool> mFeederRun{false};
    std::atomic<bool> mParkRequest{false};
    bool mParked = false;
    std::mutex mParkMutex;
    std::condition_variable mParkCv;

    // Pending parameter updates (feeder applies them at block boundaries; never the RT thread)
    std::mutex mPendingMutex;
    PendingUpdates mPending;
    std::atomic<bool> mHasPending{false};

    // Shared state
    std::atomic<bool> mPlaying{false};
    std::atomic<bool> mFeederDone{false};   // feeder reached end of range
    std::atomic<bool> mEnded{false};        // callback drained everything after feeder done
    std::atomic<int64_t> mPlayhead{0};      // frames
    std::atomic<int64_t> mFeedPos{0};       // frames (feeder's next position)
    std::atomic<int64_t> mRangeStart{0}, mRangeEnd{0};
    std::atomic<bool> mLoop{false};
    std::atomic<int64_t> mSkipStart{-1}, mSkipEnd{-1};
    std::atomic<uint32_t> mFlushReq{0}, mFlushAck{0};

    // Callback-only state
    BlockMarker mCurMarker{0, 0};
    int32_t mCurConsumed = 0;
    bool mHaveMarker = false;
};

}  // namespace tfx
