#include "io/SourceFile.h"
#include "common/Log.h"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cerrno>
#include <cstdlib>

extern "C" {
#include <libavutil/mem.h>
#include <libavutil/error.h>
}

namespace tfx {

static constexpr int kAvioBufferSize = 64 * 1024;

SourceFile::~SourceFile() { close(); }

bool SourceFile::open(const std::string& source) {
    close();
    if (source.rfind("fd:", 0) == 0) {
        char* end = nullptr;
        long parent = std::strtol(source.c_str() + 3, &end, 10);
        if (end == source.c_str() + 3 || parent < 0) {
            LOGE("Invalid fd source '%s'", source.c_str());
            return false;
        }
        mFd = fcntl(static_cast<int>(parent), F_DUPFD_CLOEXEC, 0);
    } else {
        // Read-only, close-on-exec, never follow into a writable mode.
        mFd = ::open(source.c_str(), O_RDONLY | O_CLOEXEC);
    }
    if (mFd < 0) {
        LOGE("Cannot open source '%s': errno=%d", source.c_str(), errno);
        return false;
    }
    struct stat st {};
    mSize = (fstat(mFd, &st) == 0) ? static_cast<int64_t>(st.st_size) : -1;
    mPos = 0;
    return true;
}

void SourceFile::close() {
    if (mAvio) {
        av_freep(&mAvio->buffer);
        avio_context_free(&mAvio);
        mAvio = nullptr;
    }
    if (mFd >= 0) {
        ::close(mFd);
        mFd = -1;
    }
}

int SourceFile::read(uint8_t* buf, int size) {
    ssize_t n;
    do {
        n = pread(mFd, buf, static_cast<size_t>(size), mPos);
    } while (n < 0 && errno == EINTR);
    if (n < 0) return AVERROR(errno);
    if (n == 0) return AVERROR_EOF;
    mPos += n;
    return static_cast<int>(n);
}

int64_t SourceFile::seek(int64_t offset, int whence) {
    whence &= ~AVSEEK_FORCE;
    switch (whence) {
        case AVSEEK_SIZE: return mSize;
        case SEEK_SET: mPos = offset; break;
        case SEEK_CUR: mPos += offset; break;
        case SEEK_END:
            if (mSize < 0) return AVERROR(ENOSYS);
            mPos = mSize + offset;
            break;
        default: return AVERROR(EINVAL);
    }
    if (mPos < 0) mPos = 0;
    return mPos;
}

static int avioRead(void* opaque, uint8_t* buf, int size) {
    return static_cast<SourceFile*>(opaque)->read(buf, size);
}
static int64_t avioSeek(void* opaque, int64_t offset, int whence) {
    return static_cast<SourceFile*>(opaque)->seek(offset, whence);
}

AVIOContext* SourceFile::avio() {
    if (mAvio) return mAvio;
    if (mFd < 0) return nullptr;
    auto* buffer = static_cast<uint8_t*>(av_malloc(kAvioBufferSize));
    if (!buffer) return nullptr;
    mAvio = avio_alloc_context(buffer, kAvioBufferSize, 0, this, &avioRead, nullptr, &avioSeek);
    if (!mAvio) av_free(buffer);
    return mAvio;
}

}  // namespace tfx
