#pragma once
// Compressor, de-esser and EBU R128 / ITU-R BS.1770-4 loudness meter.
#include "dsp/BiquadFilter.h"
#include <vector>

namespace tfx {

class Compressor {
public:
    void prepare(int sampleRate);
    void setParams(float thresholdDb, float ratio, float attackMs, float releaseMs, float kneeDb,
                   float makeupDb);
    void process(float* interleaved, int frames);
    void reset() { mEnvDb = -120.f; }

private:
    int mRate = 48000;
    float mThreshold = -18.f, mRatio = 3.f, mKnee = 6.f, mMakeup = 0.f;
    float mAttackCoef = 0.f, mReleaseCoef = 0.f;
    float mEnvDb = -120.f;
};

// Dynamic-EQ de-esser: a band-pass side-chain measures sibilance energy around 7 kHz and drives
// a minimum-phase bell cut at the same frequency, so only the sibilant band is reduced and the
// body of the voice is untouched. (A previous "x - k*highpass" split cancelled poorly near the
// cutoff because of the high-pass phase shift: ~1.7 dB of reduction where 8 dB was requested.)
class DeEsser {
public:
    static constexpr int kControlBlock = 16;  // gain recomputed every 16 samples (0.33 ms @ 48k)

    void prepare(int sampleRate);
    void setParams(float thresholdDb, float maxReductionDb);
    void process(float* interleaved, int frames);
    void reset();

private:
    int mRate = 48000;
    double mCenter = 7000.0;
    MonoBiquad mDetect;
    StereoBiquad mBell;
    float mThreshold = -28.f, mMaxReduction = 8.f;
    float mEnv = 0.f, mAttack = 0.f, mRelease = 0.f;
    float mAppliedDb = 0.f;
};

// Double-precision biquad for metering. The RLB high-pass has poles ~0.005 from the unit
// circle, so float coefficients alone shift the measured loudness.
struct PreciseBiquad {
    double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    double z1 = 0, z2 = 0;
    inline double process(double x) {
        const double y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }
    void reset() { z1 = z2 = 0; }
};

class LoudnessMeter {
public:
    void prepare(int sampleRate);
    void process(const float* interleaved, int frames);  // stereo input
    // When the export is mono, BS.1770 measures the single downmixed channel ((L+R)/2), not L+R
    // power summed (which would read +3 dB too loud for dual-mono content).
    void setMonoOutput(bool mono) { mMono = mono; }
    double integratedLufs() const;                      // gated integrated loudness
    double truePeakApprox() const { return mPeak; }
    void reset();

private:
    int mRate = 48000;
    PreciseBiquad mPreL, mPreR, mRlbL, mRlbR;
    int mHop = 4800;         // 100 ms
    int mHopCount = 0;
    double mHopSum = 0.0;
    std::vector<double> mHopPowers;  // mean-square per 100 ms hop
    double mPeak = 0.0;
    bool mMono = false;
};

}  // namespace tfx
