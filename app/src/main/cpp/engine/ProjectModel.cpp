#include "engine/ProjectModel.h"
#include <json.hpp>
#include <algorithm>

using nlohmann::json;

namespace tfx {

template <typename T>
static T get(const json& j, const char* key, T def) {
    auto it = j.find(key);
    if (it == j.end() || it->is_null()) return def;
    try {
        return it->get<T>();
    } catch (...) {
        return def;
    }
}

static void readDsp(const json& j, DspParams& p) {
    if (!j.is_object()) return;
    p.eqEnabled = get<bool>(j, "eqEnabled", false);
    auto bands = j.find("eqBands");
    if (bands != j.end() && bands->is_array()) {
        for (size_t i = 0; i < std::min<size_t>(bands->size(), TenBandEq::kBands); ++i) {
            p.eqBands[i] = (*bands)[i].is_number() ? (*bands)[i].get<float>() : 0.f;
        }
    }
    p.bassAmount = get<float>(j, "bassBoostAmount", 0.f);
    p.bassFreq = get<float>(j, "bassBoostFreq", 80.f);
    p.reverbEnabled = get<bool>(j, "reverbEnabled", false);
    p.reverbRoom = get<float>(j, "reverbRoomSize", 0.5f);
    p.reverbDamping = get<float>(j, "reverbDamping", 0.5f);
    p.reverbWet = get<float>(j, "reverbWetDry", 0.25f);
    p.reverbPreDelayMs = get<float>(j, "reverbPreDelayMs", 10.f);
    p.volumeDb = get<float>(j, "volumeGainDb", 0.f);
    p.voiceEnhance = get<bool>(j, "voiceEnhanceEnabled", false);
    p.denoiseMode = get<std::string>(j, "aiDenoiseMode", "off");
    p.denoiseStrength = std::clamp(get<float>(j, "aiDenoiseStrength", 1.f), 0.f, 1.f);
    p.speed = std::clamp(get<float>(j, "speed", 1.f), 0.25f, 4.f);
    p.pitchSemitones = std::clamp(get<float>(j, "pitchSemitones", 0.f), -12.f, 12.f);
    p.preserveFormants = get<bool>(j, "preserveFormants", true);
    p.bypass = get<bool>(j, "bypass", false);
    p.normalizeLufs = get<float>(j, "normalizeLufs", 0.f);
}

static void readClip(const json& c, ClipDesc& clip) {
    clip.id = get<int64_t>(c, "id", 0);
    clip.source = get<std::string>(c, "source", "");
    clip.sourceStartUs = std::max<int64_t>(0, get<int64_t>(c, "sourceStartUs", 0));
    clip.sourceEndUs = get<int64_t>(c, "sourceEndUs", 0);
    clip.timelineStartUs = std::max<int64_t>(0, get<int64_t>(c, "timelineStartUs", 0));
    clip.gainDb = get<float>(c, "gainDb", 0.f);
    clip.fadeInUs = std::max<int64_t>(0, get<int64_t>(c, "fadeInUs", 0));
    clip.fadeOutUs = std::max<int64_t>(0, get<int64_t>(c, "fadeOutUs", 0));
    clip.equalPowerFade = get<std::string>(c, "fadeCurve", "linear") == "equalPower";
    auto d = c.find("dsp");
    if (d != c.end()) readDsp(*d, clip.dsp);
}

bool parseProject(const std::string& text, ProjectDesc& out, std::string& error) {
    try {
        json j = json::parse(text);
        out = ProjectDesc{};
        for (const auto& t : j.value("tracks", json::array())) {
            TrackDesc track;
            track.id = get<int64_t>(t, "id", 0);
            track.gainDb = get<float>(t, "gainDb", 0.f);
            track.muted = get<bool>(t, "muted", false);
            track.solo = get<bool>(t, "solo", false);
            for (const auto& c : t.value("clips", json::array())) {
                ClipDesc clip;
                readClip(c, clip);
                if (clip.source.empty() || clip.sourceLengthUs() <= 0) continue;
                track.clips.push_back(std::move(clip));
            }
            out.tracks.push_back(std::move(track));
        }
        auto m = j.find("master");
        if (m != j.end()) readDsp(*m, out.master);
        return true;
    } catch (const std::exception& e) {
        error = e.what();
        return false;
    }
}

bool parseClip(const std::string& text, ClipDesc& out, std::string& error) {
    try {
        readClip(json::parse(text), out);
        return true;
    } catch (const std::exception& e) {
        error = e.what();
        return false;
    }
}

bool parseDspParams(const std::string& text, DspParams& out, std::string& error) {
    try {
        out = DspParams{};
        readDsp(json::parse(text), out);
        return true;
    } catch (const std::exception& e) {
        error = e.what();
        return false;
    }
}

bool parseEncoderConfig(const std::string& text, EncoderConfig& out, std::string& error) {
    try {
        json j = json::parse(text);
        out = EncoderConfig{};
        out.format = get<std::string>(j, "codec", "wav");
        std::transform(out.format.begin(), out.format.end(), out.format.begin(), ::tolower);
        out.sampleRate = get<int>(j, "sampleRate", 0);
        out.channels = get<int>(j, "channels", 0);
        out.bitrateKbps = get<int>(j, "bitrateKbps", 192);
        out.vbr = get<bool>(j, "vbr", false);
        out.bitDepth = get<int>(j, "bitDepth", 16);
        auto md = j.find("metadata");
        if (md != j.end() && md->is_object()) {
            static const std::pair<const char*, const char*> keys[] = {
                {"title", "title"}, {"artist", "artist"}, {"album", "album"},
                {"albumArtist", "album_artist"}, {"year", "date"}, {"genre", "genre"},
                {"comment", "comment"}, {"trackNumber", "track"}};
            for (auto& [src, dst] : keys) {
                auto v = md->find(src);
                if (v != md->end() && v->is_string()) out.metadata[dst] = v->get<std::string>();
                else if (v != md->end() && v->is_number_integer() && v->get<int>() > 0)
                    out.metadata[dst] = std::to_string(v->get<int>());
            }
        }
        return true;
    } catch (const std::exception& e) {
        error = e.what();
        return false;
    }
}

}  // namespace tfx
