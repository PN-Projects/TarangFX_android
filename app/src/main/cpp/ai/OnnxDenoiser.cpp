#include "ai/OnnxDenoiser.h"
#include "common/Log.h"

#include <onnxruntime_cxx_api.h>

#include <algorithm>
#include <cmath>
#include <complex>
#include <cstring>
#include <map>
#include <mutex>
#include <sys/stat.h>

extern "C" {
#include <libswresample/swresample.h>
#include <libavutil/channel_layout.h>
#include <libavutil/opt.h>
}

namespace tfx {

// ------------------------------------------------------------------------------------------
// Shared ORT environment + one session per model file (Session::Run is thread-safe; all
// recurrent state lives in each denoiser instance, so sessions can be shared across clips).
// ------------------------------------------------------------------------------------------
static Ort::Env& ortEnv() {
    static Ort::Env env(ORT_LOGGING_LEVEL_WARNING, "TarangFX");
    return env;
}

static std::shared_ptr<Ort::Session> sessionFor(const std::string& path) {
    static std::mutex m;
    static std::map<std::string, std::weak_ptr<Ort::Session>> cache;
    std::lock_guard<std::mutex> lk(m);
    if (auto s = cache[path].lock()) return s;
    try {
        Ort::SessionOptions opts;
        opts.SetIntraOpNumThreads(2);
        opts.SetInterOpNumThreads(1);
        // The DFN3 graph is already ORT-optimised (contains com.microsoft FusedConv).
        opts.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_EXTENDED);
        auto s = std::make_shared<Ort::Session>(ortEnv(), path.c_str(), opts);
        cache[path] = s;
        LOGI("ONNX model loaded: %s", path.c_str());
        return s;
    } catch (const Ort::Exception& e) {
        LOGE("Cannot load ONNX model %s: %s", path.c_str(), e.what());
        return nullptr;
    }
}

static bool fileExists(const std::string& p) {
    struct stat st {};
    return stat(p.c_str(), &st) == 0 && st.st_size > 0;
}

// ------------------------------------------------------------------------------------------
// Generic stateful runner: input[0] = audio-ish tensor, inputs[1..] = recurrent states;
// output[0] = enhanced, outputs[1..] = new states in the same order (verified for both models).
// ------------------------------------------------------------------------------------------
class OnnxModelRunner {
public:
    virtual ~OnnxModelRunner() = default;
    virtual int modelRate() const = 0;
    virtual int hop() const = 0;
    virtual int delaySamples() const = 0;  // algorithmic delay at model rate
    // Consumes exactly hop() samples, produces exactly hop() samples.
    virtual bool processHop(const float* in, float* out) = 0;
    virtual void reset() = 0;

protected:
    bool setup(const std::string& path) {
        mSession = sessionFor(path);
        if (!mSession) return false;
        Ort::AllocatorWithDefaultOptions alloc;
        const size_t nIn = mSession->GetInputCount();
        const size_t nOut = mSession->GetOutputCount();
        if (nIn < 1 || nOut != nIn) {
            LOGE("Unexpected ONNX I/O count %zu/%zu", nIn, nOut);
            return false;
        }
        for (size_t i = 0; i < nIn; ++i) {
            mInNames.push_back(mSession->GetInputNameAllocated(i, alloc).get());
            auto shape = mSession->GetInputTypeInfo(i).GetTensorTypeAndShapeInfo().GetShape();
            for (auto& d : shape) if (d < 0) d = 1;
            mInShapes.push_back(shape);
            size_t count = 1;
            for (auto d : shape) count *= static_cast<size_t>(d);
            mBuffers.emplace_back(count, 0.f);
        }
        for (size_t i = 0; i < nOut; ++i) mOutNames.push_back(mSession->GetOutputNameAllocated(i, alloc).get());
        for (auto& s : mInNames) mInPtrs.push_back(s.c_str());
        for (auto& s : mOutNames) mOutPtrs.push_back(s.c_str());
        return true;
    }

    std::vector<float>& state(const char* name) {
        for (size_t i = 0; i < mInNames.size(); ++i) if (mInNames[i] == name) return mBuffers[i];
        static std::vector<float> none;
        return none;
    }

    // Runs one step with mBuffers[0] as input; copies output 0 to `out0` and states back.
    bool run(std::vector<float>& out0) {
        try {
            Ort::MemoryInfo mem = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
            std::vector<Ort::Value> inputs;
            inputs.reserve(mBuffers.size());
            for (size_t i = 0; i < mBuffers.size(); ++i) {
                inputs.push_back(Ort::Value::CreateTensor<float>(mem, mBuffers[i].data(), mBuffers[i].size(),
                                                                 mInShapes[i].data(), mInShapes[i].size()));
            }
            auto outputs = mSession->Run(Ort::RunOptions{nullptr}, mInPtrs.data(), inputs.data(), inputs.size(),
                                         mOutPtrs.data(), mOutPtrs.size());
            const float* o0 = outputs[0].GetTensorData<float>();
            out0.assign(o0, o0 + outputs[0].GetTensorTypeAndShapeInfo().GetElementCount());
            for (size_t i = 1; i < outputs.size(); ++i) {
                const size_t n = outputs[i].GetTensorTypeAndShapeInfo().GetElementCount();
                if (n != mBuffers[i].size()) return false;
                std::memcpy(mBuffers[i].data(), outputs[i].GetTensorData<float>(), n * sizeof(float));
            }
            return true;
        } catch (const Ort::Exception& e) {
            LOGE("ONNX run failed: %s", e.what());
            return false;
        }
    }

    void zeroStates() {
        for (size_t i = 1; i < mBuffers.size(); ++i) std::fill(mBuffers[i].begin(), mBuffers[i].end(), 0.f);
    }

    std::shared_ptr<Ort::Session> mSession;
    std::vector<std::string> mInNames, mOutNames;
    std::vector<const char*> mInPtrs, mOutPtrs;
    std::vector<std::vector<int64_t>> mInShapes;
    std::vector<std::vector<float>> mBuffers;
};

// ---- DeepFilterNet3 torchDF streaming graph ----------------------------------------------
class Dfn3Runner : public OnnxModelRunner {
public:
    bool init(const std::string& path) {
        if (!setup(path) || mBuffers[0].size() != 512) {
            LOGE("dfn3_stream.onnx: unexpected input layout");
            return false;
        }
        reset();
        return true;
    }
    int modelRate() const override { return 48000; }
    int hop() const override { return 512; }
    int delaySamples() const override { return 1536; }
    void reset() override {
        zeroStates();
        auto& erb = state("erb_norm_state");          // linspace(-60, -90, 32)
        for (size_t i = 0; i < erb.size(); ++i) erb[i] = -60.f - 30.f * i / std::max<size_t>(1, erb.size() - 1);
        auto& unit = state("band_unit_norm_state");   // linspace(0.001, 0.0001, 96)
        for (size_t i = 0; i < unit.size(); ++i) unit[i] = 0.001f - 0.0009f * i / std::max<size_t>(1, unit.size() - 1);
    }
    bool processHop(const float* in, float* out) override {
        std::memcpy(mBuffers[0].data(), in, 512 * sizeof(float));
        if (!run(mOut) || mOut.size() < 512) return false;
        std::memcpy(out, mOut.data(), 512 * sizeof(float));
        return true;
    }

private:
    std::vector<float> mOut;
};

// ---- GTCRN with our own STFT (n_fft 512, hop 256, periodic sqrt-Hann) ---------------------
static void fft(std::vector<std::complex<float>>& a, bool inverse) {
    const size_t n = a.size();
    for (size_t i = 1, j = 0; i < n; ++i) {
        size_t bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(a[i], a[j]);
    }
    for (size_t len = 2; len <= n; len <<= 1) {
        const float ang = 2.f * static_cast<float>(M_PI) / len * (inverse ? 1.f : -1.f);
        const std::complex<float> wl(std::cos(ang), std::sin(ang));
        for (size_t i = 0; i < n; i += len) {
            std::complex<float> w(1.f, 0.f);
            for (size_t k = 0; k < len / 2; ++k) {
                const auto u = a[i + k], v = a[i + k + len / 2] * w;
                a[i + k] = u + v;
                a[i + k + len / 2] = u - v;
                w *= wl;
            }
        }
    }
}

class GtcrnRunner : public OnnxModelRunner {
public:
    static constexpr int kN = 512, kHop = 256, kBins = 257;
    bool init(const std::string& path) {
        if (!setup(path) || mBuffers[0].size() != kBins * 2) {
            LOGE("gtcrn_simple.onnx: unexpected input layout");
            return false;
        }
        mWin.resize(kN);
        for (int n = 0; n < kN; ++n) mWin[n] = std::sqrt(0.5f - 0.5f * std::cos(2.f * static_cast<float>(M_PI) * n / kN));
        mFrame.assign(kN, 0.f);
        mOla.assign(kN, 0.f);
        mSpec.resize(kN);
        reset();
        return true;
    }
    int modelRate() const override { return 16000; }
    int hop() const override { return kHop; }
    int delaySamples() const override { return kHop; }
    void reset() override {
        zeroStates();
        std::fill(mFrame.begin(), mFrame.end(), 0.f);
        std::fill(mOla.begin(), mOla.end(), 0.f);
    }
    bool processHop(const float* in, float* out) override {
        // Slide analysis buffer by one hop.
        std::memmove(mFrame.data(), mFrame.data() + kHop, (kN - kHop) * sizeof(float));
        std::memcpy(mFrame.data() + kN - kHop, in, kHop * sizeof(float));
        for (int n = 0; n < kN; ++n) mSpec[n] = {mFrame[n] * mWin[n], 0.f};
        fft(mSpec, false);
        // Layout [1, 257, 1, 2] -> flat index 2*f + c
        for (int f = 0; f < kBins; ++f) {
            mBuffers[0][2 * f] = mSpec[f].real();
            mBuffers[0][2 * f + 1] = mSpec[f].imag();
        }
        if (!run(mOut) || mOut.size() < static_cast<size_t>(kBins * 2)) return false;
        for (int f = 0; f < kBins; ++f) mSpec[f] = {mOut[2 * f], mOut[2 * f + 1]};
        mSpec[0].imag(0.f);
        mSpec[kBins - 1].imag(0.f);
        for (int f = 1; f < kBins - 1; ++f) mSpec[kN - f] = std::conj(mSpec[f]);
        fft(mSpec, true);
        // irfft includes 1/N; synthesis window; overlap-add (w^2 sums to 1 at 50% overlap).
        for (int n = 0; n < kN; ++n) mOla[n] += mSpec[n].real() / kN * mWin[n];
        std::memcpy(out, mOla.data(), kHop * sizeof(float));
        std::memmove(mOla.data(), mOla.data() + kHop, (kN - kHop) * sizeof(float));
        std::fill(mOla.begin() + (kN - kHop), mOla.end(), 0.f);
        return true;
    }

private:
    std::vector<float> mWin, mFrame, mOla, mOut;
    std::vector<std::complex<float>> mSpec;
};

static std::string modelPath(const std::string& dir, const std::string& mode) {
    return dir + (mode == "studio" ? "/dfn3_stream.onnx" : "/gtcrn_simple.onnx");
}

// ------------------------------------------------------------------------------------------
bool OnnxDenoiser::modelAvailable(const std::string& dir, const std::string& mode) {
    if (dir.empty() || (mode != "studio" && mode != "fast")) return false;
    return fileExists(modelPath(dir, mode));
}

std::unique_ptr<StreamDenoiser> OnnxDenoiser::create(const std::string& dir, const std::string& mode, int sampleRate) {
    if (!modelAvailable(dir, mode)) return nullptr;
    std::unique_ptr<OnnxModelRunner> runner;
    if (mode == "studio") {
        auto r = std::make_unique<Dfn3Runner>();
        if (!r->init(modelPath(dir, mode))) return nullptr;
        runner = std::move(r);
    } else {
        auto r = std::make_unique<GtcrnRunner>();
        if (!r->init(modelPath(dir, mode))) return nullptr;
        runner = std::move(r);
    }
    std::unique_ptr<OnnxDenoiser> d(new OnnxDenoiser());
    if (!d->init(std::move(runner), sampleRate)) return nullptr;
    return d;
}

static SwrContext* makeMonoResampler(int inRate, int outRate) {
    SwrContext* s = nullptr;
    AVChannelLayout mono;
    av_channel_layout_default(&mono, 1);
    if (swr_alloc_set_opts2(&s, &mono, AV_SAMPLE_FMT_FLT, outRate, &mono, AV_SAMPLE_FMT_FLT, inRate, 0, nullptr) < 0) return nullptr;
    av_opt_set_int(s, "filter_size", 32, 0);
    if (swr_init(s) < 0) swr_free(&s);
    return s;
}

static void resampleInto(SwrContext* s, const float* in, int n, std::vector<float>& out) {
    const int maxOut = swr_get_out_samples(s, n) + 16;
    const size_t old = out.size();
    out.resize(old + maxOut);
    uint8_t* o = reinterpret_cast<uint8_t*>(out.data() + old);
    const uint8_t* i = reinterpret_cast<const uint8_t*>(in);
    const int got = swr_convert(s, &o, maxOut, n > 0 ? &i : nullptr, n);
    out.resize(old + std::max(0, got));
}

OnnxDenoiser::~OnnxDenoiser() {
    if (mDown) swr_free(&mDown);
    if (mUp) swr_free(&mUp);
}

int OnnxDenoiser::measureResamplerDelay() {
    // Push an impulse through an identical down->up resampler pair and locate the peak.
    SwrContext* d = makeMonoResampler(mEngineRate, mModelRate);
    SwrContext* u = makeMonoResampler(mModelRate, mEngineRate);
    if (!d || !u) { if (d) swr_free(&d); if (u) swr_free(&u); return 0; }
    std::vector<float> impulse(mEngineRate / 5, 0.f);
    impulse[0] = 1.f;
    std::vector<float> mid, out;
    resampleInto(d, impulse.data(), static_cast<int>(impulse.size()), mid);
    resampleInto(u, mid.data(), static_cast<int>(mid.size()), out);
    swr_free(&d);
    swr_free(&u);
    int best = 0;
    for (size_t i = 1; i < out.size(); ++i) if (std::fabs(out[i]) > std::fabs(out[best])) best = static_cast<int>(i);
    return best;
}

bool OnnxDenoiser::init(std::unique_ptr<OnnxModelRunner> runner, int engineRate) {
    mRunner = std::move(runner);
    mEngineRate = engineRate;
    mModelRate = mRunner->modelRate();
    mHop = mRunner->hop();
    mResample = mEngineRate != mModelRate;
    int rtDelay = 0;
    if (mResample) {
        rtDelay = measureResamplerDelay();
        mDown = makeMonoResampler(mEngineRate, mModelRate);
        mUp = makeMonoResampler(mModelRate, mEngineRate);
        if (!mDown || !mUp) return false;
    }
    // Pre-fill = one hop (in engine frames) + margin, so the output FIFO never underruns.
    mPrefill = static_cast<int>(std::ceil(static_cast<double>(mHop) * mEngineRate / mModelRate)) + 64;
    const int modelDelay = static_cast<int>(std::lround(static_cast<double>(mRunner->delaySamples()) * mEngineRate / mModelRate));
    mLatency = mPrefill + modelDelay + rtDelay;
    mHopBuf.resize(mHop);
    mHopOut.resize(mHop);
    mDry.assign(static_cast<size_t>(mLatency) * 2, 0.f);
    LOGI("Denoiser ready: model %d Hz hop %d, latency %d frames @%d Hz", mModelRate, mHop, mLatency, mEngineRate);
    reset();
    return true;
}

void OnnxDenoiser::reset() {
    mRunner->reset();
    mModelIn.clear();
    mWetOut.assign(static_cast<size_t>(mPrefill), 0.f);
    mWetRead = 0;
    std::fill(mDry.begin(), mDry.end(), 0.f);
    mDryPos = 0;
    if (mResample) {
        if (mDown) swr_free(&mDown);
        if (mUp) swr_free(&mUp);
        mDown = makeMonoResampler(mEngineRate, mModelRate);
        mUp = makeMonoResampler(mModelRate, mEngineRate);
    }
    mStrength = mStrengthTarget;
}

void OnnxDenoiser::setStrength(float wet01) { mStrengthTarget = std::clamp(wet01, 0.f, 1.f); }

void OnnxDenoiser::runAvailableHops() {
    size_t consumed = 0;
    while (mModelIn.size() - consumed >= static_cast<size_t>(mHop)) {
        std::memcpy(mHopBuf.data(), mModelIn.data() + consumed, mHop * sizeof(float));
        consumed += mHop;
        if (!mRunner->processHop(mHopBuf.data(), mHopOut.data())) {
            // Model failure: pass the (aligned) input through rather than glitching.
            std::memcpy(mHopOut.data(), mHopBuf.data(), mHop * sizeof(float));
        }
        if (mResample) resampleInto(mUp, mHopOut.data(), mHop, mWetOut);
        else mWetOut.insert(mWetOut.end(), mHopOut.begin(), mHopOut.end());
    }
    if (consumed) mModelIn.erase(mModelIn.begin(), mModelIn.begin() + static_cast<long>(consumed));
}

void OnnxDenoiser::process(float* x, int frames) {
    // 1. Mono mid -> model input FIFO
    mScratch.resize(frames);
    for (int i = 0; i < frames; ++i) mScratch[i] = 0.5f * (x[2 * i] + x[2 * i + 1]);
    if (mResample) resampleInto(mDown, mScratch.data(), frames, mModelIn);
    else mModelIn.insert(mModelIn.end(), mScratch.begin(), mScratch.end());
    runAvailableHops();

    // 2. Pop `frames` wet samples and mix with the latency-aligned dry signal.
    const float s0 = mStrength, s1 = mStrengthTarget;
    const size_t dryLen = mDry.size() / 2;
    for (int i = 0; i < frames; ++i) {
        float wet = 0.f;
        if (mWetRead < mWetOut.size()) wet = mWetOut[mWetRead++];
        const float s = s0 + (s1 - s0) * (static_cast<float>(i) / std::max(1, frames));
        float dl = x[2 * i], dr = x[2 * i + 1];
        if (dryLen > 0) {
            std::swap(dl, mDry[2 * mDryPos]);
            std::swap(dr, mDry[2 * mDryPos + 1]);
            if (++mDryPos >= dryLen) mDryPos = 0;
        }
        x[2 * i] = s * wet + (1.f - s) * dl;
        x[2 * i + 1] = s * wet + (1.f - s) * dr;
    }
    mStrength = s1;
    if (mWetRead > 8192) {
        mWetOut.erase(mWetOut.begin(), mWetOut.begin() + static_cast<long>(mWetRead));
        mWetRead = 0;
    }
}

}  // namespace tfx
