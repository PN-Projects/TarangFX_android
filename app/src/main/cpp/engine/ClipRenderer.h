#pragma once
// Renders one clip onto the timeline: decoder -> (AI denoise) -> (Rubber Band time/pitch)
// -> gain + fade envelope -> per-clip DSP chain. Runs on the feeder / export thread, never on
// the real-time audio callback.
#include <memory>
#include <vector>
#include "engine/ProjectModel.h"
#include "io/FfmpegDecoder.h"
#include "dsp/DspChain.h"
#include "ai/StreamDenoiser.h"

namespace RubberBand { class RubberBandStretcher; }

namespace tfx {

class ClipRenderer {
public:
    ClipRenderer(const ClipDesc& desc, int sampleRate, bool highQuality);
    ~ClipRenderer();

    int64_t id() const { return mDesc.id; }
    int64_t startFrame() const { return mStart; }
    int64_t endFrame() const { return mEnd; }                 // exclusive, without effect tail
    int64_t tailEndFrame() const { return mEnd + mTail; }

    // Adds this clip's contribution for timeline frames [t0, t0+frames) into `out` (stereo).
    void mixInto(float* out, int64_t t0, int frames, float extraGain);

    // Non-structural update (gain, fades, DSP). Structural changes rebuild the renderer.
    void updateParams(const ClipDesc& desc);
    bool sameStructure(const ClipDesc& other) const;

    void prefetch(int64_t timelineFrame);  // open + seek ahead of time
    void release();                        // free decoder / stretcher memory
    bool isOpen() const { return mOpen; }

private:
    void computeBounds();
    bool reposition(int64_t timelineFrame);
    int pullSource(float* dst, int frames);   // produces exactly `frames` (zero padded)
    int readDecoded(float* dst, int frames);  // raw (denoised) source frames, zero-padded at end
    void setupStretcher();
    float envelopeAt(int64_t localFrame) const;

    ClipDesc mDesc;
    int mRate;
    bool mHighQuality;
    int64_t mStart = 0, mEnd = 0, mTail = 0;

    FfmpegDecoder mDecoder;
    bool mOpen = false;
    bool mOpenFailed = false;
    int64_t mNextFrame = -1;          // expected next timeline frame (for sequential reads)
    int64_t mSrcFramesLeft = 0;       // source frames remaining before trim-out
    int mPendingDenoiseDiscard = 0;
    int mDenoiseFlushLeft = 0;

    std::unique_ptr<RubberBand::RubberBandStretcher> mStretcher;
    std::vector<float> mPlanarIn[2], mPlanarOut[2];
    int mStretchDiscard = 0;
    bool mStretchFinal = false;

    std::unique_ptr<StreamDenoiser> mDenoiser;
    std::string mDenoiseMode;

    DspChain mDsp;
    std::vector<float> mScratch, mScratch2;
    float mGain = 1.f;
};

}  // namespace tfx
