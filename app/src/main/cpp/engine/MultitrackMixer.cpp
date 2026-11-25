#include "engine/MultitrackMixer.h"
#include <algorithm>
#include <cmath>
#include <cstring>

namespace tfx {

MultitrackMixer::MultitrackMixer(int sampleRate, bool highQuality)
    : mRate(sampleRate), mHighQuality(highQuality) {
    mMaster.prepare(sampleRate);
    mLimiter.prepare(sampleRate, -0.3f);
}

void MultitrackMixer::setProject(const ProjectDesc& project) {
    // Index existing renderers so unchanged clips keep their open decoders (no glitch on edits).
    std::vector<std::unique_ptr<ClipRenderer>> pool;
    for (auto& t : mTracks)
        for (auto& c : t.clips) pool.push_back(std::move(c));

    std::vector<Track> tracks;
    mAnySolo = false;
    for (const auto& td : project.tracks) {
        Track t;
        t.desc = td;
        t.gain = std::pow(10.f, td.gainDb / 20.f);
        mAnySolo |= td.solo;
        for (const auto& cd : td.clips) {
            std::unique_ptr<ClipRenderer> r;
            for (auto& p : pool) {
                if (p && p->id() == cd.id && p->sameStructure(cd)) {
                    r = std::move(p);
                    r->updateParams(cd);
                    break;
                }
            }
            if (!r) r = std::make_unique<ClipRenderer>(cd, mRate, mHighQuality);
            t.clips.push_back(std::move(r));
        }
        tracks.push_back(std::move(t));
    }
    mTracks = std::move(tracks);
    mMaster.setParams(project.master);
    recomputeLength();
}

void MultitrackMixer::updateClip(const ClipDesc& clip) {
    for (auto& t : mTracks)
        for (auto& c : t.clips)
            if (c->id() == clip.id) {
                c->updateParams(clip);
                return;
            }
}

void MultitrackMixer::setMasterDsp(const DspParams& p) { mMaster.setParams(p); }

void MultitrackMixer::setTrackState(int64_t trackId, float gainDb, bool muted, bool solo) {
    mAnySolo = false;
    for (auto& t : mTracks) {
        if (t.desc.id == trackId) {
            t.desc.gainDb = gainDb;
            t.desc.muted = muted;
            t.desc.solo = solo;
            t.gain = std::pow(10.f, gainDb / 20.f);
        }
        mAnySolo |= t.desc.solo;
    }
}

void MultitrackMixer::recomputeLength() {
    int64_t len = 0;
    for (auto& t : mTracks)
        for (auto& c : t.clips) len = std::max(len, c->endFrame());
    mLength = len;
}

void MultitrackMixer::seek(int64_t frame) {
    mPos = std::max<int64_t>(0, frame);
    mMaster.reset();
    mLimiter.reset();
}

void MultitrackMixer::manageResources() {
    const int64_t ahead = static_cast<int64_t>(mRate) * 3 / 2;   // pre-open 1.5 s ahead
    const int64_t behind = static_cast<int64_t>(mRate) * 2;
    for (auto& t : mTracks) {
        for (auto& c : t.clips) {
            if (c->tailEndFrame() + behind < mPos || c->startFrame() > mPos + ahead * 8) {
                if (c->isOpen()) c->release();
            } else if (!c->isOpen() && c->startFrame() > mPos && c->startFrame() <= mPos + ahead) {
                c->prefetch(c->startFrame());
            }
        }
    }
}

void MultitrackMixer::render(float* out, int frames) {
    std::memset(out, 0, sizeof(float) * 2 * frames);
    for (auto& t : mTracks) {
        const bool audible = !t.desc.muted && (!mAnySolo || t.desc.solo);
        for (auto& c : t.clips) {
            if (c->tailEndFrame() <= mPos || c->startFrame() >= mPos + frames) continue;
            // Muted tracks still advance their clips silently so un-muting is seamless.
            c->mixInto(out, mPos, frames, audible ? t.gain : 0.f);
        }
    }
    mMaster.process(out, frames);
    mLimiter.process(out, frames);
    mPos += frames;
    manageResources();
}

}  // namespace tfx
