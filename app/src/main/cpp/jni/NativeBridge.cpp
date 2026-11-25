// JNI entry points for com.pnprojects.tarangfx.nativebridge.NativeAudio.
// All functions catch C++ exceptions so nothing unwinds across the JNI boundary.
#include <jni.h>
#include <string>
#include <vector>
#include <json.hpp>

#include "common/Log.h"
#include "engine/AudioEngine.h"
#include "engine/OfflineRenderer.h"
#include "engine/Recorder.h"
#include "engine/WaveformExtractor.h"
#include "io/FfmpegDecoder.h"
#include "io/FfmpegEncoder.h"
#include "io/TagLibBridge.h"
#include "ai/StreamDenoiser.h"

extern "C" {
#include <libavutil/log.h>
}

using nlohmann::json;
using namespace tfx;

#define JNI_FN(name) Java_com_pnprojects_tarangfx_nativebridge_NativeAudio_##name

static std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

static jstring jnew(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }

// Wraps a Kotlin NativeProgressListener (fun onProgress(Float): Boolean) — called on the same thread.
static ProgressFn makeProgress(JNIEnv* env, jobject listener) {
    if (!listener) return {};
    jclass cls = env->GetObjectClass(listener);
    jmethodID mid = env->GetMethodID(cls, "onProgress", "(F)Z");
    env->DeleteLocalRef(cls);
    if (!mid) return {};
    return [env, listener, mid](float p) -> bool {
        jboolean keep = env->CallBooleanMethod(listener, mid, static_cast<jfloat>(p));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        return keep == JNI_TRUE;
    };
}

static void ffLogCallback(void*, int level, const char* fmt, va_list vl) {
    if (level > AV_LOG_WARNING) return;
    char line[1024];
    vsnprintf(line, sizeof(line), fmt, vl);
    __android_log_print(level <= AV_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, "FFmpeg", "%s", line);
}

extern "C" {

JNIEXPORT void JNICALL JNI_FN(nativeInit)(JNIEnv* env, jclass, jstring modelDir) {
    av_log_set_callback(ffLogCallback);
    av_log_set_level(AV_LOG_WARNING);
    setDenoiserModelDirectory(jstr(env, modelDir));
}

// ------------------------------------------------------------------------------ probing
JNIEXPORT jstring JNICALL JNI_FN(probe)(JNIEnv* env, jclass, jstring source) {
    try {
        MediaInfo info;
        if (!FfmpegDecoder::probe(jstr(env, source), info)) return nullptr;
        json j = {{"durationUs", info.durationUs}, {"sampleRate", info.sampleRate},
                  {"channels", info.channels},     {"bitRate", info.bitRate},
                  {"codec", info.codec},           {"container", info.container},
                  {"hasAudio", info.hasAudio},     {"hasVideo", info.hasVideo},
                  {"bitsPerSample", info.bitsPerSample}};
        return jnew(env, j.dump());
    } catch (const std::exception& e) {
        LOGE("probe: %s", e.what());
        return nullptr;
    }
}

JNIEXPORT jint JNICALL JNI_FN(extractPeaks)(JNIEnv* env, jclass, jstring source, jstring cachePath,
                                            jint pps, jobject listener) {
    try {
        return extractPeaks(jstr(env, source), jstr(env, cachePath), pps, makeProgress(env, listener));
    } catch (const std::exception& e) {
        LOGE("extractPeaks: %s", e.what());
        return -1;
    }
}

// ------------------------------------------------------------------------------ engine
JNIEXPORT jstring JNICALL JNI_FN(engineSetProject)(JNIEnv* env, jclass, jstring projectJson) {
    try {
        std::string err;
        if (AudioEngine::instance().setProject(jstr(env, projectJson), err)) return nullptr;
        return jnew(env, err.empty() ? "Invalid project" : err);
    } catch (const std::exception& e) {
        return jnew(env, e.what());
    }
}
JNIEXPORT void JNICALL JNI_FN(engineClear)(JNIEnv*, jclass) { AudioEngine::instance().clearProject(); }
JNIEXPORT void JNICALL JNI_FN(enginePlay)(JNIEnv*, jclass) { AudioEngine::instance().play(); }
JNIEXPORT void JNICALL JNI_FN(enginePause)(JNIEnv*, jclass) { AudioEngine::instance().pause(); }
JNIEXPORT void JNICALL JNI_FN(engineSeek)(JNIEnv*, jclass, jlong us) { AudioEngine::instance().seekUs(us); }
JNIEXPORT jlong JNICALL JNI_FN(enginePosition)(JNIEnv*, jclass) { return AudioEngine::instance().positionUs(); }
JNIEXPORT jlong JNICALL JNI_FN(engineDuration)(JNIEnv*, jclass) { return AudioEngine::instance().durationUs(); }
JNIEXPORT jboolean JNICALL JNI_FN(engineIsPlaying)(JNIEnv*, jclass) {
    return AudioEngine::instance().isPlaying() ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jint JNICALL JNI_FN(engineSampleRate)(JNIEnv*, jclass) { return AudioEngine::instance().sampleRate(); }
JNIEXPORT void JNICALL JNI_FN(engineSetPlayRange)(JNIEnv*, jclass, jlong s, jlong e, jboolean loop) {
    AudioEngine::instance().setPlayRange(s, e, loop == JNI_TRUE);
}
JNIEXPORT void JNICALL JNI_FN(engineSetSkipRange)(JNIEnv*, jclass, jlong s, jlong e) {
    AudioEngine::instance().setSkipRange(s, e);
}
JNIEXPORT jboolean JNICALL JNI_FN(engineUpdateClip)(JNIEnv* env, jclass, jstring clipJson) {
    try {
        return AudioEngine::instance().updateClip(jstr(env, clipJson)) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}
JNIEXPORT jboolean JNICALL JNI_FN(engineSetMasterDsp)(JNIEnv* env, jclass, jstring dspJson) {
    try {
        return AudioEngine::instance().setMasterDsp(jstr(env, dspJson)) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}
JNIEXPORT void JNICALL JNI_FN(engineSetTrackState)(JNIEnv*, jclass, jlong id, jfloat gainDb,
                                                   jboolean muted, jboolean solo) {
    AudioEngine::instance().setTrackState(id, gainDb, muted == JNI_TRUE, solo == JNI_TRUE);
}

// ------------------------------------------------------------------------------ export
JNIEXPORT jstring JNICALL JNI_FN(render)(JNIEnv* env, jclass, jstring projectJson, jstring encoderJson,
                                         jstring outPath, jlong startUs, jlong endUs, jint renderRate,
                                         jstring tempDir, jobject listener) {
    try {
        RenderRequest req;
        std::string err;
        if (!parseProject(jstr(env, projectJson), req.project, err)) return jnew(env, "Bad project: " + err);
        if (!parseEncoderConfig(jstr(env, encoderJson), req.encoder, err)) return jnew(env, "Bad export config: " + err);
        req.outPath = jstr(env, outPath);
        req.tempDir = jstr(env, tempDir);
        req.startUs = startUs;
        req.endUs = endUs;
        req.renderRate = renderRate > 0 ? renderRate : 48000;
        if (renderProject(req, makeProgress(env, listener), err)) return nullptr;
        return jnew(env, err.empty() ? "Render failed" : err);
    } catch (const std::exception& e) {
        return jnew(env, e.what());
    }
}

JNIEXPORT jstring JNICALL JNI_FN(planRemux)(JNIEnv* env, jclass, jstring source) {
    try {
        RemuxPlan p = planAudioRemux(jstr(env, source));
        json j = {{"codec", p.codec}, {"muxer", p.muxer}, {"extension", p.extension}};
        return jnew(env, j.dump());
    } catch (...) {
        return nullptr;
    }
}

JNIEXPORT jstring JNICALL JNI_FN(remux)(JNIEnv* env, jclass, jstring source, jstring outPath,
                                        jstring muxer, jobject listener) {
    try {
        std::string err;
        if (remuxAudio(jstr(env, source), jstr(env, outPath), jstr(env, muxer), makeProgress(env, listener), err))
            return nullptr;
        return jnew(env, err.empty() ? "Extraction failed" : err);
    } catch (const std::exception& e) {
        return jnew(env, e.what());
    }
}

// ------------------------------------------------------------------------------ tags
JNIEXPORT jstring JNICALL JNI_FN(readTags)(JNIEnv* env, jclass, jstring source, jstring nameHint) {
    try {
        AudioTags t;
        if (!readTags(jstr(env, source), jstr(env, nameHint), t)) return nullptr;
        json j = {{"title", t.title}, {"artist", t.artist}, {"album", t.album},
                  {"albumArtist", t.albumArtist}, {"year", t.year}, {"trackNumber", t.track},
                  {"genre", t.genre}, {"comment", t.comment}, {"hasCover", !t.cover.empty()},
                  {"coverMime", t.coverMime}};
        return jnew(env, j.dump());
    } catch (const std::exception& e) {
        LOGE("readTags: %s", e.what());
        return nullptr;
    }
}

JNIEXPORT jbyteArray JNICALL JNI_FN(readCover)(JNIEnv* env, jclass, jstring source, jstring nameHint) {
    try {
        AudioTags t;
        if (!readTags(jstr(env, source), jstr(env, nameHint), t) || t.cover.empty()) return nullptr;
        jbyteArray arr = env->NewByteArray(static_cast<jsize>(t.cover.size()));
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(t.cover.size()),
                                reinterpret_cast<const jbyte*>(t.cover.data()));
        return arr;
    } catch (...) {
        return nullptr;
    }
}

JNIEXPORT jstring JNICALL JNI_FN(writeTags)(JNIEnv* env, jclass, jstring path, jstring tagsJson,
                                            jbyteArray cover, jstring coverMime, jint coverMode) {
    try {
        AudioTags t;
        json j = json::parse(jstr(env, tagsJson));
        t.title = j.value("title", "");
        t.artist = j.value("artist", "");
        t.album = j.value("album", "");
        t.albumArtist = j.value("albumArtist", "");
        t.genre = j.value("genre", "");
        t.comment = j.value("comment", "");
        t.year = j.value("year", 0);
        t.track = j.value("trackNumber", 0);
        if (cover) {
            const jsize n = env->GetArrayLength(cover);
            t.cover.resize(static_cast<size_t>(n));
            env->GetByteArrayRegion(cover, 0, n, reinterpret_cast<jbyte*>(t.cover.data()));
            t.coverMime = jstr(env, coverMime);
        }
        std::string err;
        if (writeTags(jstr(env, path), t, coverMode, err)) return nullptr;
        return jnew(env, err);
    } catch (const std::exception& e) {
        return jnew(env, e.what());
    }
}

// ------------------------------------------------------------------------------ recorder
JNIEXPORT jstring JNICALL JNI_FN(recStart)(JNIEnv* env, jclass, jstring path, jstring encoderJson,
                                           jint channels, jboolean liveDenoise) {
    try {
        EncoderConfig cfg;
        std::string err;
        if (!parseEncoderConfig(jstr(env, encoderJson), cfg, err)) return jnew(env, err);
        if (Recorder::instance().start(jstr(env, path), cfg, channels, liveDenoise == JNI_TRUE, err)) return nullptr;
        return jnew(env, err);
    } catch (const std::exception& e) {
        return jnew(env, e.what());
    }
}
JNIEXPORT void JNICALL JNI_FN(recPause)(JNIEnv*, jclass) { Recorder::instance().pause(); }
JNIEXPORT void JNICALL JNI_FN(recResume)(JNIEnv*, jclass) { Recorder::instance().resume(); }
JNIEXPORT jlong JNICALL JNI_FN(recStop)(JNIEnv*, jclass) { return Recorder::instance().stop(); }
JNIEXPORT void JNICALL JNI_FN(recSetGain)(JNIEnv*, jclass, jfloat db) { Recorder::instance().setGainDb(db); }
JNIEXPORT jfloat JNICALL JNI_FN(recLevel)(JNIEnv*, jclass) { return Recorder::instance().level(); }
JNIEXPORT jlong JNICALL JNI_FN(recPosition)(JNIEnv*, jclass) { return Recorder::instance().recordedUs(); }
JNIEXPORT jboolean JNICALL JNI_FN(recIsActive)(JNIEnv*, jclass) {
    return Recorder::instance().isActive() ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL JNI_FN(recSetLiveDenoise)(JNIEnv*, jclass, jboolean on) {
    Recorder::instance().setLiveDenoise(on == JNI_TRUE);
}
JNIEXPORT jfloatArray JNICALL JNI_FN(recDrainPeaks)(JNIEnv* env, jclass) {
    std::vector<float> p = Recorder::instance().drainPeaks();
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(p.size()));
    if (!p.empty()) env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(p.size()), p.data());
    return arr;
}

// ------------------------------------------------------------------------------ AI
JNIEXPORT jboolean JNICALL JNI_FN(denoiseAvailable)(JNIEnv* env, jclass, jstring mode) {
    return isDenoiserAvailable(jstr(env, mode)) ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
