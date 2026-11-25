#pragma once
// Streams a source through the decoder in 4096-frame chunks and stores max-abs peaks
// (default 100 peaks/second) into a compact binary cache file:
//   "PKS1" | int32 version | int32 peaksPerSecond | int64 durationMs | int32 count | float[count]
// (all little-endian). Kotlin memory-maps/reads this once and renders it with viewport culling.
#include <string>
#include "common/Progress.h"

namespace tfx {

// Returns number of peaks written, or a negative value on error (-2 => cancelled).
int extractPeaks(const std::string& source, const std::string& cachePath, int peaksPerSecond,
                 const ProgressFn& progress);

}  // namespace tfx
