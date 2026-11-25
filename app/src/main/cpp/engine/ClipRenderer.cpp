#include "engine/ClipRenderer.h"
#include "common/Log.h"

#include <rubberband/RubberBandStretcher.h>
#include <algorithm>
#include <cmath>
#include <cstring>

namespace tfx {

using RB = RubberBand::RubberBandStretcher;
static constexpr int kMaxChunk = 4096;

ClipRenderer::ClipRenderer(const ClipDesc& desc, int sampleRate, bool highQuality)
    : mDesc(desc), mRate(sampleRate), mHighQuality(highQuality) {
    computeBounds();
    mDsp.prepare(sampleRate);
    mDsp.setParams(desc.dsp);
    mGain = std::pow(10.f, desc.gainDb / 20.f);
    mScratch.resize(kMaxChunk * 2);
    mScratch2.resize(kMaxChunk * 2);
}

ClipRenderer::~ClipRenderer() { release(); }

void ClipRenderer::computeBounds() {
    mStart = mDesc.timelineStartUs * mRate / 1000000;
    const int64_t len = mDesc.timelineLengthUs() * mRate / 1000000;
    mEnd = mStart + std::max<int64_t>(0, len);
    // Allow the reverb tail to ring out after the clip ends.
    mTail = mDesc.dsp.hasReverb() ? static_cast<int64_t>(mRate) * 3 : 0;
}

bool ClipRenderer::sameStructure(const ClipDesc& o) const {
    return o.source == mDesc.source && o.sourceStartUs == mDesc.sourceStartUs &&
           o.sourceEndUs == mDesc.sourceEndUs && o.timelineStartUs == mDesc.timelineStartUs &&
           std::fabs(o.dsp.speed - mDesc.dsp.speed) < 1e-4f &&
           o.dsp.hasTimePitch() == mDesc.dsp.hasTimePitch() &&
           o.dsp.denoiseMode == mDesc.dsp.denoiseMode &&
           o.dsp.hasReverb() == mDesc.dsp.hasReverb();
}

void ClipRenderer::updateParams(const ClipDesc& d) {
    const float oldPitch = mDesc.dsp.pitchSemitones;
    const bool oldFormant = mDesc.dsp.preserveFormants;
    mDesc.gainDb = d.gainDb;
    mDesc.fadeInUs = d.fadeInUs;
    mDesc.fadeOutUs = d.fadeOutUs;
    mDesc.equalPowerFade = d.equalPowerFade;
    mDesc.dsp = d.dsp;
    mGain = std::pow(10.f, d.gainDb / 20.f);
    mDsp.setParams(d.dsp);
    if (mDenoiser) mDenoiser->setStrength(d.dsp.bypass ? 0.f : d.dsp.denoiseStrength);
    if (mStretcher) {
        if (oldPitch != d.dsp.pitchSemitones) mStretcher->setPitchScale(std::pow(2.0, d.dsp.pitchSemitones / 12.0));
        if (oldFormant != d.dsp.preserveFormants)
            mStretcher->setFormantOption(d.dsp.preserveFormants ? RB::OptionFormantPreserved : RB::OptionFormantShifted);
    }
}

void ClipRenderer::release() {
    mDecoder.close();
    mStretcher.reset();
    mDenoiser.reset();
    mOpen = false;
    mNextFrame = -1;
}

void ClipRenderer::setupStretcher() {
    if (!mDesc.dsp.hasTimePitch()) {
        mStretcher.reset();
        return;
    }
    RB::Options opts = RB::OptionProcessRealTime | RB::OptionChannelsTogether |
                       RB::OptionPitchHighConsistency |
                       (mHighQuality ? RB::OptionEngineFiner : RB::OptionEngineFaster) |
                       (mDesc.dsp.preserveFormants ? RB::OptionFormantPreserved : RB::OptionFormantShifted);
    const double ratio = 1.0 / std::max(0.25f, mDesc.dsp.speed);
    const double pitch = std::pow(2.0, mDesc.dsp.pitchSemitones / 12.0);
    mStretcher = std::make_unique<RB>(static_cast<size_t>(mRate), 2, opts, ratio, pitch);
    mStretcher->setMaxProcessSize(kMaxChunk);
    for (auto& v : mPlanarIn) v.assign(kMaxChunk, 0.f);
    for (auto& v : mPlanarOut) v.assign(kMaxChunk, 0.f);
    // Prime with the preferred start pad and drop the corresponding start delay so the
    // stretched output stays time-aligned with the timeline.
    size_t pad = mStretcher->getPreferredStartPad();
    while (pad > 0) {
        const size_t n = std::min<size_t>(pad, kMaxChunk);
        std::fill(mPlanarIn[0].begin(), mPlanarIn[0].begin() + n, 0.f);
        std::fill(mPlanarIn[1].begin(), mPlanarIn[1].begin() + n, 0.f);
        const float* in[2] = {mPlanarIn[0].data(), mPlanarIn[1].data()};
        mStretcher->process(in, n, false);
        pad -= n;
    }
    mStretchDiscard = static_cast<int>(mStretcher->getStartDelay());
    mStretchFinal = false;
}

bool ClipRenderer::reposition(int64_t t) {
    if (mOpenFailed) return false;
    if (!mOpen) {
        if (!mDecoder.open(mDesc.source, mRate, 2)) {
            LOGE("Clip %lld: cannot open source", static_cast<long long>(mDesc.id));
            mOpenFailed = true;
            return false;
        }
        mOpen = true;
        if (mDesc.dsp.denoiseMode != "off") {
            mDenoiser = createDenoiser(mDesc.dsp.denoiseMode, mRate);
            if (mDenoiser) mDenoiser->setStrength(mDesc.dsp.bypass ? 0.f : mDesc.dsp.denoiseStrength);
        }
    }
    const int64_t local = std::max<int64_t>(0, t - mStart);
    const double speed = std::max(0.25f, mDesc.dsp.speed);
    const int64_t srcOffset = static_cast<int64_t>(local * speed);
    const int64_t srcTotal = mDesc.sourceLengthUs() * mRate / 1000000;
    mSrcFramesLeft = std::max<int64_t>(0, srcTotal - srcOffset);
    mDecoder.seekUs(mDesc.sourceStartUs + srcOffset * 1000000 / mRate);
    setupStretcher();
    mDsp.reset();
    if (mDenoiser) {
        mDenoiser->reset();
        mPendingDenoiseDiscard = mDenoiser->latencyFrames();
        mDenoiseFlushLeft = mDenoiser->latencyFrames();
    }
    mNextFrame = t;
    return true;
}

int ClipRenderer::readDecoded(float* dst, int frames) {
    int real = 0;
    if (mSrcFramesLeft > 0) {
        const int want = static_cast<int>(std::min<int64_t>(frames, mSrcFramesLeft));
        real = mDecoder.read(dst, want);
        if (real < want) mSrcFramesLeft = 0;  // source ended earlier than its reported duration
        else mSrcFramesLeft -= real;
    }
    if (real < frames) std::memset(dst + real * 2, 0, sizeof(float) * 2 * (frames - real));
    if (mDenoiser) {
        // Zero frames after the end push the denoiser's internal latency out.
        if (mSrcFramesLeft == 0 && mDenoiseFlushLeft > 0) {
            const int f = std::min(frames - real, mDenoiseFlushLeft);
            real += f;
            mDenoiseFlushLeft -= f;
        }
        mDenoiser->process(dst, frames);
        if (mPendingDenoiseDiscard > 0 && real > 0) {
            const int d = std::min(real, mPendingDenoiseDiscard);
            std::memmove(dst, dst + d * 2, sizeof(float) * 2 * (frames - d));
            std::memset(dst + (frames - d) * 2, 0, sizeof(float) * 2 * d);
            real -= d;
            mPendingDenoiseDiscard -= d;
        }
    }
    return real;
}

int ClipRenderer::pullSource(float* dst, int frames) {
    int produced = 0;
    if (!mStretcher) {
        int guard = 0;
        while (produced < frames) {
            const int got = readDecoded(dst + produced * 2, frames - produced);
            produced += got;
            if (got == 0 && ++guard > 2) break;
        }
        if (produced < frames) std::memset(dst + produced * 2, 0, sizeof(float) * 2 * (frames - produced));
        return frames;
    }
    int guard = 0;
    while (produced < frames) {
        const int avail = mStretcher->available();
        if (avail > 0) {
            if (mStretchDiscard > 0) {
                const int d = std::min({avail, mStretchDiscard, kMaxChunk});
                float* out[2] = {mPlanarOut[0].data(), mPlanarOut[1].data()};
                mStretcher->retrieve(out, d);
                mStretchDiscard -= d;
                continue;
            }
            const int n = std::min({avail, frames - produced, kMaxChunk});
            float* out[2] = {mPlanarOut[0].data(), mPlanarOut[1].data()};
            const size_t got = mStretcher->retrieve(out, n);
            for (size_t i = 0; i < got; ++i) {
                dst[(produced + i) * 2] = mPlanarOut[0][i];
                dst[(produced + i) * 2 + 1] = mPlanarOut[1][i];
            }
            produced += static_cast<int>(got);
            continue;
        }
        if (avail < 0 || mStretchFinal) break;
        size_t req = mStretcher->getSamplesRequired();
        if (req == 0) req = 512;
        req = std::min<size_t>(req, kMaxChunk);
        const int got = readDecoded(mScratch2.data(), static_cast<int>(req));
        for (int i = 0; i < got; ++i) {
            mPlanarIn[0][i] = mScratch2[i * 2];
            mPlanarIn[1][i] = mScratch2[i * 2 + 1];
        }
        const float* in[2] = {mPlanarIn[0].data(), mPlanarIn[1].data()};
        const bool final = got == 0 || (mSrcFramesLeft == 0 && !mDenoiser);
        mStretcher->process(in, static_cast<size_t>(got), final);
        if (final) mStretchFinal = true;
        if (got == 0 && ++guard > 4) break;
    }
    if (produced < frames) std::memset(dst + produced * 2, 0, sizeof(float) * 2 * (frames - produced));
    return frames;
}

float ClipRenderer::envelopeAt(int64_t local) const {
    const int64_t len = mEnd - mStart;
    if (local < 0 || local >= len) return 0.f;
    float g = 1.f;
    const int64_t fi = mDesc.fadeInUs * mRate / 1000000;
    const int64_t fo = mDesc.fadeOutUs * mRate / 1000000;
    if (fi > 0 && local < fi) {
        const float x = static_cast<float>(local) / static_cast<float>(fi);
        g *= mDesc.equalPowerFade ? std::sin(x * static_cast<float>(M_PI_2)) : x;
    }
    const int64_t rem = len - local;
    if (fo > 0 && rem < fo) {
        const float x = static_cast<float>(rem) / static_cast<float>(fo);
        g *= mDesc.equalPowerFade ? std::sin(x * static_cast<float>(M_PI_2)) : x;
    }
    return g;
}

void ClipRenderer::prefetch(int64_t t) {
    if (mOpen || mOpenFailed) return;
    reposition(std::max(t, mStart));
}

void ClipRenderer::mixInto(float* out, int64_t t0, int frames, float extraGain) {
    int64_t s = std::max(t0, mStart);
    const int64_t e = std::min(t0 + frames, tailEndFrame());
    if (s >= e) return;
    if (!mOpen || mNextFrame != s) {
        if (!reposition(s)) return;
    }
    while (s < e) {
        const int n = static_cast<int>(std::min<int64_t>(e - s, kMaxChunk));
        float* buf = mScratch.data();
        pullSource(buf, n);
        const int64_t local0 = s - mStart;
        for (int i = 0; i < n; ++i) {
            const float g = mGain * envelopeAt(local0 + i);
            buf[2 * i] *= g;
            buf[2 * i + 1] *= g;
        }
        mDsp.process(buf, n);
        float* dst = out + (s - t0) * 2;
        for (int i = 0; i < n * 2; ++i) dst[i] += buf[i] * extraGain;
        s += n;
    }
    mNextFrame = e;
}

}  // namespace tfx
