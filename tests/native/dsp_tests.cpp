// Native unit tests + micro-benchmarks for the DSP core.
//
// Reference values come from published specs where one exists:
//   * EBU Tech 3341 (loudness meter conformance) and ITU-R BS.1770-4 for LoudnessMeter.
//   * RBJ Audio-EQ-Cookbook for biquad magnitude at the design frequency.
// Everything else checks invariants (limiter ceiling/latency, ring-buffer ordering, identity
// paths) or compares an optimised routine against a brute-force reference.
//
// Usage on device:  ./dsp_tests            (tests + benchmarks)
//                   ./dsp_tests --no-bench
#include "common/SpscRingBuffer.h"
#include "dsp/BassBoost.h"
#include "dsp/BiquadFilter.h"
#include "dsp/DspChain.h"
#include "dsp/Dynamics.h"
#include "dsp/SoftLimiter.h"
#include "dsp/StereoReverb.h"
#include "dsp/TenBandEq.h"
#include "dsp/VoiceEnhancer.h"

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>
#include <random>
#include <string>
#include <thread>
#include <vector>

using namespace tfx;

// ------------------------------------------------------------------------------ tiny harness
static int gPass = 0, gFail = 0;
static std::string gCurrent;

#define CHECK(cond)                                                                           \
    do {                                                                                      \
        if (cond) { ++gPass; }                                                                \
        else { ++gFail; std::printf("    FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); }    \
    } while (0)

#define CHECK_NEAR(actual, expected, tol)                                                     \
    do {                                                                                      \
        const double a_ = (actual), e_ = (expected);                                          \
        if (std::fabs(a_ - e_) <= (tol)) { ++gPass; }                                         \
        else { ++gFail; std::printf("    FAIL %s:%d  %s = %.6f, expected %.6f +/- %g\n",     \
                                    __FILE__, __LINE__, #actual, a_, e_, (double)(tol)); }    \
    } while (0)

static void runTest(const char* name, const std::function<void()>& fn) {
    const int failBefore = gFail;
    gCurrent = name;
    fn();
    std::printf("[%s] %s\n", gFail == failBefore ? "PASS" : "FAIL", name);
}

// ------------------------------------------------------------------------------ helpers
constexpr int kRate = 48000;
constexpr double kPi = 3.14159265358979323846;

static std::vector<float> stereoSine(double freq, double amp, int frames, int rate = kRate) {
    std::vector<float> v(static_cast<size_t>(frames) * 2);
    for (int i = 0; i < frames; ++i) {
        const float s = static_cast<float>(amp * std::sin(2 * kPi * freq * i / rate));
        v[2 * i] = v[2 * i + 1] = s;
    }
    return v;
}

static double rmsDb(const std::vector<float>& x, int fromFrame) {
    double s = 0;
    size_t n = 0;
    for (size_t i = static_cast<size_t>(fromFrame) * 2; i < x.size(); ++i) { s += double(x[i]) * x[i]; ++n; }
    return 10.0 * std::log10(std::max(s / std::max<size_t>(n, 1), 1e-30));
}

static double peakAbs(const std::vector<float>& x) {
    double p = 0;
    for (float v : x) p = std::max(p, std::fabs(double(v)));
    return p;
}

// ------------------------------------------------------------------------------ tests
static void testBiquadPeakingGain() {
    // RBJ peaking EQ: magnitude at f0 equals the design gain.
    for (double g : {-12.0, -6.0, 6.0, 12.0}) {
        StereoBiquad bq;
        bq.setTarget(designBiquad(BiquadType::Peaking, kRate, 1000, 1.414, g), true);
        auto x = stereoSine(1000, 0.25, kRate);
        const double inDb = rmsDb(x, kRate / 2);
        bq.process(x.data(), kRate);
        CHECK_NEAR(rmsDb(x, kRate / 2) - inDb, g, 0.05);
    }
    // Far from f0 the filter is transparent.
    StereoBiquad bq;
    bq.setTarget(designBiquad(BiquadType::Peaking, kRate, 1000, 1.414, 12), true);
    auto x = stereoSine(12000, 0.25, kRate);
    const double inDb = rmsDb(x, kRate / 2);
    bq.process(x.data(), kRate);
    CHECK_NEAR(rmsDb(x, kRate / 2) - inDb, 0.0, 0.3);
}

static void testBiquadHighPassCutoff() {
    // 2nd-order Butterworth HP (Q = 0.7071): -3.01 dB at the cutoff, ~-40 dB a decade below.
    StereoBiquad bq;
    bq.setTarget(designBiquad(BiquadType::HighPass, kRate, 1000, 0.70710678, 0), true);
    auto x = stereoSine(1000, 0.25, kRate);
    const double inDb = rmsDb(x, kRate / 2);
    bq.process(x.data(), kRate);
    CHECK_NEAR(rmsDb(x, kRate / 2) - inDb, -3.01, 0.05);
    StereoBiquad bq2;
    bq2.setTarget(designBiquad(BiquadType::HighPass, kRate, 1000, 0.70710678, 0), true);
    auto y = stereoSine(100, 0.25, kRate);
    bq2.process(y.data(), kRate);
    CHECK_NEAR(rmsDb(y, kRate / 2) - inDb, -40.0, 0.5);
}

static void testBiquadRampIsSmooth() {
    // Switching from flat to +12 dB must ramp: no sample-to-sample jump bigger than the signal
    // itself could produce (zipper-free), and the target is reached after kRampSamples.
    StereoBiquad bq;
    auto x = stereoSine(1000, 0.25, 4096);
    bq.process(x.data(), 1024);
    bq.setTarget(designBiquad(BiquadType::Peaking, kRate, 1000, 1.414, 12));
    bq.process(x.data() + 2048, 3072);
    double maxStep = 0;
    for (int i = 1; i < 4096; ++i) maxStep = std::max(maxStep, std::fabs(double(x[2 * i]) - x[2 * (i - 1)]));
    // A 1 kHz sine at amplitude A has max slope 2*pi*f/fs*A per sample; +12 dB => 4x amplitude.
    CHECK(maxStep < 2 * kPi * 1000.0 / kRate * 0.25 * 4.0 * 1.2);
}

static void testFlatEqIsIdentity() {
    TenBandEq eq;
    eq.prepare(kRate);
    float zeros[TenBandEq::kBands] = {0};
    eq.setGains(zeros, true);
    auto x = stereoSine(440, 0.5, 8192);
    auto ref = x;
    eq.process(x.data(), 8192);
    CHECK(std::memcmp(x.data(), ref.data(), x.size() * sizeof(float)) == 0);
}

static void testEqBandGain() {
    TenBandEq eq;
    eq.prepare(kRate);
    float g[TenBandEq::kBands] = {0};
    g[5] = 9.f;  // 1 kHz band
    eq.setGains(g, true);
    auto x = stereoSine(1000, 0.1, kRate);
    const double inDb = rmsDb(x, kRate / 2);
    eq.process(x.data(), kRate);
    CHECK_NEAR(rmsDb(x, kRate / 2) - inDb, 9.0, 0.3);
}

// EBU Tech 3341, Table 1: stereo 1 kHz sine at -23 dBFS (and -33 dBFS) reads -23.0 / -33.0 LUFS
// (tolerance +/-0.1 LU).
static void testLoudnessEbu3341() {
    for (double level : {-23.0, -33.0}) {
        LoudnessMeter m;
        m.prepare(kRate);
        auto x = stereoSine(1000, std::pow(10.0, level / 20.0), 20 * kRate);
        m.process(x.data(), 20 * kRate);
        CHECK_NEAR(m.integratedLufs(), level, 0.1);
    }
    // Test 3: -36 / -23 / -36 dBFS for 10 / 60 / 10 s -> -23.0 LUFS (relative gate).
    LoudnessMeter m;
    m.prepare(kRate);
    for (auto [lvl, secs] : {std::pair{-36.0, 10}, {-23.0, 60}, {-36.0, 10}}) {
        auto x = stereoSine(1000, std::pow(10.0, lvl / 20.0), secs * kRate);
        m.process(x.data(), secs * kRate);
    }
    CHECK_NEAR(m.integratedLufs(), -23.0, 0.1);
    // Test 4: absolute gate. -72/-36/-23/-36/-72 dBFS for 10/10/60/10/10 s -> -23.0 LUFS.
    LoudnessMeter m4;
    m4.prepare(kRate);
    for (auto [lvl, secs] : {std::pair{-72.0, 10}, {-36.0, 10}, {-23.0, 60}, {-36.0, 10}, {-72.0, 10}}) {
        auto x = stereoSine(1000, std::pow(10.0, lvl / 20.0), secs * kRate);
        m4.process(x.data(), secs * kRate);
    }
    CHECK_NEAR(m4.integratedLufs(), -23.0, 0.1);
}

static void testLoudnessMonoMode() {
    // BS.1770: a single channel carrying a -23 dBFS 1 kHz sine reads -26.0 LUFS (one channel, G=1).
    LoudnessMeter m;
    m.prepare(kRate);
    m.setMonoOutput(true);
    auto x = stereoSine(1000, std::pow(10.0, -23.0 / 20.0), 20 * kRate);
    m.process(x.data(), 20 * kRate);
    CHECK_NEAR(m.integratedLufs(), -26.0, 0.1);
    // Silence is fully gated.
    LoudnessMeter s;
    s.prepare(kRate);
    std::vector<float> z(2 * kRate * 5, 0.f);
    s.process(z.data(), kRate * 5);
    CHECK_NEAR(s.integratedLufs(), -70.0, 1e-9);
}

static void testLimiterCeilingAndLatency() {
    SoftLimiter lim;
    lim.prepare(kRate, -0.3f);
    const double ceiling = std::pow(10.0, -0.3 / 20.0);
    std::mt19937 rng(42);
    std::normal_distribution<float> nd(0.f, 1.2f);  // heavy overs, peaks well above +12 dB
    std::vector<float> x(2 * kRate * 2);
    for (auto& v : x) v = nd(rng);
    for (int i = 0; i < 2 * kRate; i += 997) { x[2 * i] = 8.f; x[2 * i + 1] = -8.f; }  // +18 dBFS spikes
    lim.process(x.data(), 2 * kRate);
    CHECK(peakAbs(x) <= ceiling + 1e-6);

    // Quiet material passes untouched, delayed by exactly latencyFrames().
    SoftLimiter l2;
    l2.prepare(kRate, -0.3f);
    const int lat = l2.latencyFrames();
    auto q = stereoSine(440, 0.1, 4096);
    auto ref = q;
    l2.process(q.data(), 4096);
    double maxErr = 0;
    for (int i = lat; i < 4096; ++i) maxErr = std::max(maxErr, std::fabs(double(q[2 * i]) - ref[2 * (i - lat)]));
    CHECK(maxErr < 1e-6);
    CHECK(lat == static_cast<int>(1.5e-3 * kRate));
}

// The limiter's look-ahead uses a monotonic deque (O(1) amortised sliding-window minimum).
// Re-implement the same limiter with an O(n*L) brute-force window scan and require identical
// output: proves the deque returns exactly the window minimum at every sample.
static float refSafetyClip(float x, float ceiling) {
    const float knee = ceiling * 0.9f;
    const float a = std::fabs(x);
    if (a <= knee) return x;
    const float range = ceiling - knee;
    const float y = knee + range * std::tanh((a - knee) / range);
    return x < 0 ? -y : y;
}

static void testLimiterWindowMinMatchesBruteForce() {
    SoftLimiter lim;
    lim.prepare(kRate, -0.3f, 1.5f, 80.f);
    const int lat = lim.latencyFrames();
    const float ceil = std::pow(10.f, -0.3f / 20.f);
    const float attackStep = 1.f / static_cast<float>(lat);
    const float releaseCoef = 1.f - std::exp(-1.f / (80.f * 0.001f * kRate));
    std::mt19937 rng(7);
    std::uniform_real_distribution<float> ud(-3.f, 3.f);
    const int n = 20000;
    std::vector<float> x(2 * n);
    for (int i = 0; i < n; ++i) {  // bursts of overs separated by quiet passages
        const float env = (i / 1500) % 2 ? 0.2f : 1.f;
        x[2 * i] = ud(rng) * env;
        x[2 * i + 1] = ud(rng) * env;
    }
    const auto in = x;
    lim.process(x.data(), n);

    std::vector<float> req(n);
    for (int i = 0; i < n; ++i) {
        const float p = std::max(std::fabs(in[2 * i]), std::fabs(in[2 * i + 1]));
        req[i] = p > ceil ? ceil / p : 1.f;
    }
    float gain = 1.f;
    double maxDiff = 0;
    for (int i = 0; i < n; ++i) {
        float target = 1.f;
        for (int k = std::max(0, i - lat + 1); k <= i; ++k) target = std::min(target, req[k]);
        if (target < gain) gain = std::max(target, gain - attackStep);
        else gain += (target - gain) * releaseCoef;
        const float dl = i >= lat ? in[2 * (i - lat)] : 0.f;
        const float dr = i >= lat ? in[2 * (i - lat) + 1] : 0.f;
        maxDiff = std::max(maxDiff, std::fabs(double(refSafetyClip(dl * gain, ceil)) - x[2 * i]));
        maxDiff = std::max(maxDiff, std::fabs(double(refSafetyClip(dr * gain, ceil)) - x[2 * i + 1]));
    }
    CHECK(maxDiff < 1e-6);
}

static void testCompressorStaticCurve() {
    // Constant 0.5 (-6.02 dBFS) input; threshold -20, ratio 3, knee 6, makeup 4:
    // GR = (1/3 - 1) * 13.98 = -9.32 dB  ->  out = -6.02 - 9.32 + 4 = -11.34 dBFS.
    Compressor c;
    c.prepare(kRate);
    c.setParams(-20.f, 3.f, 8.f, 120.f, 6.f, 4.f);
    std::vector<float> x(2 * kRate, 0.5f);
    c.process(x.data(), kRate);
    CHECK_NEAR(20 * std::log10(std::fabs(x[2 * (kRate - 1)])), -11.34, 0.05);
    // Below threshold - knee/2: only makeup gain.
    Compressor c2;
    c2.prepare(kRate);
    c2.setParams(-20.f, 3.f, 8.f, 120.f, 6.f, 4.f);
    std::vector<float> y(2 * kRate, 0.01f);  // -40 dBFS
    c2.process(y.data(), kRate);
    CHECK_NEAR(20 * std::log10(std::fabs(y[2 * (kRate - 1)])), -36.0, 0.05);
}

static void testDeEsserBandSelective() {
    DeEsser d;
    d.prepare(kRate);
    d.setParams(-30.f, 8.f);
    auto lo = stereoSine(200, 0.5, kRate);
    const double loIn = rmsDb(lo, kRate / 2);
    d.process(lo.data(), kRate);
    CHECK_NEAR(rmsDb(lo, kRate / 2) - loIn, 0.0, 0.5);  // voice body untouched

    DeEsser d2;
    d2.prepare(kRate);
    d2.setParams(-30.f, 8.f);
    auto hi = stereoSine(7000, 0.5, kRate);
    const double hiIn = rmsDb(hi, kRate / 2);
    d2.process(hi.data(), kRate);
    CHECK(rmsDb(hi, kRate / 2) - hiIn < -4.0);  // loud sibilance attenuated
}

static void testReverbDryWet() {
    StereoReverb r;
    r.prepare(kRate);
    r.setParams(0.5f, 0.5f, 0.f, 10.f);
    auto x = stereoSine(440, 0.5, 4096);
    auto ref = x;
    r.process(x.data(), 4096);
    CHECK(std::memcmp(x.data(), ref.data(), x.size() * sizeof(float)) == 0);  // wet 0 = bypass

    // An impulse produces a decaying, finite tail.
    StereoReverb r2;
    r2.prepare(kRate);
    r2.setParams(0.8f, 0.3f, 0.5f, 0.f);
    std::vector<float> imp(2 * kRate * 3, 0.f);
    imp[0] = imp[1] = 1.f;
    r2.process(imp.data(), kRate * 3);
    double early = 0, late = 0;
    for (int i = kRate / 10; i < kRate / 2; ++i) early += imp[2 * i] * imp[2 * i];
    for (int i = 5 * kRate / 2; i < 3 * kRate; ++i) late += imp[2 * i] * imp[2 * i];
    bool finite = true;
    for (float v : imp) finite &= std::isfinite(v);
    CHECK(finite);
    CHECK(early > 0 && late < early * 1e-2);
}

static void testBassBoostLiftsLows() {
    BassBoost b;
    b.prepare(kRate);
    b.setParams(0.6f, 80.f);
    auto lo = stereoSine(60, 0.05, kRate);
    const double loIn = rmsDb(lo, kRate / 2);
    b.process(lo.data(), kRate);
    BassBoost b2;
    b2.prepare(kRate);
    b2.setParams(0.6f, 80.f);
    auto hi = stereoSine(2000, 0.05, kRate);
    const double hiIn = rmsDb(hi, kRate / 2);
    b2.process(hi.data(), kRate);
    CHECK(rmsDb(lo, kRate / 2) - loIn > 6.0);
    CHECK_NEAR(rmsDb(hi, kRate / 2) - hiIn, 0.0, 0.5);
}

static void testDspChainBypassAndVolume() {
    DspChain c;
    c.prepare(kRate);
    DspParams p;
    p.eqEnabled = true;
    p.eqBands[3] = 6.f;
    p.reverbEnabled = true;
    p.bypass = true;
    c.setParams(p);
    auto x = stereoSine(440, 0.3, 4096);
    auto ref = x;
    c.process(x.data(), 4096);
    CHECK(std::memcmp(x.data(), ref.data(), x.size() * sizeof(float)) == 0);

    DspChain v;
    v.prepare(kRate);
    DspParams pv;
    pv.volumeDb = 6.f;
    v.setParams(pv);
    v.prepare(kRate);  // start at target gain
    auto y = stereoSine(440, 0.1, 4096);
    const double inDb = rmsDb(y, 0);
    v.process(y.data(), 4096);
    CHECK_NEAR(rmsDb(y, 0) - inDb, 6.0, 0.01);
}

static void testSpscRingBuffer() {
    SpscRingBuffer<int> rb(1000);
    CHECK(rb.capacity() == 1024);
    // Wrap-around integrity.
    std::vector<int> in(700), out(700);
    int next = 0, expect = 0;
    bool ordered = true;
    for (int round = 0; round < 50; ++round) {
        for (auto& v : in) v = next++;
        CHECK(rb.write(in.data(), in.size()) == in.size());
        CHECK(rb.read(out.data(), out.size()) == out.size());
        for (int v : out) ordered &= (v == expect++);
    }
    CHECK(ordered);
    // Overfill is truncated, never overwrites unread data.
    std::vector<int> big(2000, 1);
    CHECK(rb.write(big.data(), big.size()) == 1024);
    CHECK(rb.availableToWrite() == 0);
    rb.discardAll();
    CHECK(rb.availableToRead() == 0);

    // Concurrent producer/consumer: 2M items arrive complete and in order.
    SpscRingBuffer<uint32_t> q(4096);
    constexpr uint32_t kN = 2'000'000;
    std::thread prod([&] {
        uint32_t buf[256];
        uint32_t v = 0;
        while (v < kN) {
            const uint32_t n = std::min<uint32_t>(256, kN - v);
            for (uint32_t i = 0; i < n; ++i) buf[i] = v + i;
            size_t done = 0;
            while (done < n) done += q.write(buf + done, n - done);
            v += n;
        }
    });
    uint32_t got = 0;
    bool inOrder = true;
    uint32_t rbuf[300];
    while (got < kN) {
        const size_t n = q.read(rbuf, 300);
        for (size_t i = 0; i < n; ++i) inOrder &= (rbuf[i] == got + i);
        got += static_cast<uint32_t>(n);
    }
    prod.join();
    CHECK(inOrder);
    CHECK(got == kN);
}

// ------------------------------------------------------------------------------ benchmarks
// Reports nanoseconds per stereo frame and the real-time factor at 48 kHz
// (how many times faster than playback one core runs the module).
static void bench(const char* name, const std::function<void(float*, int)>& fn) {
    constexpr int kBlock = 4096;
    constexpr int kBlocks = 600;  // ~51 s of audio
    std::vector<float> x(2 * kBlock);
    std::mt19937 rng(1);
    std::uniform_real_distribution<float> ud(-0.5f, 0.5f);
    for (auto& v : x) v = ud(rng);
    const auto src = x;
    fn(x.data(), kBlock);  // warm-up
    const auto t0 = std::chrono::steady_clock::now();
    for (int b = 0; b < kBlocks; ++b) {
        std::memcpy(x.data(), src.data(), x.size() * sizeof(float));
        fn(x.data(), kBlock);
    }
    const double ns = std::chrono::duration<double, std::nano>(std::chrono::steady_clock::now() - t0).count();
    const double nsPerFrame = ns / (double(kBlock) * kBlocks);
    std::printf("BENCH %-26s %8.2f ns/frame   %8.1fx real-time\n", name, nsPerFrame, 1e9 / kRate / nsPerFrame);
}

static void runBenchmarks() {
    std::printf("\n-- micro-benchmarks (stereo, 48 kHz, 4096-frame blocks) --\n");
    {
        StereoBiquad bq;
        bq.setTarget(designBiquad(BiquadType::Peaking, kRate, 1000, 1.4, 6), true);
        bench("StereoBiquad x1", [&](float* x, int n) { bq.process(x, n); });
    }
    {
        TenBandEq eq;
        eq.prepare(kRate);
        float g[TenBandEq::kBands] = {3, -2, 4, 1, -3, 2, 5, -4, 2, 3};
        eq.setGains(g, true);
        bench("TenBandEq (10 bands)", [&](float* x, int n) { eq.process(x, n); });
    }
    {
        BassBoost b;
        b.prepare(kRate);
        b.setParams(0.6f, 80.f);
        bench("BassBoost", [&](float* x, int n) { b.process(x, n); });
    }
    {
        StereoReverb r;
        r.prepare(kRate);
        r.setParams(0.6f, 0.4f, 0.3f, 10.f);
        bench("StereoReverb", [&](float* x, int n) { r.process(x, n); });
    }
    {
        Compressor c;
        c.prepare(kRate);
        c.setParams(-20.f, 3.f, 8.f, 120.f, 6.f, 4.f);
        bench("Compressor", [&](float* x, int n) { c.process(x, n); });
    }
    {
        DeEsser d;
        d.prepare(kRate);
        d.setParams(-30.f, 8.f);
        bench("DeEsser", [&](float* x, int n) { d.process(x, n); });
    }
    {
        VoiceEnhancer v;
        v.prepare(kRate);
        bench("VoiceEnhancer (full)", [&](float* x, int n) { v.process(x, n); });
    }
    {
        SoftLimiter l;
        l.prepare(kRate);
        bench("SoftLimiter", [&](float* x, int n) { l.process(x, n); });
    }
    {
        LoudnessMeter m;
        m.prepare(kRate);
        bench("LoudnessMeter", [&](float* x, int n) { m.process(x, n); });
    }
    {
        DspChain c;
        c.prepare(kRate);
        DspParams p;
        p.eqEnabled = true;
        for (int i = 0; i < TenBandEq::kBands; ++i) p.eqBands[i] = (i % 2 ? 3.f : -2.f);
        p.bassAmount = 0.5f;
        p.reverbEnabled = true;
        p.reverbWet = 0.3f;
        p.voiceEnhance = true;
        p.volumeDb = -1.f;
        c.setParams(p);
        bench("DspChain (everything on)", [&](float* x, int n) { c.process(x, n); });
    }
}

int main(int argc, char** argv) {
    const bool doBench = !(argc > 1 && std::strcmp(argv[1], "--no-bench") == 0);
    std::printf("== TarangFX native DSP tests ==\n");
    runTest("Biquad peaking gain at f0 (RBJ cookbook)", testBiquadPeakingGain);
    runTest("Biquad Butterworth high-pass -3 dB at cutoff", testBiquadHighPassCutoff);
    runTest("Biquad coefficient ramp is zipper-free", testBiquadRampIsSmooth);
    runTest("Flat 10-band EQ is bit-exact identity", testFlatEqIsIdentity);
    runTest("10-band EQ band gain", testEqBandGain);
    runTest("Loudness meter: EBU Tech 3341 cases 1-4", testLoudnessEbu3341);
    runTest("Loudness meter: mono channel + silence gate", testLoudnessMonoMode);
    runTest("Limiter: ceiling never exceeded, exact latency", testLimiterCeilingAndLatency);
    runTest("Limiter: sliding-window min vs brute force", testLimiterWindowMinMatchesBruteForce);
    runTest("Compressor static curve", testCompressorStaticCurve);
    runTest("De-esser is band-selective", testDeEsserBandSelective);
    runTest("Reverb: wet 0 bypass, stable decaying tail", testReverbDryWet);
    runTest("Bass boost lifts lows, leaves mids", testBassBoostLiftsLows);
    runTest("DspChain bypass identity + volume", testDspChainBypassAndVolume);
    runTest("SPSC ring buffer: wrap, overfill, 2M threaded", testSpscRingBuffer);
    std::printf("\nRESULT: %d checks passed, %d failed\n", gPass, gFail);
    if (doBench) runBenchmarks();
    return gFail == 0 ? 0 : 1;
}
