#include "ai/StreamDenoiser.h"
#include <mutex>
#include <unistd.h>

#ifdef HAVE_ONNXRUNTIME
#include "ai/OnnxDenoiser.h"
#endif

namespace tfx {

static std::mutex gDirMutex;
static std::string gModelDir;

void setDenoiserModelDirectory(const std::string& dir) {
    std::lock_guard<std::mutex> lk(gDirMutex);
    gModelDir = dir;
}

static std::string modelDir() {
    std::lock_guard<std::mutex> lk(gDirMutex);
    return gModelDir;
}

bool isDenoiserAvailable(const std::string& mode) {
#ifdef HAVE_ONNXRUNTIME
    return OnnxDenoiser::modelAvailable(modelDir(), mode);
#else
    (void)mode;
    return false;
#endif
}

std::unique_ptr<StreamDenoiser> createDenoiser(const std::string& mode, int sampleRate) {
#ifdef HAVE_ONNXRUNTIME
    return OnnxDenoiser::create(modelDir(), mode, sampleRate);
#else
    (void)mode;
    (void)sampleRate;
    return nullptr;
#endif
}

}  // namespace tfx
