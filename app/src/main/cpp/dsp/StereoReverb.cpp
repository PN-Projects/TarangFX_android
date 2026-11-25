#include "dsp/StereoReverb.h"
#include <algorithm>
#include <cmath>

namespace tfx {

static constexpr int kCombTuning[8] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
static constexpr int kApTuning[4] = {556, 441, 341, 225};
static constexpr int kStereoSpread = 23;
static constexpr float kMaxPreDelayMs = 250.f;

void StereoReverb::prepare(int sampleRate) {
    mRate = sampleRate;
    const double scale = sampleRate / 44100.0;
    for (int i = 0; i < kCombs; ++i) {
        mCombL[i].buf.assign(std::max(1, int(kCombTuning[i] * scale)), 0.f);
        mCombR[i].buf.assign(std::max(1, int((kCombTuning[i] + kStereoSpread) * scale)), 0.f);
        mCombL[i].idx = mCombR[i].idx = 0;
    }
    for (int i = 0; i < kAllpasses; ++i) {
        mApL[i].buf.assign(std::max(1, int(kApTuning[i] * scale)), 0.f);
        mApR[i].buf.assign(std::max(1, int((kApTuning[i] + kStereoSpread) * scale)), 0.f);
        mApL[i].idx = mApR[i].idx = 0;
    }
    mPreDelay.assign(static_cast<size_t>(kMaxPreDelayMs / 1000.f * sampleRate + 2) * 2, 0.f);
    mPreIdx = 0;
}

void StereoReverb::setParams(float roomSize, float damping, float wet, float preDelayMs) {
    roomSize = std::clamp(roomSize, 0.f, 1.f);
    damping = std::clamp(damping, 0.f, 1.f);
    mWet = std::clamp(wet, 0.f, 1.f);
    mDry = 1.f - 0.5f * mWet;
    mFeedback = roomSize * 0.28f + 0.7f;
    mDamp1 = damping * 0.4f;
    mDamp2 = 1.f - mDamp1;
    const int maxLen = static_cast<int>(mPreDelay.size() / 2) - 1;
    mPreLen = std::clamp(static_cast<int>(preDelayMs / 1000.f * mRate), 0, std::max(0, maxLen));
}

void StereoReverb::process(float* x, int frames) {
    if (!enabled() || mPreDelay.empty()) return;
    const int preSize = static_cast<int>(mPreDelay.size() / 2);
    const float wetGain = mWet;
    constexpr float kInputGain = 0.015f;
    for (int i = 0; i < frames; ++i) {
        const float inL = x[2 * i], inR = x[2 * i + 1];
        mPreDelay[2 * mPreIdx] = inL;
        mPreDelay[2 * mPreIdx + 1] = inR;
        int readIdx = mPreIdx - mPreLen;
        if (readIdx < 0) readIdx += preSize;
        const float dl = mPreDelay[2 * readIdx], dr = mPreDelay[2 * readIdx + 1];
        if (++mPreIdx >= preSize) mPreIdx = 0;

        const float input = (dl + dr) * kInputGain;
        float outL = 0.f, outR = 0.f;
        for (int c = 0; c < kCombs; ++c) {
            outL += mCombL[c].process(input, mFeedback, mDamp1, mDamp2);
            outR += mCombR[c].process(input, mFeedback, mDamp1, mDamp2);
        }
        for (int a = 0; a < kAllpasses; ++a) {
            outL = mApL[a].process(outL);
            outR = mApR[a].process(outR);
        }
        x[2 * i] = inL * mDry + outL * wetGain;
        x[2 * i + 1] = inR * mDry + outR * wetGain;
    }
}

void StereoReverb::reset() {
    for (int i = 0; i < kCombs; ++i) {
        std::fill(mCombL[i].buf.begin(), mCombL[i].buf.end(), 0.f);
        std::fill(mCombR[i].buf.begin(), mCombR[i].buf.end(), 0.f);
        mCombL[i].store = mCombR[i].store = 0.f;
    }
    for (int i = 0; i < kAllpasses; ++i) {
        std::fill(mApL[i].buf.begin(), mApL[i].buf.end(), 0.f);
        std::fill(mApR[i].buf.begin(), mApR[i].buf.end(), 0.f);
    }
    std::fill(mPreDelay.begin(), mPreDelay.end(), 0.f);
}

}  // namespace tfx
