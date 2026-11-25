#include "dsp/SoftLimiter.h"
#include <algorithm>
#include <cmath>

namespace tfx {

void SoftLimiter::prepare(int sampleRate, float ceilingDb, float lookaheadMs, float releaseMs) {
    mCeiling = std::pow(10.f, ceilingDb / 20.f);
    mLookahead = std::max(1, static_cast<int>(lookaheadMs * 0.001f * sampleRate));
    mDelay.assign(static_cast<size_t>(mLookahead) * 2, 0.f);
    mWinCap = mLookahead + 2;
    mWinVal.assign(mWinCap, 1.f);
    mWinIdx.assign(mWinCap, 0);
    mReleaseCoef = 1.f - std::exp(-1.f / (releaseMs * 0.001f * sampleRate));
    mAttackStep = 1.f / static_cast<float>(mLookahead);
    reset();
}

void SoftLimiter::reset() {
    std::fill(mDelay.begin(), mDelay.end(), 0.f);
    mHead = mTail = 0;
    mCounter = 0;
    mDelayIdx = 0;
    mGain = 1.f;
}

static inline float safetyClip(float x, float ceiling) {
    const float knee = ceiling * 0.9f;
    const float a = std::fabs(x);
    if (a <= knee) return x;
    const float range = ceiling - knee;
    const float y = knee + range * std::tanh((a - knee) / range);
    return x < 0 ? -y : y;
}

void SoftLimiter::process(float* x, int frames) {
    if (mDelay.empty()) return;
    for (int i = 0; i < frames; ++i) {
        const float l = x[2 * i], r = x[2 * i + 1];
        const float peak = std::max(std::fabs(l), std::fabs(r));
        const float req = peak > mCeiling ? mCeiling / peak : 1.f;

        // Push into monotonic deque (keeps increasing values from front to back -> front is min).
        while (mTail != mHead) {
            int last = (mTail - 1 + mWinCap) % mWinCap;
            if (mWinVal[last] >= req) mTail = last; else break;
        }
        mWinVal[mTail] = req;
        mWinIdx[mTail] = mCounter;
        mTail = (mTail + 1) % mWinCap;
        while (mWinIdx[mHead] <= mCounter - mLookahead) mHead = (mHead + 1) % mWinCap;
        const float target = mWinVal[mHead];
        ++mCounter;

        if (target < mGain) {
            // Linear attack reaching the target within the lookahead window.
            mGain = std::max(target, mGain - mAttackStep);
        } else {
            mGain += (target - mGain) * mReleaseCoef;
        }

        // Read delayed sample, write current.
        const float dl = mDelay[2 * mDelayIdx], dr = mDelay[2 * mDelayIdx + 1];
        mDelay[2 * mDelayIdx] = l;
        mDelay[2 * mDelayIdx + 1] = r;
        if (++mDelayIdx >= mLookahead) mDelayIdx = 0;

        x[2 * i] = safetyClip(dl * mGain, mCeiling);
        x[2 * i + 1] = safetyClip(dr * mGain, mCeiling);
    }
}

}  // namespace tfx
