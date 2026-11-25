#pragma once
#include <functional>

namespace tfx {
// Returns false to request cancellation. progress is 0..1.
using ProgressFn = std::function<bool(float progress)>;
}
