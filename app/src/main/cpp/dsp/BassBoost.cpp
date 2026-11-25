#include "dsp/BassBoost.h"
#include <algorithm>
#include <cmath>

namespace tfx {

void BassBoost::prepare(int sampleRate) {
    mRate = sampleRate;
    setParams(mAmount, mFreq);
}

void BassBoost::setParams(float amount, float freq) {
    amount = std::clamp(amount, 0.f, 1.f);
    freq = std::clamp(freq, 40.f, 150.f);
    mAmount = amount;
    mFreq = freq;
    BiquadCoeffs shelf;
    if (amount > 0.001f) {
        // Q > 0.707 gives the shelf a slight resonant bump at the corner frequency.
        shelf = designBiquad(BiquadType::LowShelf, mRate, freq, 0.9, amount * 15.0);
    }
    mShelf.setTarget(shelf);
    const BiquadCoeffs lp = designBiquad(BiquadType::LowPass, mRate, freq * 1.6, 0.707, 0);
    mLpL.set(lp);
    mLpR.set(lp);
}

static inline float softKnee(float x) {
    // Transparent below 0.9, smoothly saturates towards 1.0 above.
    const float a = std::fabs(x);
    if (a <= 0.9f) return x;
    const float y = 0.9f + 0.1f * std::tanh((a - 0.9f) / 0.1f);
    return x < 0 ? -y : y;
}

void BassBoost::process(float* x, int frames) {
    if (!active()) return;
    mShelf.process(x, frames);
    if (mAmount <= 0.001f) return;
    const float drive = 1.f + 3.f * mAmount;
    const float mix = 0.6f * mAmount;
    for (int i = 0; i < frames; ++i) {
        float& l = x[2 * i];
        float& r = x[2 * i + 1];
        const float lowL = mLpL.process(l);
        const float lowR = mLpR.process(r);
        // Odd-harmonic residue of the low band makes bass audible on small phone speakers.
        const float hL = std::tanh(drive * lowL) / drive - lowL;
        const float hR = std::tanh(drive * lowR) / drive - lowR;
        l = softKnee(l - mix * hL);
        r = softKnee(r - mix * hR);
    }
}

void BassBoost::reset() {
    mShelf.reset();
    mLpL.reset();
    mLpR.reset();
}

}  // namespace tfx
