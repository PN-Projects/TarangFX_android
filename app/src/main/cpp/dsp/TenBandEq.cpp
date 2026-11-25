#include "dsp/TenBandEq.h"
#include <algorithm>
#include <cmath>

namespace tfx {

void TenBandEq::prepare(int sampleRate) {
    mRate = sampleRate;
    std::array<float, kBands> g = mGains;
    setGains(g.data(), true);
}

void TenBandEq::setGains(const float* gainsDb, bool immediate) {
    for (int b = 0; b < kBands; ++b) {
        const float g = std::clamp(gainsDb[b], -12.f, 12.f);
        if (!immediate && g == mGains[b]) continue;
        mGains[b] = g;
        // Bands at/above ~Nyquist (e.g. 16 kHz at 22.05 kHz) are skipped.
        BiquadCoeffs c;
        if (std::fabs(g) > 0.01f && kFrequencies[b] < mRate * 0.45f) {
            c = designBiquad(BiquadType::Peaking, mRate, kFrequencies[b], 1.414, g);
        }
        mFilters[b].setTarget(c, immediate);
    }
}

void TenBandEq::process(float* x, int frames) {
    for (auto& f : mFilters) f.process(x, frames);
}

void TenBandEq::reset() {
    for (auto& f : mFilters) f.reset();
}

}  // namespace tfx
