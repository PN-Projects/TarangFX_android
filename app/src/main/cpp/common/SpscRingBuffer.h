#pragma once
// Lock-free single-producer / single-consumer ring buffer.
// Safe to use from a real-time audio callback on the consumer side: no locks, no allocation.
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <memory>
#include <algorithm>

namespace tfx {

template <typename T>
class SpscRingBuffer {
public:
    explicit SpscRingBuffer(size_t minCapacity) {
        size_t cap = 1;
        while (cap < minCapacity) cap <<= 1;
        mCapacity = cap;
        mMask = cap - 1;
        mData = std::make_unique<T[]>(cap);
    }

    size_t capacity() const { return mCapacity; }

    // ---- producer side ----
    size_t availableToWrite() const {
        const uint64_t w = mWrite.load(std::memory_order_relaxed);
        const uint64_t r = mRead.load(std::memory_order_acquire);
        return mCapacity - static_cast<size_t>(w - r);
    }

    size_t write(const T* src, size_t count) {
        const uint64_t w = mWrite.load(std::memory_order_relaxed);
        const uint64_t r = mRead.load(std::memory_order_acquire);
        const size_t free = mCapacity - static_cast<size_t>(w - r);
        const size_t n = std::min(count, free);
        const size_t start = static_cast<size_t>(w) & mMask;
        const size_t first = std::min(n, mCapacity - start);
        std::memcpy(&mData[start], src, first * sizeof(T));
        if (n > first) std::memcpy(&mData[0], src + first, (n - first) * sizeof(T));
        mWrite.store(w + n, std::memory_order_release);
        return n;
    }

    bool push(const T& v) { return write(&v, 1) == 1; }

    // ---- consumer side ----
    size_t availableToRead() const {
        const uint64_t w = mWrite.load(std::memory_order_acquire);
        const uint64_t r = mRead.load(std::memory_order_relaxed);
        return static_cast<size_t>(w - r);
    }

    size_t read(T* dst, size_t count) {
        const uint64_t w = mWrite.load(std::memory_order_acquire);
        const uint64_t r = mRead.load(std::memory_order_relaxed);
        const size_t avail = static_cast<size_t>(w - r);
        const size_t n = std::min(count, avail);
        const size_t start = static_cast<size_t>(r) & mMask;
        const size_t first = std::min(n, mCapacity - start);
        std::memcpy(dst, &mData[start], first * sizeof(T));
        if (n > first) std::memcpy(dst + first, &mData[0], (n - first) * sizeof(T));
        mRead.store(r + n, std::memory_order_release);
        return n;
    }

    bool pop(T& out) { return read(&out, 1) == 1; }

    // Consumer-side flush: drops everything currently buffered.
    void discardAll() {
        mRead.store(mWrite.load(std::memory_order_acquire), std::memory_order_release);
    }

    // Only valid when neither producer nor consumer is running.
    void reset() {
        mWrite.store(0, std::memory_order_relaxed);
        mRead.store(0, std::memory_order_relaxed);
    }

private:
    std::unique_ptr<T[]> mData;
    size_t mCapacity = 0;
    size_t mMask = 0;
    alignas(64) std::atomic<uint64_t> mWrite{0};
    alignas(64) std::atomic<uint64_t> mRead{0};
};

}  // namespace tfx
