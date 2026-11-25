#pragma once
// Offline export pipeline: MultitrackMixer (identical to live preview, but with the
// high-quality Rubber Band engine) -> [optional EBU R128 loudness normalisation pass]
// -> FfmpegEncoder. Works in 4096-frame chunks; memory use is independent of length.
#include <string>
#include "common/Progress.h"
#include "engine/ProjectModel.h"

namespace tfx {

struct RenderRequest {
    ProjectDesc project;
    EncoderConfig encoder;
    int renderRate = 48000;
    int64_t startUs = 0;
    int64_t endUs = 0;  // <= 0 => to end of project
    std::string outPath;
    std::string tempDir;
};

bool renderProject(const RenderRequest& req, const ProgressFn& progress, std::string& error);

}  // namespace tfx
