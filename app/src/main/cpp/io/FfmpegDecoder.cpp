#include "io/FfmpegDecoder.h"
#include "common/Log.h"

#include <algorithm>
#include <cstring>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswresample/swresample.h>
#include <libavutil/opt.h>
#include <libavutil/channel_layout.h>
}

namespace tfx {

static std::string ffErr(int err) {
    char buf[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(err, buf, sizeof(buf));
    return buf;
}

FfmpegDecoder::~FfmpegDecoder() { close(); }

void FfmpegDecoder::close() {
    if (mSwr) swr_free(&mSwr);
    if (mCodec) avcodec_free_context(&mCodec);
    if (mFmt) avformat_close_input(&mFmt);
    if (mPacket) av_packet_free(&mPacket);
    if (mFrame) av_frame_free(&mFrame);
    mFile.close();
    mFifo.clear();
    mFifoRead = 0;
    mStreamIndex = -1;
    mEof = false;
    mInputDrained = false;
}

static bool openFormat(SourceFile& file, AVFormatContext** outFmt) {
    AVFormatContext* fmt = avformat_alloc_context();
    if (!fmt) return false;
    fmt->pb = file.avio();
    fmt->flags |= AVFMT_FLAG_CUSTOM_IO;
    if (!fmt->pb) {
        avformat_free_context(fmt);
        return false;
    }
    int err = avformat_open_input(&fmt, nullptr, nullptr, nullptr);
    if (err < 0) {
        LOGE("avformat_open_input failed: %s", ffErr(err).c_str());
        return false;  // fmt freed by avformat_open_input on failure
    }
    err = avformat_find_stream_info(fmt, nullptr);
    if (err < 0) {
        LOGW("avformat_find_stream_info: %s", ffErr(err).c_str());
    }
    *outFmt = fmt;
    return true;
}

static void fillInfo(AVFormatContext* fmt, int audioIdx, MediaInfo& info) {
    info = MediaInfo{};
    info.container = fmt->iformat ? fmt->iformat->name : "";
    for (unsigned i = 0; i < fmt->nb_streams; ++i) {
        auto* st = fmt->streams[i];
        if (st->codecpar->codec_type == AVMEDIA_TYPE_VIDEO &&
            !(st->disposition & AV_DISPOSITION_ATTACHED_PIC)) {
            info.hasVideo = true;
        }
    }
    if (audioIdx < 0) return;
    auto* st = fmt->streams[audioIdx];
    auto* par = st->codecpar;
    info.hasAudio = true;
    info.sampleRate = par->sample_rate;
    info.channels = par->ch_layout.nb_channels;
    info.bitRate = par->bit_rate > 0 ? par->bit_rate : fmt->bit_rate;
    info.bitsPerSample = par->bits_per_raw_sample > 0 ? par->bits_per_raw_sample : par->bits_per_coded_sample;
    const AVCodecDescriptor* desc = avcodec_descriptor_get(par->codec_id);
    info.codec = desc ? desc->name : "unknown";
    if (st->duration != AV_NOPTS_VALUE && st->duration > 0) {
        info.durationUs = av_rescale_q(st->duration, st->time_base, AVRational{1, 1000000});
    } else if (fmt->duration != AV_NOPTS_VALUE && fmt->duration > 0) {
        info.durationUs = av_rescale(fmt->duration, 1000000, AV_TIME_BASE);
    }
}

bool FfmpegDecoder::probe(const std::string& source, MediaInfo& out) {
    SourceFile file;
    if (!file.open(source)) return false;
    AVFormatContext* fmt = nullptr;
    if (!openFormat(file, &fmt)) return false;
    int idx = av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    fillInfo(fmt, idx, out);
    avformat_close_input(&fmt);
    return true;
}

bool FfmpegDecoder::open(const std::string& source, int outRate, int outChannels) {
    close();
    if (!mFile.open(source)) return false;
    if (!openFormat(mFile, &mFmt)) {
        close();
        return false;
    }
    const AVCodec* codec = nullptr;
    mStreamIndex = av_find_best_stream(mFmt, AVMEDIA_TYPE_AUDIO, -1, -1, &codec, 0);
    if (mStreamIndex < 0 || !codec) {
        LOGE("No decodable audio stream in '%s'", source.c_str());
        fillInfo(mFmt, -1, mInfo);
        close();
        return false;
    }
    fillInfo(mFmt, mStreamIndex, mInfo);
    // Ignore every other stream so the demuxer can skip video packets cheaply.
    for (unsigned i = 0; i < mFmt->nb_streams; ++i) {
        if (static_cast<int>(i) != mStreamIndex) mFmt->streams[i]->discard = AVDISCARD_ALL;
    }

    mCodec = avcodec_alloc_context3(codec);
    if (!mCodec) {
        close();
        return false;
    }
    AVStream* st = mFmt->streams[mStreamIndex];
    avcodec_parameters_to_context(mCodec, st->codecpar);
    mCodec->pkt_timebase = st->time_base;
    int err = avcodec_open2(mCodec, codec, nullptr);
    if (err < 0) {
        LOGE("avcodec_open2: %s", ffErr(err).c_str());
        close();
        return false;
    }
    mPacket = av_packet_alloc();
    mFrame = av_frame_alloc();
    if (!mPacket || !mFrame) {
        close();
        return false;
    }
    mStreamStartPts = (st->start_time != AV_NOPTS_VALUE) ? st->start_time : 0;

    const int srcRate = mCodec->sample_rate > 0 ? mCodec->sample_rate : mInfo.sampleRate;
    const int srcCh = std::max(1, mCodec->ch_layout.nb_channels);
    mOutRate = outRate > 0 ? outRate : srcRate;
    mOutChannels = outChannels > 0 ? std::min(outChannels, 2) : std::min(srcCh, 2);
    if (mOutRate <= 0) mOutRate = 48000;
    mFifo.reserve(8192 * mOutChannels);
    return true;
}

bool FfmpegDecoder::setupResampler(const AVFrame* frame) {
    const int inCh = frame->ch_layout.nb_channels;
    if (mSwr && frame->sample_rate == mSwrInRate && frame->format == mSwrInFormat &&
        inCh == mSwrInChannels) {
        return true;
    }
    if (mSwr) swr_free(&mSwr);

    AVChannelLayout inLayout;
    if (frame->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC || frame->ch_layout.nb_channels <= 0) {
        av_channel_layout_default(&inLayout, std::max(1, inCh));
    } else {
        av_channel_layout_copy(&inLayout, &frame->ch_layout);
    }
    AVChannelLayout outLayout;
    av_channel_layout_default(&outLayout, mOutChannels);

    int err = swr_alloc_set_opts2(&mSwr, &outLayout, AV_SAMPLE_FMT_FLT, mOutRate, &inLayout,
                                  static_cast<AVSampleFormat>(frame->format), frame->sample_rate, 0,
                                  nullptr);
    av_channel_layout_uninit(&inLayout);
    av_channel_layout_uninit(&outLayout);
    if (err < 0 || !mSwr) {
        LOGE("swr_alloc_set_opts2: %s", ffErr(err).c_str());
        return false;
    }
    // High quality resampling (Kaiser-windowed sinc) for studio export fidelity.
    av_opt_set_int(mSwr, "filter_size", 32, 0);
    av_opt_set_int(mSwr, "phase_shift", 10, 0);
    av_opt_set_double(mSwr, "cutoff", 0.97, 0);
    if ((err = swr_init(mSwr)) < 0) {
        LOGE("swr_init: %s", ffErr(err).c_str());
        swr_free(&mSwr);
        return false;
    }
    mSwrInRate = frame->sample_rate;
    mSwrInFormat = frame->format;
    mSwrInChannels = inCh;
    return true;
}

void FfmpegDecoder::appendConverted(const AVFrame* frame) {
    if (!setupResampler(frame)) return;
    const int maxOut = swr_get_out_samples(mSwr, frame->nb_samples) + 32;
    // Compact the FIFO before growing it.
    if (mFifoRead > 0) {
        mFifo.erase(mFifo.begin(), mFifo.begin() + static_cast<long>(mFifoRead));
        mFifoRead = 0;
    }
    const size_t oldSize = mFifo.size();
    mFifo.resize(oldSize + static_cast<size_t>(maxOut) * mOutChannels);
    uint8_t* outPtr = reinterpret_cast<uint8_t*>(mFifo.data() + oldSize);
    int got = swr_convert(mSwr, &outPtr, maxOut, const_cast<const uint8_t**>(frame->extended_data),
                          frame->nb_samples);
    if (got < 0) got = 0;
    mFifo.resize(oldSize + static_cast<size_t>(got) * mOutChannels);
}

void FfmpegDecoder::drainResampler() {
    if (!mSwr) return;
    for (;;) {
        const int maxOut = 4096;
        if (mFifoRead > 0) {
            mFifo.erase(mFifo.begin(), mFifo.begin() + static_cast<long>(mFifoRead));
            mFifoRead = 0;
        }
        const size_t oldSize = mFifo.size();
        mFifo.resize(oldSize + static_cast<size_t>(maxOut) * mOutChannels);
        uint8_t* outPtr = reinterpret_cast<uint8_t*>(mFifo.data() + oldSize);
        int got = swr_convert(mSwr, &outPtr, maxOut, nullptr, 0);
        if (got <= 0) {
            mFifo.resize(oldSize);
            break;
        }
        mFifo.resize(oldSize + static_cast<size_t>(got) * mOutChannels);
    }
}

bool FfmpegDecoder::decodeMore() {
    if (mEof || !mCodec) return false;
    for (;;) {
        int err = avcodec_receive_frame(mCodec, mFrame);
        if (err == 0) {
            const size_t before = mFifo.size() - mFifoRead;
            if (mAwaitFirstPts) {
                mAwaitFirstPts = false;
                int64_t pts = mFrame->best_effort_timestamp;
                if (pts == AV_NOPTS_VALUE) pts = mFrame->pts;
                if (pts != AV_NOPTS_VALUE) {
                    AVStream* st = mFmt->streams[mStreamIndex];
                    const int64_t posFrames =
                        av_rescale_q(pts - mStreamStartPts, st->time_base, AVRational{1, mOutRate});
                    mDiscardFrames = std::max<int64_t>(0, mSeekTargetFrames - posFrames);
                } else {
                    mDiscardFrames = 0;
                }
            }
            appendConverted(mFrame);
            av_frame_unref(mFrame);
            // Apply pending sample-accurate discard.
            if (mDiscardFrames > 0) {
                const size_t availFrames = (mFifo.size() - mFifoRead) / mOutChannels;
                const size_t drop = static_cast<size_t>(std::min<int64_t>(mDiscardFrames, availFrames));
                mFifoRead += drop * mOutChannels;
                mDiscardFrames -= static_cast<int64_t>(drop);
            }
            if (mFifo.size() - mFifoRead > 0 || mFifo.size() - mFifoRead != before) return true;
            continue;
        }
        if (err == AVERROR_EOF) {
            drainResampler();
            mEof = true;
            return mFifo.size() > mFifoRead;
        }
        if (err != AVERROR(EAGAIN)) {
            LOGW("avcodec_receive_frame: %s", ffErr(err).c_str());
            // Treat as recoverable: try feeding more input.
        }
        if (mInputDrained) {
            // Decoder has no more frames and input is exhausted.
            drainResampler();
            mEof = true;
            return mFifo.size() > mFifoRead;
        }
        // Feed the next packet of our audio stream.
        for (;;) {
            err = av_read_frame(mFmt, mPacket);
            if (err < 0) {
                avcodec_send_packet(mCodec, nullptr);  // enter draining mode
                mInputDrained = true;
                break;
            }
            if (mPacket->stream_index != mStreamIndex) {
                av_packet_unref(mPacket);
                continue;
            }
            err = avcodec_send_packet(mCodec, mPacket);
            av_packet_unref(mPacket);
            if (err < 0 && err != AVERROR(EAGAIN)) {
                LOGW("avcodec_send_packet: %s (skipping corrupt packet)", ffErr(err).c_str());
                continue;
            }
            break;
        }
    }
}

int FfmpegDecoder::read(float* dst, int frames) {
    int written = 0;
    while (written < frames) {
        size_t availSamples = mFifo.size() - mFifoRead;
        if (availSamples == 0) {
            if (!decodeMore()) break;
            continue;
        }
        const size_t availFrames = availSamples / mOutChannels;
        const size_t n = std::min<size_t>(availFrames, static_cast<size_t>(frames - written));
        std::memcpy(dst + static_cast<size_t>(written) * mOutChannels, mFifo.data() + mFifoRead,
                    n * mOutChannels * sizeof(float));
        mFifoRead += n * mOutChannels;
        written += static_cast<int>(n);
        if (mFifoRead == mFifo.size()) {
            mFifo.clear();
            mFifoRead = 0;
        }
    }
    return written;
}

bool FfmpegDecoder::seekUs(int64_t positionUs) {
    if (!mFmt || !mCodec) return false;
    if (positionUs < 0) positionUs = 0;
    AVStream* st = mFmt->streams[mStreamIndex];
    const int64_t ts = mStreamStartPts + av_rescale_q(positionUs, AVRational{1, 1000000}, st->time_base);
    int err = av_seek_frame(mFmt, mStreamIndex, ts, AVSEEK_FLAG_BACKWARD);
    if (err < 0) {
        // Fall back to a byte/any seek through the generic API.
        err = avformat_seek_file(mFmt, mStreamIndex, INT64_MIN, ts, ts, 0);
    }
    if (err < 0) {
        LOGW("seek to %lld us failed: %s", static_cast<long long>(positionUs), ffErr(err).c_str());
        return false;
    }
    avcodec_flush_buffers(mCodec);
    if (mSwr) swr_free(&mSwr);  // drop resampler history so no pre-seek samples leak through
    mSwrInRate = 0;
    mSwrInFormat = -1;
    mSwrInChannels = 0;
    mFifo.clear();
    mFifoRead = 0;
    mEof = false;
    mInputDrained = false;
    mSeekTargetFrames = av_rescale(positionUs, mOutRate, 1000000);
    mDiscardFrames = 0;
    mAwaitFirstPts = true;
    return true;
}

}  // namespace tfx
