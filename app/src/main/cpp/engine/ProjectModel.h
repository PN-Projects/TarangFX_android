#pragma once
// Immutable "Project Graph" passed from Kotlin as JSON. Original files are never modified:
// a project only references sources + trim points + DSP parameters.
#include <string>
#include <vector>
#include <cstdint>
#include "dsp/DspChain.h"
#include "io/FfmpegEncoder.h"

namespace tfx {

struct ClipDesc {
    int64_t id = 0;
    std::string source;          // "fd:N" or absolute path
    int64_t sourceStartUs = 0;   // trim-in within the source
    int64_t sourceEndUs = 0;     // trim-out within the source (exclusive)
    int64_t timelineStartUs = 0; // where the clip starts on the timeline
    float gainDb = 0.f;
    int64_t fadeInUs = 0;
    int64_t fadeOutUs = 0;
    bool equalPowerFade = false;
    DspParams dsp;

    int64_t sourceLengthUs() const { return sourceEndUs > sourceStartUs ? sourceEndUs - sourceStartUs : 0; }
    int64_t timelineLengthUs() const {
        const float s = dsp.speed > 0.01f ? dsp.speed : 1.f;
        return static_cast<int64_t>(static_cast<double>(sourceLengthUs()) / s);
    }
};

struct TrackDesc {
    int64_t id = 0;
    float gainDb = 0.f;
    bool muted = false;
    bool solo = false;
    std::vector<ClipDesc> clips;
};

struct ProjectDesc {
    std::vector<TrackDesc> tracks;
    DspParams master;
};

bool parseProject(const std::string& json, ProjectDesc& out, std::string& error);
bool parseClip(const std::string& json, ClipDesc& out, std::string& error);
bool parseDspParams(const std::string& json, DspParams& out, std::string& error);
bool parseEncoderConfig(const std::string& json, EncoderConfig& out, std::string& error);

}  // namespace tfx
