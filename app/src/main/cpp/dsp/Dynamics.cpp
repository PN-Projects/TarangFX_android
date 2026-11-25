#include "dsp/Dynamics.h"
#include <algorithm>
#include <cmath>

namespace tfx {

static inline float toDb(float lin) { return 20.f * std::log10(std::max(lin, 1e-9f)); }
static inline float fromDb(float db) { return std::pow(10.f, db / 20.f); }

// ------------------------------------------------------------------ Compressor
void Compressor::prepare(int sampleRate) {
    mRate = sampleRate;
    setParams(mThreshold, mRatio, 10.f, 120.f, mKnee, mMakeup);
}

void Compressor::setParams(float thresholdDb, float ratio, float attackMs, float releaseMs,
                           float kneeDb, float makeupDb) {
    mThreshold = thresholdDb;
    mRatio = std::max(1.f, ratio);
    mKnee = std::max(0.f, kneeDb);
    mMakeup = makeupDb;
    mAttackCoef = std::exp(-1.f / (std::max(0.1f, attackMs) * 0.001f * mRate));
    mReleaseCoef = std::exp(-1.f / (std::max(1.f, releaseMs) * 0.001f * mRate));
}

void Compressor::process(float* x, int frames) {
    const float makeup = fromDb(mMakeup);
    const float slope = 1.f / mRatio - 1.f;
    const float halfKnee = 0.5f * mKnee;
    for (int i = 0; i < frames; ++i) {
        const float peak = std::max(std::fabs(x[2 * i]), std::fabs(x[2 * i + 1]));
        const float inDb = toDb(peak);
        const float coef = inDb > mEnvDb ? mAttackCoef : mReleaseCoef;
        mEnvDb = coef * mEnvDb + (1.f - coef) * inDb;
        // Soft-knee static curve
        const float over = mEnvDb - mThreshold;
        if (over < -halfKnee) {
            // Below the knee (most of the time for speech pauses / quiet passages): no gain
            // reduction, so skip the dB->linear pow() entirely.
            x[2 * i] *= makeup;
            x[2 * i + 1] *= makeup;
            continue;
        }
        float grDb;
        if (mKnee > 0.f && over <= halfKnee) {
            const float t = over + halfKnee;
            grDb = slope * t * t / (2.f * mKnee);
        } else {
            grDb = slope * over;
        }
        const float g = fromDb(grDb) * makeup;
        x[2 * i] *= g;
        x[2 * i + 1] *= g;
    }
}

// ------------------------------------------------------------------ DeEsser
void DeEsser::prepare(int sampleRate) {
    mRate = sampleRate;
    mCenter = std::min(7000.0, sampleRate * 0.4);
    mDetect.set(designBiquad(BiquadType::BandPass, sampleRate, mCenter, 1.2, 0));
    mAttack = 1.f - std::exp(-1.f / (0.001f * sampleRate));   // 1 ms
    mRelease = 1.f - std::exp(-1.f / (0.060f * sampleRate));  // 60 ms
    reset();
}

void DeEsser::setParams(float thresholdDb, float maxReductionDb) {
    mThreshold = thresholdDb;
    mMaxReduction = maxReductionDb;
}

void DeEsser::reset() {
    mDetect.reset();
    mBell.setTarget(BiquadCoeffs{}, true);
    mBell.reset();
    mEnv = 0.f;
    mAppliedDb = 0.f;
}

void DeEsser::process(float* x, int frames) {
    // Linear threshold so the per-sample path has no transcendental calls.
    const float threshLin = fromDb(mThreshold);
    for (int start = 0; start < frames; start += kControlBlock) {
        const int n = std::min(kControlBlock, frames - start);
        float* blk = x + 2 * start;
        // 1) Side-chain: envelope of the sibilance band (detector sees the unprocessed input).
        for (int i = 0; i < n; ++i) {
            const float d = std::fabs(mDetect.process(0.5f * (blk[2 * i] + blk[2 * i + 1])));
            mEnv += (d > mEnv ? mAttack : mRelease) * (d - mEnv);
        }
        // 2) Control: reduction = 0.6 dB per dB over threshold, capped. Quantised to 0.25 dB so
        //    the bell is only redesigned when the gain really moves; StereoBiquad's 64-sample
        //    coefficient ramp makes each change click-free.
        float redDb = 0.f;
        if (mEnv > threshLin) {
            redDb = std::min((toDb(mEnv) - mThreshold) * 0.6f, mMaxReduction);
            redDb = std::round(redDb * 4.f) * 0.25f;
        }
        if (redDb != mAppliedDb) {
            mAppliedDb = redDb;
            mBell.setTarget(redDb > 0.f ? designBiquad(BiquadType::Peaking, mRate, mCenter, 1.0, -redDb)
                                        : BiquadCoeffs{});
        }
        // 3) Apply the dynamic bell (no-op while idle and fully released).
        mBell.process(blk, n);
    }
}

// ------------------------------------------------------------------ LoudnessMeter (BS.1770-4)
void LoudnessMeter::prepare(int sampleRate) {
    mRate = sampleRate;
    // K-weighting per ITU-R BS.1770-4, re-derived for any sample rate with the bilinear-transform
    // parameterisation popularised by libebur128 (MIT). At 48 kHz this reproduces the standard's
    // published coefficients; the generic RBJ shelf does not (it read 0.25 LU low on EBU
    // Tech 3341 test signals).
    const double fs = sampleRate;
    {   // Stage 1: high-shelf pre-filter, +4 dB above ~1.68 kHz
        const double f0 = 1681.974450955533, G = 3.999843853973347, Q = 0.7071752369554196;
        const double K = std::tan(M_PI * f0 / fs);
        const double Vh = std::pow(10.0, G / 20.0);
        const double Vb = std::pow(Vh, 0.4996667741545416);
        const double a0 = 1.0 + K / Q + K * K;
        PreciseBiquad pre;
        pre.b0 = (Vh + Vb * K / Q + K * K) / a0;
        pre.b1 = 2.0 * (K * K - Vh) / a0;
        pre.b2 = (Vh - Vb * K / Q + K * K) / a0;
        pre.a1 = 2.0 * (K * K - 1.0) / a0;
        pre.a2 = (1.0 - K / Q + K * K) / a0;
        mPreL = mPreR = pre;
    }
    {   // Stage 2: RLB high-pass at ~38 Hz
        const double f0 = 38.13547087602444, Q = 0.5003270373238773;
        const double K = std::tan(M_PI * f0 / fs);
        const double a0 = 1.0 + K / Q + K * K;
        PreciseBiquad rlb;
        rlb.b0 = 1.0; rlb.b1 = -2.0; rlb.b2 = 1.0;
        rlb.a1 = 2.0 * (K * K - 1.0) / a0;
        rlb.a2 = (1.0 - K / Q + K * K) / a0;
        mRlbL = mRlbR = rlb;
    }
    mHop = std::max(1, sampleRate / 10);
    reset();
}

void LoudnessMeter::reset() {
    mPreL.reset(); mPreR.reset(); mRlbL.reset(); mRlbR.reset();
    mHopCount = 0;
    mHopSum = 0.0;
    mHopPowers.clear();
    mPeak = 0.0;
}

void LoudnessMeter::process(const float* x, int frames) {
    for (int i = 0; i < frames; ++i) {
        const float l = x[2 * i], r = x[2 * i + 1];
        mPeak = std::max<double>(mPeak, std::max(std::fabs(l), std::fabs(r)));
        if (mMono) {
            const double km = mRlbL.process(mPreL.process(0.5 * (double(l) + r)));
            mHopSum += km * km;
        } else {
            const double kl = mRlbL.process(mPreL.process(l));
            const double kr = mRlbR.process(mPreR.process(r));
            mHopSum += kl * kl + kr * kr;
        }
        if (++mHopCount >= mHop) {
            mHopPowers.push_back(mHopSum / mHop);
            mHopSum = 0.0;
            mHopCount = 0;
        }
    }
}

double LoudnessMeter::integratedLufs() const {
    // 400 ms gating blocks with 75% overlap = 4 consecutive 100 ms hops.
    std::vector<double> blocks;
    if (mHopPowers.size() < 4) {
        double s = 0;
        for (double p : mHopPowers) s += p;
        if (mHopPowers.empty() || s <= 0) return -70.0;
        return -0.691 + 10.0 * std::log10(s / mHopPowers.size());
    }
    blocks.reserve(mHopPowers.size());
    for (size_t i = 0; i + 3 < mHopPowers.size(); ++i) {
        blocks.push_back((mHopPowers[i] + mHopPowers[i + 1] + mHopPowers[i + 2] + mHopPowers[i + 3]) / 4.0);
    }
    auto lufs = [](double ms) { return -0.691 + 10.0 * std::log10(std::max(ms, 1e-12)); };
    // Gates are compared in the power domain (one log10 per gate instead of one per block).
    // Absolute gate: -70 LUFS  <=>  mean square > 10^((-70 + 0.691) / 10).
    const double absGate = std::pow(10.0, (-70.0 + 0.691) / 10.0);
    double sum = 0; size_t n = 0;
    for (double b : blocks) if (b > absGate) { sum += b; ++n; }
    if (n == 0) return -70.0;
    // Relative gate: 10 LU below the absolute-gated mean  <=>  power ratio 0.1.
    const double relGate = std::max(absGate, (sum / n) * 0.1);
    sum = 0; n = 0;
    for (double b : blocks) if (b > relGate) { sum += b; ++n; }
    if (n == 0) return -70.0;
    return lufs(sum / n);
}

}  // namespace tfx
