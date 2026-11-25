#pragma once
// ONNX Runtime streaming denoisers (contracts documented in tools/models/README.md):
//   studio: DeepFilterNet3 torchDF streaming graph (dfn3_stream.onnx) - 48 kHz, 512-sample hop,
//           STFT/ERB/deep-filter inside the graph, 13 recurrent state tensors, 1536-sample delay.
//   fast:   GTCRN (gtcrn_simple.onnx) - 16 kHz, 512-pt FFT / 256 hop periodic sqrt-Hann STFT done
//           here, 3 recurrent caches.
//
// Pipeline (all on the feeder/export thread, never the RT callback):
//   stereo@engineRate -> mono mid -> resample to model rate -> hop FIFO -> model -> resample back
//   -> output FIFO (pre-filled) ; dry path delayed by the exact same latency for wet/dry mixing.
#include <memory>
#include <string>
#include <vector>
#include "ai/StreamDenoiser.h"

struct SwrContext;

namespace tfx {

class OnnxModelRunner;  // model-specific (GTCRN / DFN3)

class OnnxDenoiser : public StreamDenoiser {
public:
    static bool modelAvailable(const std::string& dir, const std::string& mode);
    static std::unique_ptr<StreamDenoiser> create(const std::string& dir, const std::string& mode, int sampleRate);

    ~OnnxDenoiser() override;
    void process(float* interleaved, int frames) override;
    int latencyFrames() const override { return mLatency; }
    void reset() override;
    void setStrength(float wet01) override;

private:
    OnnxDenoiser() = default;
    bool init(std::unique_ptr<OnnxModelRunner> runner, int engineRate);
    void pushModelInput(const float* mono, int n);
    void runAvailableHops();
    int measureResamplerDelay();

    std::unique_ptr<OnnxModelRunner> mRunner;
    int mEngineRate = 48000;
    int mModelRate = 48000;
    int mHop = 512;
    bool mResample = false;
    SwrContext* mDown = nullptr;
    SwrContext* mUp = nullptr;

    std::vector<float> mModelIn;    // model-rate mono FIFO
    std::vector<float> mWetOut;     // engine-rate mono FIFO
    size_t mWetRead = 0;
    std::vector<float> mHopBuf, mHopOut, mScratch, mScratch2;

    std::vector<float> mDry;        // stereo delay line (latency frames)
    size_t mDryPos = 0;
    int mLatency = 0;
    int mPrefill = 0;
    float mStrength = 1.f, mStrengthTarget = 1.f;
};

}  // namespace tfx
