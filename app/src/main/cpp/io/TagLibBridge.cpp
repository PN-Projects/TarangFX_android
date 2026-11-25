#include "io/TagLibBridge.h"
#include "common/Log.h"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cerrno>
#include <cstdlib>

#include <fileref.h>
#include <tag.h>
#include <tpropertymap.h>
#include <tiostream.h>
#include <tvariant.h>

namespace tfx {

// Read-only TagLib stream over a duplicated file descriptor (works for content:// URIs).
class FdIOStream : public TagLib::IOStream {
public:
    FdIOStream(int fd, std::string name) : mFd(fd), mName(std::move(name)) {}
    ~FdIOStream() override { if (mFd >= 0) ::close(mFd); }

    TagLib::FileName name() const override { return mName.c_str(); }
    TagLib::ByteVector readBlock(size_t length) override {
        TagLib::ByteVector v(static_cast<unsigned int>(length), 0);
        ssize_t n;
        do { n = pread(mFd, v.data(), length, mPos); } while (n < 0 && errno == EINTR);
        if (n <= 0) return TagLib::ByteVector();
        mPos += n;
        v.resize(static_cast<unsigned int>(n));
        return v;
    }
    void writeBlock(const TagLib::ByteVector&) override {}
    void insert(const TagLib::ByteVector&, TagLib::offset_t, size_t) override {}
    void removeBlock(TagLib::offset_t, size_t) override {}
    bool readOnly() const override { return true; }
    bool isOpen() const override { return mFd >= 0; }
    void seek(TagLib::offset_t offset, Position p) override {
        switch (p) {
            case Beginning: mPos = offset; break;
            case Current: mPos += offset; break;
            case End: mPos = length() + offset; break;
        }
        if (mPos < 0) mPos = 0;
    }
    TagLib::offset_t tell() const override { return mPos; }
    TagLib::offset_t length() override {
        struct stat st {};
        return fstat(mFd, &st) == 0 ? static_cast<TagLib::offset_t>(st.st_size) : 0;
    }
    void truncate(TagLib::offset_t) override {}

private:
    int mFd;
    std::string mName;
    TagLib::offset_t mPos = 0;
};

static std::string str(const TagLib::String& s) { return s.to8Bit(true); }

static void readFromRef(TagLib::FileRef& ref, AudioTags& out) {
    if (TagLib::Tag* t = ref.tag()) {
        out.title = str(t->title());
        out.artist = str(t->artist());
        out.album = str(t->album());
        out.genre = str(t->genre());
        out.comment = str(t->comment());
        out.year = static_cast<int>(t->year());
        out.track = static_cast<int>(t->track());
    }
    TagLib::PropertyMap props = ref.properties();
    auto it = props.find("ALBUMARTIST");
    if (it != props.end() && !it->second.isEmpty()) out.albumArtist = str(it->second.front());

    const auto pictures = ref.complexProperties("PICTURE");
    for (const auto& pic : pictures) {
        auto d = pic.find("data");
        if (d == pic.end()) continue;
        const TagLib::ByteVector bytes = d->second.value<TagLib::ByteVector>();
        if (bytes.isEmpty()) continue;
        out.cover.assign(bytes.begin(), bytes.end());
        auto m = pic.find("mimeType");
        if (m != pic.end()) out.coverMime = str(m->second.value<TagLib::String>());
        break;
    }
}

bool readTags(const std::string& source, const std::string& nameHint, AudioTags& out) {
    out = AudioTags{};
    if (source.rfind("fd:", 0) == 0) {
        const int parent = std::atoi(source.c_str() + 3);
        const int fd = fcntl(parent, F_DUPFD_CLOEXEC, 0);
        if (fd < 0) return false;
        FdIOStream stream(fd, nameHint.empty() ? "audio" : nameHint);
        TagLib::FileRef ref(&stream, false);
        if (ref.isNull()) return false;
        readFromRef(ref, out);
        return true;
    }
    TagLib::FileRef ref(source.c_str(), false);
    if (ref.isNull()) return false;
    readFromRef(ref, out);
    return true;
}

bool writeTags(const std::string& path, const AudioTags& tags, int coverMode, std::string& error) {
    TagLib::FileRef ref(path.c_str(), false);
    if (ref.isNull() || !ref.tag()) {
        error = "Unsupported file for tagging";
        return false;
    }
    TagLib::Tag* t = ref.tag();
    t->setTitle(TagLib::String(tags.title, TagLib::String::UTF8));
    t->setArtist(TagLib::String(tags.artist, TagLib::String::UTF8));
    t->setAlbum(TagLib::String(tags.album, TagLib::String::UTF8));
    t->setGenre(TagLib::String(tags.genre, TagLib::String::UTF8));
    t->setComment(TagLib::String(tags.comment, TagLib::String::UTF8));
    t->setYear(static_cast<unsigned int>(std::max(0, tags.year)));
    t->setTrack(static_cast<unsigned int>(std::max(0, tags.track)));

    TagLib::PropertyMap props = ref.properties();
    if (tags.albumArtist.empty()) props.erase("ALBUMARTIST");
    else props.replace("ALBUMARTIST", TagLib::StringList(TagLib::String(tags.albumArtist, TagLib::String::UTF8)));
    ref.setProperties(props);

    if (coverMode == 2) {
        ref.setComplexProperties("PICTURE", {});
    } else if (coverMode == 1 && !tags.cover.empty()) {
        TagLib::VariantMap pic;
        pic["data"] = TagLib::ByteVector(reinterpret_cast<const char*>(tags.cover.data()),
                                         static_cast<unsigned int>(tags.cover.size()));
        pic["mimeType"] = TagLib::String(tags.coverMime.empty() ? "image/jpeg" : tags.coverMime);
        pic["description"] = TagLib::String("Cover");
        pic["pictureType"] = TagLib::String("Front Cover");
        if (!ref.setComplexProperties("PICTURE", {pic})) {
            LOGW("Cover art not supported for %s", path.c_str());
        }
    }
    if (!ref.save()) {
        error = "TagLib failed to save tags";
        return false;
    }
    return true;
}

}  // namespace tfx
