#pragma once
// Streaming neural denoiser interface. Implementations keep recurrent state across calls so
// arbitrarily long inputs are processed chunk by chunk with no boundary clicks.
#include <memory>
#include <string>

namespace tfx {

class StreamDenoiser {
public:
    virtual ~StreamDenoiser() = default;
    // In-place on interleaved stereo at the sample rate given to createDenoiser().
    // Output is delayed by latencyFrames() relative to input.
    virtual void process(float* interleaved, int frames) = 0;
    virtual int latencyFrames() const = 0;
    virtual void reset() = 0;
    virtual void setStrength(float wet01) = 0;
};

// mode: "studio" (DeepFilterNet3, 48 kHz) or "fast" (GTCRN, 16 kHz). Returns nullptr when the
// model or ONNX Runtime is unavailable.
std::unique_ptr<StreamDenoiser> createDenoiser(const std::string& mode, int sampleRate);

// Directory holding the extracted .onnx models (copied from APK assets by Kotlin on first use).
void setDenoiserModelDirectory(const std::string& dir);
bool isDenoiserAvailable(const std::string& mode);

}  // namespace tfx
