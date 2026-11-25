#pragma once
// Opens an audio "source string" which is either:
//   "fd:<N>"  - a file descriptor owned by Kotlin (ParcelFileDescriptor); we dup() it so every
//               reader has an independent lifetime, and use pread() so readers never share offsets.
//   "<path>"  - a regular filesystem path (app cache / private files).
#include <string>
#include <cstdint>

extern "C" {
#include <libavformat/avio.h>
}

namespace tfx {

class SourceFile {
public:
    SourceFile() = default;
    ~SourceFile();
    SourceFile(const SourceFile&) = delete;
    SourceFile& operator=(const SourceFile&) = delete;

    bool open(const std::string& source);
    void close();

    int fd() const { return mFd; }
    int64_t size() const { return mSize; }

    // Builds an AVIOContext that reads this file via pread(). Owned by SourceFile.
    AVIOContext* avio();

    // Raw positional read helpers (used by AVIO callbacks).
    int read(uint8_t* buf, int size);
    int64_t seek(int64_t offset, int whence);

private:
    int mFd = -1;
    int64_t mPos = 0;
    int64_t mSize = 0;
    AVIOContext* mAvio = nullptr;
};

}  // namespace tfx
