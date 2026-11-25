#pragma once
#include "dsp/BiquadFilter.h"
#include <array>

namespace tfx {

// 10-band graphic EQ built from cascaded RBJ peaking filters (Q = 1.414), +/-12 dB per band.
class TenBandEq {
public:
    static constexpr int kBands = 10;
    static constexpr float kFrequencies[kBands] = {31.f, 62.f, 125.f, 250.f, 500.f,
                                                   1000.f, 2000.f, 4000.f, 8000.f, 16000.f};
    void prepare(int sampleRate);
    void setGains(const float* gainsDb, bool immediate = false);
    void process(float* interleaved, int frames);
    void reset();

private:
    int mRate = 48000;
    std::array<float, kBands> mGains{};
    std::array<StereoBiquad, kBands> mFilters;
};

}  // namespace tfx
