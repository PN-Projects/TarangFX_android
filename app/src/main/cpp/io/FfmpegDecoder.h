#pragma once
// Streaming FFmpeg audio decoder. Produces interleaved float32 PCM at a requested sample rate /
// channel count. Never decodes the whole file into memory: data is pulled packet-by-packet and
// buffered only as much as one decoded frame requires.
#include <string>
#include <vector>
#include <cstdint>
#include "io/SourceFile.h"

struct AVFormatContext;
struct AVCodecContext;
struct SwrContext;
struct AVPacket;
struct AVFrame;

namespace tfx {

struct MediaInfo {
    int64_t durationUs = 0;
    int sampleRate = 0;
    int channels = 0;
    int64_t bitRate = 0;
    std::string codec;      // e.g. "mp3", "aac", "flac"
    std::string container;  // e.g. "mp3", "mov,mp4,m4a,3gp,3g2,mj2"
    bool hasAudio = false;
    bool hasVideo = false;
    int bitsPerSample = 0;
};

class FfmpegDecoder {
public:
    FfmpegDecoder() = default;
    ~FfmpegDecoder();
    FfmpegDecoder(const FfmpegDecoder&) = delete;
    FfmpegDecoder& operator=(const FfmpegDecoder&) = delete;

    // outRate/outChannels == 0 -> keep source value (channels are capped to 2).
    bool open(const std::string& source, int outRate = 0, int outChannels = 0);
    void close();

    const MediaInfo& info() const { return mInfo; }
    int outRate() const { return mOutRate; }
    int outChannels() const { return mOutChannels; }

    // Reads up to `frames` interleaved frames. Returns frames written (0 => end of stream).
    int read(float* dst, int frames);

    // Sample-accurate seek (decodes from the previous keyframe and discards up to the target).
    bool seekUs(int64_t positionUs);

    bool isEof() const { return mEof && mFifoRead >= mFifo.size(); }

    // Probe without keeping the decoder open.
    static bool probe(const std::string& source, MediaInfo& out);

private:
    bool decodeMore();
    bool setupResampler(const AVFrame* frame);
    void appendConverted(const AVFrame* frame);
    void drainResampler();

    SourceFile mFile;
    AVFormatContext* mFmt = nullptr;
    AVCodecContext* mCodec = nullptr;
    SwrContext* mSwr = nullptr;
    AVPacket* mPacket = nullptr;
    AVFrame* mFrame = nullptr;
    int mStreamIndex = -1;

    int mOutRate = 0;
    int mOutChannels = 0;
    int mSwrInRate = 0;
    int mSwrInFormat = -1;
    int mSwrInChannels = 0;

    std::vector<float> mFifo;  // interleaved
    size_t mFifoRead = 0;      // index in samples (not frames)

    bool mInputDrained = false;  // demuxer hit EOF and null packet was sent
    bool mEof = false;

    // Sample-accurate seek bookkeeping
    bool mAwaitFirstPts = false;
    int64_t mSeekTargetFrames = 0;
    int64_t mDiscardFrames = 0;
    int64_t mStreamStartPts = 0;

    MediaInfo mInfo;
};

}  // namespace tfx
