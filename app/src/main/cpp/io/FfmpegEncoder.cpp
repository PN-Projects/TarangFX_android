#include <functional>
#include "io/FfmpegEncoder.h"
#include "io/SourceFile.h"
#include "common/Log.h"

#include <algorithm>
#include <cstdio>
#include <unistd.h>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswresample/swresample.h>
#include <libavutil/audio_fifo.h>
#include <libavutil/channel_layout.h>
#include <libavutil/opt.h>
}

namespace tfx {

static std::string ffErr(int err) {
    char buf[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(err, buf, sizeof(buf));
    return buf;
}

struct CodecChoice {
    const char* muxer;
    const char* encoder;
};

static CodecChoice choose(const std::string& format, int bitDepth) {
    if (format == "mp3") return {"mp3", "libmp3lame"};
    if (format == "aac") return {"adts", "aac"};
    if (format == "m4a") return {"ipod", "aac"};
    if (format == "flac") return {"flac", "flac"};
    if (format == "opus") return {"opus", "libopus"};
    if (format == "ogg") return {"ogg", "libvorbis"};
    if (format == "alac") return {"ipod", "alac"};
    // wav
    if (bitDepth >= 32) return {"wav", "pcm_f32le"};
    if (bitDepth == 24) return {"wav", "pcm_s24le"};
    return {"wav", "pcm_s16le"};
}

int FfmpegEncoder::resolveSampleRate(const std::string& format, int requested) {
    if (requested <= 0) requested = 48000;
    if (format == "opus") {
        // libopus only accepts 8/12/16/24/48 kHz.
        for (int r : {8000, 12000, 16000, 24000, 48000}) if (requested <= r) return r;
        return 48000;
    }
    if (format == "mp3") {
        for (int r : {8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000}) if (requested <= r) return r;
        return 48000;
    }
    if (format == "aac" || format == "m4a" || format == "ogg") {
        return std::min(requested, 96000);
    }
    return std::min(requested, 192000);
}

static AVSampleFormat pickSampleFormat(const AVCodec* codec, const std::string& format, int bitDepth) {
    const AVSampleFormat* fmts = nullptr;
    int count = 0;
#if LIBAVCODEC_VERSION_INT >= AV_VERSION_INT(61, 13, 100)
    avcodec_get_supported_config(nullptr, codec, AV_CODEC_CONFIG_SAMPLE_FORMAT, 0,
                                 reinterpret_cast<const void**>(&fmts), &count);
#else
    fmts = codec->sample_fmts;
    if (fmts) while (fmts[count] != AV_SAMPLE_FMT_NONE) ++count;
#endif
    if (!fmts || count == 0) return AV_SAMPLE_FMT_FLTP;
    const bool wantHighDepth = bitDepth >= 24 && (format == "flac" || format == "alac");
    const AVSampleFormat preferred[] = {
        wantHighDepth ? AV_SAMPLE_FMT_S32 : AV_SAMPLE_FMT_FLTP,
        wantHighDepth ? AV_SAMPLE_FMT_S32P : AV_SAMPLE_FMT_FLT,
        AV_SAMPLE_FMT_FLTP, AV_SAMPLE_FMT_FLT, AV_SAMPLE_FMT_S16P, AV_SAMPLE_FMT_S16,
        AV_SAMPLE_FMT_S32P, AV_SAMPLE_FMT_S32};
    for (AVSampleFormat p : preferred)
        for (int i = 0; i < count; ++i)
            if (fmts[i] == p) return p;
    return fmts[0];
}

FfmpegEncoder::~FfmpegEncoder() {
    if (!mFinished) abort();
    cleanup();
}

void FfmpegEncoder::cleanup() {
    if (mFifo) { av_audio_fifo_free(mFifo); mFifo = nullptr; }
    if (mSwr) swr_free(&mSwr);
    if (mFrame) av_frame_free(&mFrame);
    if (mPacket) av_packet_free(&mPacket);
    if (mCodec) avcodec_free_context(&mCodec);
    if (mFmt) {
        if (mFmt->pb) avio_closep(&mFmt->pb);
        avformat_free_context(mFmt);
        mFmt = nullptr;
    }
}

bool FfmpegEncoder::open(const std::string& path, const EncoderConfig& cfg, int inputRate,
                         int inputChannels) {
    cleanup();
    mPath = path;
    mFinished = false;
    mHeaderWritten = false;
    mPts = 0;
    mInRate = inputRate;
    mInChannels = inputChannels;
    mOutChannels = cfg.channels > 0 ? std::min(cfg.channels, 2) : std::min(inputChannels, 2);
    mOutRate = resolveSampleRate(cfg.format, cfg.sampleRate > 0 ? cfg.sampleRate : inputRate);

    const CodecChoice choice = choose(cfg.format, cfg.bitDepth);
    const AVCodec* codec = avcodec_find_encoder_by_name(choice.encoder);
    if (!codec) {
        mError = std::string("Encoder not available: ") + choice.encoder;
        return false;
    }
    int err = avformat_alloc_output_context2(&mFmt, nullptr, choice.muxer, path.c_str());
    if (err < 0 || !mFmt) {
        mError = "Muxer not available: " + std::string(choice.muxer) + " " + ffErr(err);
        return false;
    }
    mStream = avformat_new_stream(mFmt, nullptr);
    mCodec = avcodec_alloc_context3(codec);
    if (!mStream || !mCodec) {
        mError = "Out of memory";
        return false;
    }
    mCodec->sample_fmt = pickSampleFormat(codec, cfg.format, cfg.bitDepth);
    mCodec->sample_rate = mOutRate;
    av_channel_layout_default(&mCodec->ch_layout, mOutChannels);
    mCodec->time_base = AVRational{1, mOutRate};
    if (cfg.format == "flac" || cfg.format == "alac") {
        mCodec->bits_per_raw_sample = cfg.bitDepth >= 24 ? 24 : 16;
    }
    const bool lossy = cfg.format == "mp3" || cfg.format == "aac" || cfg.format == "m4a" ||
                       cfg.format == "opus" || cfg.format == "ogg";
    if (lossy) {
        const int kbps = std::clamp(cfg.bitrateKbps, 32, 320);
        if (cfg.vbr && (cfg.format == "mp3" || cfg.format == "ogg")) {
            mCodec->flags |= AV_CODEC_FLAG_QSCALE;
            // Map target kbps to codec VBR quality scale.
            float q;
            if (cfg.format == "mp3") {  // LAME V0 (~245k) .. V9 (~65k): lower is better
                q = std::clamp(9.0f - (kbps - 64) / 28.0f, 0.0f, 9.0f);
            } else {  // Vorbis q -1..10: higher is better
                q = std::clamp((kbps - 64) / 25.6f, 0.0f, 10.0f);
            }
            mCodec->global_quality = static_cast<int>(q * FF_QP2LAMBDA);
        } else {
            mCodec->bit_rate = static_cast<int64_t>(kbps) * 1000 * (mOutChannels == 1 && cfg.format != "mp3" ? 1 : 1);
            if (cfg.format == "opus") {
                av_opt_set(mCodec->priv_data, "vbr", cfg.vbr ? "on" : "off", 0);
            }
        }
    }
    if (mFmt->oformat->flags & AVFMT_GLOBALHEADER) mCodec->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    if (std::string(choice.encoder) == "aac") {
        mCodec->profile = AV_PROFILE_AAC_LOW;
    }

    err = avcodec_open2(mCodec, codec, nullptr);
    if (err < 0) {
        mError = "Cannot open encoder " + std::string(choice.encoder) + ": " + ffErr(err);
        return false;
    }
    avcodec_parameters_from_context(mStream->codecpar, mCodec);
    mStream->time_base = mCodec->time_base;

    for (const auto& [k, v] : cfg.metadata) {
        if (!v.empty()) av_dict_set(&mFmt->metadata, k.c_str(), v.c_str(), 0);
    }
    av_dict_set(&mFmt->metadata, "encoder", "TarangFX", 0);

    if (!(mFmt->oformat->flags & AVFMT_NOFILE)) {
        err = avio_open(&mFmt->pb, path.c_str(), AVIO_FLAG_WRITE);
        if (err < 0) {
            mError = "Cannot create output file: " + ffErr(err);
            return false;
        }
    }
    err = avformat_write_header(mFmt, nullptr);
    if (err < 0) {
        mError = "Cannot write header: " + ffErr(err);
        return false;
    }
    mHeaderWritten = true;

    // Resampler: interleaved float input -> codec sample format.
    AVChannelLayout inLayout, outLayout;
    av_channel_layout_default(&inLayout, mInChannels);
    av_channel_layout_default(&outLayout, mOutChannels);
    err = swr_alloc_set_opts2(&mSwr, &outLayout, mCodec->sample_fmt, mOutRate, &inLayout,
                              AV_SAMPLE_FMT_FLT, mInRate, 0, nullptr);
    av_channel_layout_uninit(&inLayout);
    av_channel_layout_uninit(&outLayout);
    if (err < 0 || !mSwr) {
        mError = "Cannot create resampler";
        return false;
    }
    av_opt_set_int(mSwr, "filter_size", 32, 0);
    av_opt_set_int(mSwr, "phase_shift", 10, 0);
    // TPDF dither when reducing to 16-bit integer formats.
    if (mCodec->sample_fmt == AV_SAMPLE_FMT_S16 || mCodec->sample_fmt == AV_SAMPLE_FMT_S16P) {
        av_opt_set(mSwr, "dither_method", "triangular_hp", 0);
    }
    if ((err = swr_init(mSwr)) < 0) {
        mError = "swr_init failed: " + ffErr(err);
        return false;
    }

    mFrameSize = (codec->capabilities & AV_CODEC_CAP_VARIABLE_FRAME_SIZE) || mCodec->frame_size <= 0
                     ? 4096
                     : mCodec->frame_size;
    mFifo = av_audio_fifo_alloc(mCodec->sample_fmt, mOutChannels, mFrameSize * 4);
    mFrame = av_frame_alloc();
    mPacket = av_packet_alloc();
    if (!mFifo || !mFrame || !mPacket) {
        mError = "Out of memory";
        return false;
    }
    return true;
}

bool FfmpegEncoder::sendFrame(AVFrame* frame) {
    int err = avcodec_send_frame(mCodec, frame);
    if (err < 0 && err != AVERROR_EOF) {
        mError = "avcodec_send_frame: " + ffErr(err);
        return false;
    }
    for (;;) {
        err = avcodec_receive_packet(mCodec, mPacket);
        if (err == AVERROR(EAGAIN) || err == AVERROR_EOF) break;
        if (err < 0) {
            mError = "avcodec_receive_packet: " + ffErr(err);
            return false;
        }
        av_packet_rescale_ts(mPacket, mCodec->time_base, mStream->time_base);
        mPacket->stream_index = mStream->index;
        err = av_interleaved_write_frame(mFmt, mPacket);
        av_packet_unref(mPacket);
        if (err < 0) {
            mError = "write_frame: " + ffErr(err);
            return false;
        }
    }
    return true;
}

bool FfmpegEncoder::encodeFromFifo(bool flush) {
    while (av_audio_fifo_size(mFifo) >= mFrameSize || (flush && av_audio_fifo_size(mFifo) > 0)) {
        const int n = std::min(av_audio_fifo_size(mFifo), mFrameSize);
        av_frame_unref(mFrame);
        mFrame->nb_samples = n;
        mFrame->format = mCodec->sample_fmt;
        mFrame->sample_rate = mOutRate;
        av_channel_layout_copy(&mFrame->ch_layout, &mCodec->ch_layout);
        if (av_frame_get_buffer(mFrame, 0) < 0) {
            mError = "frame alloc failed";
            return false;
        }
        av_audio_fifo_read(mFifo, reinterpret_cast<void**>(mFrame->data), n);
        mFrame->pts = mPts;
        mPts += n;
        if (!sendFrame(mFrame)) return false;
    }
    return true;
}

bool FfmpegEncoder::write(const float* interleaved, int frames) {
    if (!mHeaderWritten || frames <= 0) return frames == 0;
    const int maxOut = swr_get_out_samples(mSwr, frames) + 64;
    uint8_t** buf = nullptr;
    int lines = 0;
    if (av_samples_alloc_array_and_samples(&buf, &lines, mOutChannels, maxOut, mCodec->sample_fmt, 0) < 0) {
        mError = "sample alloc failed";
        return false;
    }
    const uint8_t* in[1] = {reinterpret_cast<const uint8_t*>(interleaved)};
    int got = swr_convert(mSwr, buf, maxOut, in, frames);
    bool ok = true;
    if (got > 0) ok = av_audio_fifo_write(mFifo, reinterpret_cast<void**>(buf), got) >= got;
    av_freep(&buf[0]);
    av_freep(&buf);
    return ok && encodeFromFifo(false);
}

bool FfmpegEncoder::finish() {
    if (!mHeaderWritten || mFinished) return mFinished;
    // Drain resampler tail.
    for (;;) {
        uint8_t** buf = nullptr;
        int lines = 0;
        const int maxOut = 4096;
        if (av_samples_alloc_array_and_samples(&buf, &lines, mOutChannels, maxOut, mCodec->sample_fmt, 0) < 0) break;
        int got = swr_convert(mSwr, buf, maxOut, nullptr, 0);
        if (got > 0) av_audio_fifo_write(mFifo, reinterpret_cast<void**>(buf), got);
        av_freep(&buf[0]);
        av_freep(&buf);
        if (got <= 0) break;
    }
    bool ok = encodeFromFifo(true) && sendFrame(nullptr);
    int err = av_write_trailer(mFmt);
    if (err < 0) {
        mError = "write_trailer: " + ffErr(err);
        ok = false;
    }
    mFinished = true;
    cleanup();
    return ok;
}

void FfmpegEncoder::abort() {
    mFinished = true;
    cleanup();
    if (!mPath.empty()) unlink(mPath.c_str());
}

// ---------------------------------------------------------------------------------------------
// Stream copy (Video -> Audio without re-encoding)
// ---------------------------------------------------------------------------------------------

RemuxPlan planAudioRemux(const std::string& source) {
    RemuxPlan plan;
    SourceFile file;
    if (!file.open(source)) return plan;
    AVFormatContext* fmt = avformat_alloc_context();
    fmt->pb = file.avio();
    fmt->flags |= AVFMT_FLAG_CUSTOM_IO;
    if (avformat_open_input(&fmt, nullptr, nullptr, nullptr) < 0) return plan;
    avformat_find_stream_info(fmt, nullptr);
    int idx = av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (idx >= 0) {
        AVCodecID id = fmt->streams[idx]->codecpar->codec_id;
        const AVCodecDescriptor* d = avcodec_descriptor_get(id);
        plan.codec = d ? d->name : "";
        switch (id) {
            case AV_CODEC_ID_AAC: plan.muxer = "ipod"; plan.extension = "m4a"; break;
            case AV_CODEC_ID_ALAC: plan.muxer = "ipod"; plan.extension = "m4a"; break;
            case AV_CODEC_ID_MP3: plan.muxer = "mp3"; plan.extension = "mp3"; break;
            case AV_CODEC_ID_OPUS: plan.muxer = "opus"; plan.extension = "opus"; break;
            case AV_CODEC_ID_VORBIS: plan.muxer = "ogg"; plan.extension = "ogg"; break;
            case AV_CODEC_ID_FLAC: plan.muxer = "flac"; plan.extension = "flac"; break;
            case AV_CODEC_ID_PCM_S16LE:
            case AV_CODEC_ID_PCM_S24LE:
            case AV_CODEC_ID_PCM_F32LE: plan.muxer = "wav"; plan.extension = "wav"; break;
            default: plan.muxer = "matroska"; plan.extension = "mka"; break;  // AC3/EAC3/etc.
        }
    }
    avformat_close_input(&fmt);
    return plan;
}

bool remuxAudio(const std::string& source, const std::string& outPath, const std::string& muxer,
                const std::function<bool(float)>& progress, std::string& error) {
    SourceFile file;
    if (!file.open(source)) { error = "Cannot open input"; return false; }
    AVFormatContext* in = avformat_alloc_context();
    in->pb = file.avio();
    in->flags |= AVFMT_FLAG_CUSTOM_IO;
    int err = avformat_open_input(&in, nullptr, nullptr, nullptr);
    if (err < 0) { error = "Cannot read input: " + ffErr(err); return false; }
    avformat_find_stream_info(in, nullptr);
    int idx = av_find_best_stream(in, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (idx < 0) { avformat_close_input(&in); error = "No audio stream found"; return false; }
    AVStream* ist = in->streams[idx];

    AVFormatContext* out = nullptr;
    avformat_alloc_output_context2(&out, nullptr, muxer.c_str(), outPath.c_str());
    if (!out) { avformat_close_input(&in); error = "Muxer unavailable: " + muxer; return false; }
    AVStream* ost = avformat_new_stream(out, nullptr);
    avcodec_parameters_copy(ost->codecpar, ist->codecpar);
    ost->codecpar->codec_tag = 0;
    ost->time_base = ist->time_base;
    av_dict_copy(&out->metadata, in->metadata, 0);

    bool ok = false;
    AVPacket* pkt = av_packet_alloc();
    const int64_t durTs = ist->duration > 0 ? ist->duration
                          : av_rescale_q(in->duration, AVRational{1, AV_TIME_BASE}, ist->time_base);
    const int64_t startTs = ist->start_time != AV_NOPTS_VALUE ? ist->start_time : 0;
    if ((err = avio_open(&out->pb, outPath.c_str(), AVIO_FLAG_WRITE)) < 0) {
        error = "Cannot create output: " + ffErr(err);
    } else if ((err = avformat_write_header(out, nullptr)) < 0) {
        error = "Cannot write header: " + ffErr(err);
    } else {
        ok = true;
        while (av_read_frame(in, pkt) >= 0) {
            if (pkt->stream_index != idx) { av_packet_unref(pkt); continue; }
            if (progress && durTs > 0 && pkt->pts != AV_NOPTS_VALUE) {
                if (!progress(std::clamp(float(pkt->pts - startTs) / float(durTs), 0.0f, 1.0f))) {
                    error = "Cancelled";
                    ok = false;
                    av_packet_unref(pkt);
                    break;
                }
            }
            if (pkt->pts != AV_NOPTS_VALUE) pkt->pts -= startTs;
            if (pkt->dts != AV_NOPTS_VALUE) pkt->dts -= startTs;
            av_packet_rescale_ts(pkt, ist->time_base, ost->time_base);
            pkt->stream_index = 0;
            pkt->pos = -1;
            if ((err = av_interleaved_write_frame(out, pkt)) < 0) {
                error = "Write failed: " + ffErr(err);
                ok = false;
                break;
            }
        }
        if (av_write_trailer(out) < 0) ok = false;
    }
    av_packet_free(&pkt);
    if (out->pb) avio_closep(&out->pb);
    avformat_free_context(out);
    avformat_close_input(&in);
    if (!ok) unlink(outPath.c_str());
    else if (progress) progress(1.0f);
    return ok;
}

}  // namespace tfx
