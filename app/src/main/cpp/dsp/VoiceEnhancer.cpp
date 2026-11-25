#include "dsp/VoiceEnhancer.h"
#include <algorithm>

namespace tfx {

void VoiceEnhancer::prepare(int sr) {
    const BiquadCoeffs hp = designBiquad(BiquadType::HighPass, sr, 80.0, 0.707, 0);
    mHp1.setTarget(hp, true);
    mHp2.setTarget(hp, true);
    mDeEsser.prepare(sr);
    mDeEsser.setParams(-30.f, 8.f);
    mMud.setTarget(designBiquad(BiquadType::Peaking, sr, 300.0, 1.0, -2.0), true);
    mPresence.setTarget(designBiquad(BiquadType::Peaking, sr, 3500.0, 0.9, 3.0), true);
    mAir.setTarget(designBiquad(BiquadType::HighShelf, sr, std::min(10000.0, sr * 0.42), 0.707, 2.0), true);
    mComp.prepare(sr);
    mComp.setParams(-20.f, 3.f, 8.f, 120.f, 6.f, 4.f);
    reset();
}

void VoiceEnhancer::process(float* x, int frames) {
    mHp1.process(x, frames);
    mHp2.process(x, frames);
    mDeEsser.process(x, frames);
    mMud.process(x, frames);
    mPresence.process(x, frames);
    mAir.process(x, frames);
    mComp.process(x, frames);
}

void VoiceEnhancer::reset() {
    mHp1.reset(); mHp2.reset();
    mDeEsser.reset();
    mMud.reset(); mPresence.reset(); mAir.reset();
    mComp.reset();
}

}  // namespace tfx
