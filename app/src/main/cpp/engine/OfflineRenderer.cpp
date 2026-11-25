#include "engine/OfflineRenderer.h"
#include "engine/MultitrackMixer.h"
#include "io/FfmpegEncoder.h"
#include "dsp/Dynamics.h"
#include "dsp/SoftLimiter.h"
#include "common/Log.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <vector>
#include <unistd.h>

namespace tfx {

static constexpr int kChunk = 4096;

bool renderProject(const RenderRequest& req, const ProgressFn& progress, std::string& error) {
    const int rate = req.renderRate > 0 ? req.renderRate : 48000;
    MultitrackMixer mixer(rate, /*highQuality=*/true);
    mixer.setProject(req.project);

    const int64_t start = std::max<int64_t>(0, req.startUs * rate / 1000000);
    int64_t end = req.endUs > 0 ? req.endUs * rate / 1000000 : mixer.lengthFrames();
    end = std::min(end, mixer.lengthFrames());
    if (end <= start) {
        error = "Nothing to render (empty selection)";
        return false;
    }
    const int64_t total = end - start;

    // The master limiter adds a small lookahead delay: render it extra and drop it from the head.
    SoftLimiter probe;
    probe.prepare(rate, -0.3f);
    const int latency = probe.latencyFrames();

    const float targetLufs = req.project.master.normalizeLufs;
    const bool twoPass = targetLufs < -0.5f;

    FfmpegEncoder enc;
    if (!enc.open(req.outPath, req.encoder, rate, 2)) {
        error = enc.error();
        return false;
    }

    std::vector<float> buf(kChunk * 2);
    mixer.seek(start);
    int skip = latency;
    int64_t produced = 0;
    int64_t renderedTotal = 0;
    const float passWeight = twoPass ? 0.7f : 1.f;

    FILE* tmp = nullptr;
    std::string tmpPath;
    LoudnessMeter meter;
    if (twoPass) {
        meter.prepare(rate);
        meter.setMonoOutput(enc.outputChannels() == 1);
        tmpPath = (req.tempDir.empty() ? std::string("/data/local/tmp") : req.tempDir) + "/render_" +
                  std::to_string(static_cast<long long>(getpid())) + "_" + std::to_string(reinterpret_cast<uintptr_t>(&buf)) + ".f32";
        tmp = std::fopen(tmpPath.c_str(), "w+b");
        if (!tmp) {
            enc.abort();
            error = "Cannot create temporary render file";
            return false;
        }
    }

    auto fail = [&](const std::string& msg) {
        if (tmp) { std::fclose(tmp); unlink(tmpPath.c_str()); }
        enc.abort();
        error = msg;
        return false;
    };

    // ---- Pass 1: render the mix ----
    while (produced < total) {
        const int64_t remainingWithLatency = total + latency - renderedTotal;
        const int n = static_cast<int>(std::min<int64_t>(kChunk, remainingWithLatency));
        if (n <= 0) break;
        mixer.render(buf.data(), n);
        renderedTotal += n;
        int offset = 0;
        if (skip > 0) {
            offset = std::min(skip, n);
            skip -= offset;
        }
        const int usable = static_cast<int>(std::min<int64_t>(n - offset, total - produced));
        if (usable > 0) {
            const float* p = buf.data() + offset * 2;
            if (twoPass) {
                meter.process(p, usable);
                if (std::fwrite(p, sizeof(float) * 2, usable, tmp) != static_cast<size_t>(usable))
                    return fail("Disk full while rendering");
            } else if (!enc.write(p, usable)) {
                return fail(enc.error());
            }
            produced += usable;
        }
        if (progress && !progress(passWeight * static_cast<float>(produced) / total))
            return fail("Cancelled");
    }

    // ---- Pass 2 (optional): EBU R128 loudness normalisation ----
    // The -1 dBFS limiter reduces loudness on peaky material (speech), so a single gain computed
    // from pass 1 undershoots. Refine the gain with dry runs (gain -> limiter -> meter, no
    // encoding) over the float temp render until post-limiter loudness is within 0.05 LU.
    if (twoPass) {
        constexpr float kCeilingDb = -1.0f;  // headroom recommended for lossy streaming targets
        auto runPass = [&](float gainDb, bool encode, float progressBase, float progressSpan,
                           double& outLufs) -> bool {
            const float gain = std::pow(10.f, gainDb / 20.f);
            SoftLimiter lim;
            lim.prepare(rate, kCeilingDb);
            LoudnessMeter m;
            m.prepare(rate);
            m.setMonoOutput(enc.outputChannels() == 1);
            const int lat = lim.latencyFrames();
            std::rewind(tmp);
            int64_t done = 0;
            int skipFrames = lat;
            bool eof = false;
            while (done < total) {
                int n = 0;
                if (!eof) {
                    n = static_cast<int>(std::fread(buf.data(), sizeof(float) * 2, kChunk, tmp));
                    if (n < kChunk) eof = true;
                }
                if (n == 0) {  // flush limiter lookahead with silence
                    n = std::min<int>(kChunk, lat + 1);
                    std::fill(buf.begin(), buf.begin() + n * 2, 0.f);
                }
                for (int i = 0; i < n * 2; ++i) buf[i] *= gain;
                lim.process(buf.data(), n);
                int offset = 0;
                if (skipFrames > 0) {
                    offset = std::min(skipFrames, n);
                    skipFrames -= offset;
                }
                const int usable = static_cast<int>(std::min<int64_t>(n - offset, total - done));
                if (usable > 0) {
                    const float* out = buf.data() + offset * 2;
                    if (encode) {
                        if (!enc.write(out, usable)) { error = enc.error(); return false; }
                    } else {
                        m.process(out, usable);
                    }
                }
                done += std::max(0, usable);
                if (progress && !progress(progressBase + progressSpan * static_cast<float>(done) / total)) {
                    error = "Cancelled";
                    return false;
                }
            }
            outLufs = encode ? 0.0 : m.integratedLufs();
            return true;
        };

        // Root-find gain g such that L(g) = target, where L is post-limiter loudness. L is monotonic
        // in g but its slope is < 1 LU/dB (the limiter absorbs part of every boost: ~0.3-0.4 on
        // speech), so a unit-slope Newton step undershoots and crawls. Safeguarded secant: learn the
        // slope from the last two dry runs, keep a bracket [lo, hi] around the target once crossed,
        // and bisect if a secant guess would leave it. Typically converges in 3-4 cheap dry runs.
        constexpr float kMinGain = -24.f, kMaxGain = 24.f;
        constexpr float kTolLu = 0.05f;
        constexpr int kMaxRefine = 8;
        const double measured = meter.integratedLufs();
        float gainDb = std::clamp(static_cast<float>(targetLufs - measured), kMinGain, kMaxGain);
        float prevGain = 0.f;
        double prevAchieved = measured;  // gain 0 dB gave the pass-1 measurement
        float lo = kMinGain, hi = kMaxGain;  // bracket: L(lo) < target < L(hi)
        float bestGain = gainDb;
        double bestErr = 1e9;
        for (int it = 0; it < kMaxRefine; ++it) {
            double achieved = 0;
            if (!runPass(gainDb, false, 0.7f + 0.15f * it / kMaxRefine, 0.15f / kMaxRefine, achieved)) {
                const std::string e = error;
                return fail(e);
            }
            const double residual = targetLufs - achieved;
            LOGI("Loudness pass %d: gain %.2f dB -> %.2f LUFS (target %.1f)", it, gainDb, achieved, targetLufs);
            if (std::fabs(residual) < bestErr) { bestErr = std::fabs(residual); bestGain = gainDb; }
            if (std::fabs(residual) < kTolLu) break;
            if (residual > 0) lo = std::max(lo, gainDb); else hi = std::min(hi, gainDb);

            double slope = (achieved - prevAchieved) / (gainDb - prevGain);
            if (!std::isfinite(slope) || gainDb == prevGain) slope = 1.0;
            slope = std::clamp(slope, 0.05, 1.0);  // loudness never rises faster than gain
            float next = gainDb + static_cast<float>(residual / slope);
            if (!(next > lo && next < hi)) next = 0.5f * (lo + hi);  // outside bracket -> bisect
            next = std::clamp(next, kMinGain, kMaxGain);
            if (std::fabs(next - gainDb) < 1e-3f) break;  // gain cap reached; best effort
            prevGain = gainDb;
            prevAchieved = achieved;
            gainDb = next;
        }
        gainDb = bestGain;  // encode with the best gain actually measured, never an untested guess
        double unused = 0;
        if (!runPass(gainDb, true, 0.85f, 0.15f, unused)) {
            const std::string e = error;
            return fail(e);
        }
        std::fclose(tmp);
        tmp = nullptr;
        unlink(tmpPath.c_str());
    }

    if (!enc.finish()) {
        error = enc.error();
        unlink(req.outPath.c_str());
        return false;
    }
    if (progress) progress(1.f);
    return true;
}

}  // namespace tfx
