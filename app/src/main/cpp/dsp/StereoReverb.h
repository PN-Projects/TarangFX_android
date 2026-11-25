#pragma once
// Freeverb-style stereo reverb (8 damped combs + 4 allpasses per channel) with pre-delay.
// All buffers are allocated in prepare(); process() never allocates.
#include <vector>

namespace tfx {

class StereoReverb {
public:
    void prepare(int sampleRate);
    void setParams(float roomSize, float damping, float wet, float preDelayMs);
    void process(float* interleaved, int frames);
    void reset();
    bool enabled() const { return mWet > 0.0005f; }

private:
    struct Comb {
        std::vector<float> buf;
        int idx = 0;
        float store = 0.f;
        inline float process(float in, float feedback, float damp1, float damp2) {
            float out = buf[idx];
            store = out * damp2 + store * damp1;
            buf[idx] = in + store * feedback;
            if (++idx >= static_cast<int>(buf.size())) idx = 0;
            return out;
        }
    };
    struct Allpass {
        std::vector<float> buf;
        int idx = 0;
        inline float process(float in) {
            float bufout = buf[idx];
            float out = -in + bufout;
            buf[idx] = in + bufout * 0.5f;
            if (++idx >= static_cast<int>(buf.size())) idx = 0;
            return out;
        }
    };
    static constexpr int kCombs = 8;
    static constexpr int kAllpasses = 4;
    Comb mCombL[kCombs], mCombR[kCombs];
    Allpass mApL[kAllpasses], mApR[kAllpasses];
    std::vector<float> mPreDelay;  // interleaved stereo
    int mPreIdx = 0;
    int mPreLen = 0;
    int mRate = 48000;
    float mFeedback = 0.84f, mDamp1 = 0.2f, mDamp2 = 0.8f, mWet = 0.f, mDry = 1.f;
};

}  // namespace tfx
