#pragma once
// TagLib bridge: read/write Title, Artist, Album, Album Artist, Year, Track, Genre, Comment and
// embedded front-cover art across MP3 (ID3v2), M4A/ALAC (MP4 atoms), FLAC & OGG/OPUS (Xiph
// comments / METADATA_BLOCK_PICTURE) and WAV (RIFF INFO + ID3v2 chunk).
#include <string>
#include <vector>
#include <cstdint>

namespace tfx {

struct AudioTags {
    std::string title, artist, album, albumArtist, genre, comment;
    int year = 0;
    int track = 0;
    std::vector<uint8_t> cover;  // raw JPEG/PNG bytes (empty => none)
    std::string coverMime;
};

// `source` may be "fd:N" or a path; `nameHint` provides the file extension for format detection.
bool readTags(const std::string& source, const std::string& nameHint, AudioTags& out);

// Writes tags into the file at `path` (must be a writable file owned by the app).
// coverMode: 0 = keep existing cover, 1 = replace with tags.cover, 2 = remove cover.
bool writeTags(const std::string& path, const AudioTags& tags, int coverMode, std::string& error);

}  // namespace tfx
