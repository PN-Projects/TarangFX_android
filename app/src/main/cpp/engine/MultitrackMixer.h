#pragma once
// Sums all tracks/clips of a project and applies the master chain + soft limiter.
// The exact same class renders live preview (AudioEngine feeder thread) and offline export
// (OfflineRenderer), which is what guarantees "export matches preview".
#include <memory>
#include <vector>
#include "engine/ProjectModel.h"
#include "engine/ClipRenderer.h"
#include "dsp/DspChain.h"
#include "dsp/SoftLimiter.h"

namespace tfx {

class MultitrackMixer {
public:
    MultitrackMixer(int sampleRate, bool highQuality);

    void setProject(const ProjectDesc& project);   // reuses clip renderers whose structure is unchanged
    void updateClip(const ClipDesc& clip);          // parameter-only update
    void setMasterDsp(const DspParams& p);
    void setTrackState(int64_t trackId, float gainDb, bool muted, bool solo);

    void seek(int64_t frame);
    int64_t position() const { return mPos; }
    int64_t lengthFrames() const { return mLength; }
    int sampleRate() const { return mRate; }

    // Renders `frames` stereo frames starting at the current position and advances it.
    void render(float* out, int frames);

private:
    struct Track {
        TrackDesc desc;
        float gain = 1.f;
        std::vector<std::unique_ptr<ClipRenderer>> clips;
    };
    void recomputeLength();
    void manageResources();

    int mRate;
    bool mHighQuality;
    std::vector<Track> mTracks;
    DspChain mMaster;
    SoftLimiter mLimiter;
    int64_t mPos = 0;
    int64_t mLength = 0;
    bool mAnySolo = false;
};

}  // namespace tfx
