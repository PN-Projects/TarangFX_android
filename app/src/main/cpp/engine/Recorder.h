#pragma once
// Oboe input -> lock-free ring -> writer thread (gain, optional live AI denoise, peaks)
// -> FfmpegEncoder (WAV / FLAC / M4A). The input callback only copies into the ring buffer.
#include <oboe/Oboe.h>
#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include "common/SpscRingBuffer.h"
#include "io/FfmpegEncoder.h"
#include "ai/StreamDenoiser.h"

namespace tfx {

class Recorder : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    static Recorder& instance();

    bool start(const std::string& path, const EncoderConfig& cfg, int channels, bool liveDenoise,
               std::string& error);
    void pause() { mPaused = true; }
    void resume() { mPaused = false; }
    int64_t stop();  // returns recorded duration in microseconds, or -1 on failure
    bool isActive() const { return mActive.load(); }
    bool isPaused() const { return mPaused.load(); }
    void setGainDb(float db);
    float level() const { return mLevel.load(); }   // 0..1 peak of the last ~50 ms
    int64_t recordedUs() const;
    std::vector<float> drainPeaks();                // 50 peaks/second since last call
    void setLiveDenoise(bool on) { mDenoiseOn = on; }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* data, int32_t frames) override;
    void onErrorAfterClose(oboe::AudioStream*, oboe::Result) override;

private:
    Recorder() = default;
    void writerLoop();

    std::shared_ptr<oboe::AudioStream> mStream;
    std::unique_ptr<SpscRingBuffer<float>> mRing;
    std::thread mWriter;
    FfmpegEncoder mEncoder;
    std::unique_ptr<StreamDenoiser> mDenoiser;

    int mRate = 48000;
    int mChannels = 1;
    std::atomic<bool> mActive{false};
    std::atomic<bool> mPaused{false};
    std::atomic<bool> mStopRequested{false};
    std::atomic<bool> mDenoiseOn{false};
    std::atomic<float> mGain{1.f};
    std::atomic<float> mLevel{0.f};
    std::atomic<int64_t> mWrittenFrames{0};
    std::atomic<bool> mWriteFailed{false};

    std::mutex mPeaksMutex;
    std::vector<float> mPeaks;
};

}  // namespace tfx
