#pragma once
// Streaming FFmpeg encoder + muxer. Accepts interleaved float32 PCM in arbitrary chunk sizes,
// converts/resamples to the codec's native sample format and writes to a file path.
#include <string>
#include <map>
#include <functional>
#include <cstdint>

struct AVFormatContext;
struct AVCodecContext;
struct SwrContext;
struct AVAudioFifo;
struct AVFrame;
struct AVPacket;
struct AVStream;

namespace tfx {

struct EncoderConfig {
    std::string format = "wav";   // mp3 | aac | m4a | flac | wav | opus | ogg | alac
    int sampleRate = 0;           // 0 => same as input
    int channels = 0;             // 0 => same as input (1 or 2)
    int bitrateKbps = 192;        // lossy codecs
    bool vbr = false;
    int bitDepth = 16;            // 16 | 24 | 32 (32 => float for WAV, 32-bit int otherwise)
    std::map<std::string, std::string> metadata;  // container-level tags (TagLib does the rest)
};

class FfmpegEncoder {
public:
    FfmpegEncoder() = default;
    ~FfmpegEncoder();
    FfmpegEncoder(const FfmpegEncoder&) = delete;
    FfmpegEncoder& operator=(const FfmpegEncoder&) = delete;

    bool open(const std::string& path, const EncoderConfig& cfg, int inputRate, int inputChannels);
    bool write(const float* interleaved, int frames);
    bool finish();
    void abort();

    const std::string& error() const { return mError; }
    int outputRate() const { return mOutRate; }
    int outputChannels() const { return mOutChannels; }

    // Resolves the sample rate a codec will actually use for a requested rate (e.g. MP3 max 48 kHz).
    static int resolveSampleRate(const std::string& format, int requested);

private:
    bool encodeFromFifo(bool flush);
    bool sendFrame(AVFrame* frame);
    void cleanup();

    AVFormatContext* mFmt = nullptr;
    AVCodecContext* mCodec = nullptr;
    AVStream* mStream = nullptr;
    SwrContext* mSwr = nullptr;
    AVAudioFifo* mFifo = nullptr;
    AVFrame* mFrame = nullptr;
    AVPacket* mPacket = nullptr;
    int mFrameSize = 1024;
    int64_t mPts = 0;
    int mInRate = 0, mInChannels = 0, mOutRate = 0, mOutChannels = 0;
    bool mHeaderWritten = false;
    bool mFinished = false;
    std::string mPath;
    std::string mError;
};

// Copies the best audio stream from a media file (e.g. MP4 / MKV video) into an audio-only
// container without re-encoding. `outPath` extension must match `muxer`.
struct RemuxPlan {
    std::string codec;      // source codec name
    std::string muxer;      // ffmpeg muxer name, empty if stream copy is impossible
    std::string extension;  // e.g. "m4a"
};
RemuxPlan planAudioRemux(const std::string& source);
bool remuxAudio(const std::string& source, const std::string& outPath, const std::string& muxer,
                const std::function<bool(float)>& progress, std::string& error);

}  // namespace tfx
