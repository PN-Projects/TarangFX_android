#include "engine/WaveformExtractor.h"
#include "io/FfmpegDecoder.h"
#include "common/Log.h"

#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>
#include <unistd.h>

namespace tfx {

int extractPeaks(const std::string& source, const std::string& cachePath, int pps,
                 const ProgressFn& progress) {
    if (pps <= 0) pps = 100;
    FfmpegDecoder dec;
    if (!dec.open(source, 0, 1)) return -1;  // native rate, mono downmix
    const int rate = dec.outRate();
    const int64_t durUs = dec.info().durationUs;
    const double framesPerPeak = static_cast<double>(rate) / pps;
    const int64_t totalFrames = durUs > 0 ? durUs * rate / 1000000 : 0;

    const std::string tmpPath = cachePath + ".part";
    FILE* f = std::fopen(tmpPath.c_str(), "wb");
    if (!f) {
        LOGE("Cannot create peaks cache %s", tmpPath.c_str());
        return -1;
    }
    // Header placeholder (count/duration patched at the end).
    const char magic[4] = {'P', 'K', 'S', '1'};
    int32_t version = 1, ppsI = pps, count = 0;
    int64_t durationMs = 0;
    std::fwrite(magic, 1, 4, f);
    std::fwrite(&version, 4, 1, f);
    std::fwrite(&ppsI, 4, 1, f);
    std::fwrite(&durationMs, 8, 1, f);
    std::fwrite(&count, 4, 1, f);

    constexpr int kChunk = 4096;
    std::vector<float> buf(kChunk);
    std::vector<float> out;
    out.reserve(1024);
    int64_t frameIdx = 0;
    double nextBoundary = framesPerPeak;
    float peak = 0.f;
    int64_t lastReport = 0;
    bool cancelled = false;

    for (;;) {
        const int n = dec.read(buf.data(), kChunk);
        if (n <= 0) break;
        for (int i = 0; i < n; ++i) {
            const float a = std::fabs(buf[i]);
            if (a > peak) peak = a;
            if (++frameIdx >= static_cast<int64_t>(nextBoundary)) {
                out.push_back(std::min(peak, 1.f));
                peak = 0.f;
                nextBoundary += framesPerPeak;
            }
        }
        if (out.size() >= 1024) {
            std::fwrite(out.data(), sizeof(float), out.size(), f);
            count += static_cast<int32_t>(out.size());
            out.clear();
        }
        if (progress && totalFrames > 0 && frameIdx - lastReport > rate) {
            lastReport = frameIdx;
            if (!progress(std::min(1.f, static_cast<float>(frameIdx) / totalFrames))) {
                cancelled = true;
                break;
            }
        }
    }
    if (!cancelled) {
        // Partial trailing bucket.
        if (frameIdx > static_cast<int64_t>(nextBoundary - framesPerPeak)) out.push_back(std::min(peak, 1.f));
        if (!out.empty()) {
            std::fwrite(out.data(), sizeof(float), out.size(), f);
            count += static_cast<int32_t>(out.size());
        }
        durationMs = frameIdx * 1000 / std::max(1, rate);
        std::fseek(f, 12, SEEK_SET);
        std::fwrite(&durationMs, 8, 1, f);
        std::fwrite(&count, 4, 1, f);
    }
    const bool writeOk = std::fflush(f) == 0;
    std::fclose(f);
    if (cancelled || !writeOk) {
        unlink(tmpPath.c_str());
        return cancelled ? -2 : -1;
    }
    if (std::rename(tmpPath.c_str(), cachePath.c_str()) != 0) {
        unlink(tmpPath.c_str());
        return -1;
    }
    if (progress) progress(1.f);
    return count;
}

}  // namespace tfx
