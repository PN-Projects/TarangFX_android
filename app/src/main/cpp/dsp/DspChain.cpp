#include "dsp/DspChain.h"
#include <cmath>

namespace tfx {

void DspChain::prepare(int sampleRate) {
    mRate = sampleRate;
    mVoice.prepare(sampleRate);
    mEq.prepare(sampleRate);
    mBass.prepare(sampleRate);
    mReverb.prepare(sampleRate);
    setParams(mParams);
    mGain = mTargetGain;
}

void DspChain::setParams(const DspParams& p) {
    const bool voiceToggled = p.voiceEnhance != mParams.voiceEnhance;
    mParams = p;
    float zeros[TenBandEq::kBands] = {0};
    mEq.setGains(p.eqEnabled ? p.eqBands : zeros);
    mBass.setParams(p.bassAmount, p.bassFreq);
    mReverb.setParams(p.reverbRoom, p.reverbDamping, p.reverbEnabled ? p.reverbWet : 0.f, p.reverbPreDelayMs);
    mTargetGain = std::pow(10.f, p.volumeDb / 20.f);
    if (voiceToggled) mVoice.reset();
}

void DspChain::process(float* x, int frames) {
    if (mParams.bypass) return;
    if (mParams.voiceEnhance) mVoice.process(x, frames);
    mEq.process(x, frames);
    mBass.process(x, frames);
    mReverb.process(x, frames);
    // Volume with per-block linear ramp (no zipper noise).
    if (mGain != mTargetGain || mGain != 1.f) {
        const float step = (mTargetGain - mGain) / static_cast<float>(frames > 0 ? frames : 1);
        float g = mGain;
        for (int i = 0; i < frames; ++i) {
            g += step;
            x[2 * i] *= g;
            x[2 * i + 1] *= g;
        }
        mGain = mTargetGain;
    }
}

void DspChain::reset() {
    mVoice.reset();
    mEq.reset();
    mBass.reset();
    mReverb.reset();
}

}  // namespace tfx
