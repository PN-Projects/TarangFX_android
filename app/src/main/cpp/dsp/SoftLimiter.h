#pragma once
// Lookahead peak limiter with soft-knee tanh safety stage. Ceiling defaults to -0.3 dBFS so
// multitrack summing and bass boost never clip harshly. Fixed-size buffers; no allocation in process().
#include <vector>

namespace tfx {

class SoftLimiter {
public:
    void prepare(int sampleRate, float ceilingDb = -0.3f, float lookaheadMs = 1.5f, float releaseMs = 80.f);
    void process(float* interleaved, int frames);
    void reset();
    int latencyFrames() const { return mLookahead; }

private:
    std::vector<float> mDelay;     // interleaved stereo delay line (lookahead)
    std::vector<float> mWinVal;    // monotonic deque for sliding-window minimum of required gain
    std::vector<long long> mWinIdx;
    int mHead = 0, mTail = 0, mWinCap = 0;
    long long mCounter = 0;
    int mDelayIdx = 0;
    int mLookahead = 64;
    float mCeiling = 0.966f;
    float mGain = 1.f;
    float mAttackStep = 0.f;
    float mReleaseCoef = 0.f;
};

}  // namespace tfx
