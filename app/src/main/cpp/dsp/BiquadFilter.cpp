#include "dsp/BiquadFilter.h"
#include <cmath>
#include <algorithm>

namespace tfx {

BiquadCoeffs designBiquad(BiquadType type, double fs, double f0, double q, double gainDb) {
    f0 = std::clamp(f0, 10.0, fs * 0.49);
    q = std::max(q, 0.05);
    const double A = std::pow(10.0, gainDb / 40.0);
    const double w0 = 2.0 * M_PI * f0 / fs;
    const double cw = std::cos(w0), sw = std::sin(w0);
    const double alpha = sw / (2.0 * q);
    double b0 = 1, b1 = 0, b2 = 0, a0 = 1, a1 = 0, a2 = 0;
    switch (type) {
        case BiquadType::Peaking:
            b0 = 1 + alpha * A; b1 = -2 * cw; b2 = 1 - alpha * A;
            a0 = 1 + alpha / A; a1 = -2 * cw; a2 = 1 - alpha / A;
            break;
        case BiquadType::LowShelf: {
            const double sa = 2 * std::sqrt(A) * alpha;
            b0 = A * ((A + 1) - (A - 1) * cw + sa);
            b1 = 2 * A * ((A - 1) - (A + 1) * cw);
            b2 = A * ((A + 1) - (A - 1) * cw - sa);
            a0 = (A + 1) + (A - 1) * cw + sa;
            a1 = -2 * ((A - 1) + (A + 1) * cw);
            a2 = (A + 1) + (A - 1) * cw - sa;
            break;
        }
        case BiquadType::HighShelf: {
            const double sa = 2 * std::sqrt(A) * alpha;
            b0 = A * ((A + 1) + (A - 1) * cw + sa);
            b1 = -2 * A * ((A - 1) + (A + 1) * cw);
            b2 = A * ((A + 1) + (A - 1) * cw - sa);
            a0 = (A + 1) - (A - 1) * cw + sa;
            a1 = 2 * ((A - 1) - (A + 1) * cw);
            a2 = (A + 1) - (A - 1) * cw - sa;
            break;
        }
        case BiquadType::LowPass:
            b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2;
            a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha;
            break;
        case BiquadType::HighPass:
            b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2;
            a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha;
            break;
        case BiquadType::BandPass:
            b0 = alpha; b1 = 0; b2 = -alpha;
            a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha;
            break;
    }
    BiquadCoeffs c;
    c.b0 = static_cast<float>(b0 / a0);
    c.b1 = static_cast<float>(b1 / a0);
    c.b2 = static_cast<float>(b2 / a0);
    c.a1 = static_cast<float>(a1 / a0);
    c.a2 = static_cast<float>(a2 / a0);
    return c;
}

void StereoBiquad::setTarget(const BiquadCoeffs& c, bool immediate) {
    if (immediate) {
        mCur = mTarget = c;
        mRampLeft = 0;
        return;
    }
    if (c == mTarget) return;
    mTarget = c;
    const float inv = 1.0f / kRampSamples;
    mStep.b0 = (c.b0 - mCur.b0) * inv;
    mStep.b1 = (c.b1 - mCur.b1) * inv;
    mStep.b2 = (c.b2 - mCur.b2) * inv;
    mStep.a1 = (c.a1 - mCur.a1) * inv;
    mStep.a2 = (c.a2 - mCur.a2) * inv;
    mRampLeft = kRampSamples;
}

void StereoBiquad::reset() {
    mZ1[0] = mZ1[1] = mZ2[0] = mZ2[1] = 0;
}

void StereoBiquad::process(float* x, int frames) {
    if (mRampLeft == 0 && mCur.isIdentity()) return;
    for (int i = 0; i < frames; ++i) {
        if (mRampLeft > 0) {
            mCur.b0 += mStep.b0; mCur.b1 += mStep.b1; mCur.b2 += mStep.b2;
            mCur.a1 += mStep.a1; mCur.a2 += mStep.a2;
            if (--mRampLeft == 0) mCur = mTarget;
        }
        for (int ch = 0; ch < 2; ++ch) {
            const double in = x[2 * i + ch];
            const double y = mCur.b0 * in + mZ1[ch];
            mZ1[ch] = mCur.b1 * in - mCur.a1 * y + mZ2[ch];
            mZ2[ch] = mCur.b2 * in - mCur.a2 * y;
            x[2 * i + ch] = static_cast<float>(y);
        }
    }
    // Ramped to identity (e.g. a dynamic EQ releasing): drop the residual state so a later
    // re-engage starts clean instead of replaying a stale sample.
    if (mRampLeft == 0 && mCur.isIdentity()) {
        reset();
        return;
    }
    // Flush denormals.
    for (int ch = 0; ch < 2; ++ch) {
        if (std::fabs(mZ1[ch]) < 1e-20) mZ1[ch] = 0;
        if (std::fabs(mZ2[ch]) < 1e-20) mZ2[ch] = 0;
    }
}

}  // namespace tfx
