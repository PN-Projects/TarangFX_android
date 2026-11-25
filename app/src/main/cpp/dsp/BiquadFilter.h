#pragma once
// RBJ Audio-EQ-Cookbook biquads (Robert Bristow-Johnson) in Transposed Direct Form II,
// with linear coefficient ramping over 64 samples to avoid zipper noise while sliders move.
#include <cstdint>

namespace tfx {

struct BiquadCoeffs {
    float b0 = 1.f, b1 = 0.f, b2 = 0.f, a1 = 0.f, a2 = 0.f;
    bool operator==(const BiquadCoeffs& o) const {
        return b0 == o.b0 && b1 == o.b1 && b2 == o.b2 && a1 == o.a1 && a2 == o.a2;
    }
    bool isIdentity() const { return b0 == 1.f && b1 == 0.f && b2 == 0.f && a1 == 0.f && a2 == 0.f; }
};

enum class BiquadType { Peaking, LowShelf, HighShelf, LowPass, HighPass, BandPass };

BiquadCoeffs designBiquad(BiquadType type, double sampleRate, double freq, double q, double gainDb);

class StereoBiquad {
public:
    static constexpr int kRampSamples = 64;

    void setTarget(const BiquadCoeffs& c, bool immediate = false);
    void process(float* interleaved, int frames);  // 2 channels interleaved
    void reset();
    bool isIdle() const { return mRampLeft == 0 && mCur.isIdentity(); }

private:
    BiquadCoeffs mCur, mTarget, mStep;
    int mRampLeft = 0;
    double mZ1[2] = {0, 0}, mZ2[2] = {0, 0};
};

// Single-channel variant used for side-chains / meters.
class MonoBiquad {
public:
    void set(const BiquadCoeffs& c) { mC = c; }
    inline float process(float x) {
        const double y = mC.b0 * x + mZ1;
        mZ1 = mC.b1 * x - mC.a1 * y + mZ2;
        mZ2 = mC.b2 * x - mC.a2 * y;
        return static_cast<float>(y);
    }
    void reset() { mZ1 = mZ2 = 0; }

private:
    BiquadCoeffs mC;
    double mZ1 = 0, mZ2 = 0;
};

}  // namespace tfx
