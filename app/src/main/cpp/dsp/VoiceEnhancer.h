#pragma once
// Studio voice mastering chain (AI denoise runs before this, loudness normalisation after):
//   80 Hz high-pass (24 dB/oct) -> De-esser -> Vocal presence EQ -> Soft-knee compressor
#include "dsp/BiquadFilter.h"
#include "dsp/Dynamics.h"

namespace tfx {

class VoiceEnhancer {
public:
    void prepare(int sampleRate);
    void process(float* interleaved, int frames);
    void reset();

private:
    StereoBiquad mHp1, mHp2;
    DeEsser mDeEsser;
    StereoBiquad mMud, mPresence, mAir;
    Compressor mComp;
};

}  // namespace tfx
