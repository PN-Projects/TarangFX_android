#pragma once
// Effect parameters + the ordered per-clip / master processing chain:
//   [Voice Enhance] -> 10-band EQ -> Bass Boost -> Reverb -> Volume
// (AI denoise and time/pitch run earlier, inside the clip renderer.)
#include <string>
#include "dsp/TenBandEq.h"
#include "dsp/BassBoost.h"
#include "dsp/StereoReverb.h"
#include "dsp/VoiceEnhancer.h"

namespace tfx {

struct DspParams {
    bool eqEnabled = false;
    float eqBands[TenBandEq::kBands] = {0};
    float bassAmount = 0.f;
    float bassFreq = 80.f;
    bool reverbEnabled = false;
    float reverbRoom = 0.5f;
    float reverbDamping = 0.5f;
    float reverbWet = 0.25f;
    float reverbPreDelayMs = 10.f;
    float volumeDb = 0.f;
    bool voiceEnhance = false;
    std::string denoiseMode = "off";  // off | studio | fast
    float denoiseStrength = 1.f;
    float speed = 1.f;
    float pitchSemitones = 0.f;
    bool preserveFormants = true;
    bool bypass = false;              // A/B: true => original signal
    float normalizeLufs = 0.f;        // 0 => off; e.g. -14 for EBU R128 streaming target

    bool hasTimePitch() const { return std::abs(speed - 1.f) > 1e-3f || std::abs(pitchSemitones) > 1e-3f; }
    bool hasReverb() const { return reverbEnabled && reverbWet > 0.0005f; }
};

class DspChain {
public:
    void prepare(int sampleRate);
    void setParams(const DspParams& p);
    void process(float* interleaved, int frames);
    void reset();
    const DspParams& params() const { return mParams; }

private:
    int mRate = 48000;
    DspParams mParams;
    VoiceEnhancer mVoice;
    TenBandEq mEq;
    BassBoost mBass;
    StereoReverb mReverb;
    float mGain = 1.f;
    float mTargetGain = 1.f;
};

}  // namespace tfx
