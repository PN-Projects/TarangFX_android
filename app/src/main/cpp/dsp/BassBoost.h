#pragma once
#include "dsp/BiquadFilter.h"

namespace tfx {

// Resonant low-shelf + low-band harmonic saturation (sub-harmonic enhancer) followed by a
// soft knee so heavy boost never hard-clips.
class BassBoost {
public:
    void prepare(int sampleRate);
    void setParams(float amount /*0..1*/, float freqHz /*50..120*/);
    void process(float* interleaved, int frames);
    void reset();
    bool active() const { return mAmount > 0.001f || !mShelf.isIdle(); }

private:
    int mRate = 48000;
    float mAmount = 0.f;
    float mFreq = 80.f;
    StereoBiquad mShelf;
    MonoBiquad mLpL, mLpR;
};

}  // namespace tfx
