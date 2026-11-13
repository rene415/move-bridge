// Lock-free single-producer / single-consumer rings used by the engine. Fixed size, allocated
// once: nothing here allocates while audio is streaming.
#pragma once

#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace mb {

constexpr int FRAME_BYTES = 4;  // stereo, 16-bit

// Ring of interleaved stereo 16-bit frames. Producer: capture thread. Consumer: USB event thread.
class AudioRing {
public:
    static constexpr size_t CAP = 1 << 15;  // frames (~0.74 s at 44.1 kHz), power of two

    void write(const int16_t* src, size_t frames) {
        size_t wi = w_.load(std::memory_order_relaxed);
        size_t room = CAP - (wi - r_.load(std::memory_order_acquire));
        if (frames > room) frames = room;  // full: drop the overflow
        for (size_t i = 0; i < frames; i++) {
            int16_t* dst = frame(wi + i);
            dst[0] = src[i * 2];
            dst[1] = src[i * 2 + 1];
        }
        w_.store(wi + frames, std::memory_order_release);
    }

    /**
     * Fills [frames] output frames, stepping through the ring at [ratio] input frames per output
     * frame with linear interpolation (ratio is within +/-0.5% of 1). [frac] carries the
     * fractional position between calls. Pads with silence when the ring runs dry.
     * Returns the frames actually produced.
     */
    size_t readResampled(int16_t* dst, size_t frames, double ratio, double& frac) {
        size_t ri = r_.load(std::memory_order_relaxed);
        size_t avail = w_.load(std::memory_order_acquire) - ri;
        size_t n = 0;
        for (; n < frames && avail >= 2; n++) {
            interpolate(ri, frac, dst + n * 2);
            frac += ratio;
            size_t step = (size_t)frac;  // 0, 1 or 2: ratio < 1.01
            frac -= (double)step;
            ri += step;
            avail -= step;
        }
        if (n < frames) memset(dst + n * 2, 0, (frames - n) * FRAME_BYTES);
        r_.store(ri, std::memory_order_release);
        return n;
    }

    /** Drops queued audio so that [keep] frames remain (used if a large backlog builds up). */
    void trimTo(size_t keep) {
        size_t wi = w_.load(std::memory_order_acquire);
        if (wi - r_.load(std::memory_order_relaxed) > keep) r_.store(wi - keep, std::memory_order_release);
    }

    size_t avail() const {
        return w_.load(std::memory_order_acquire) - r_.load(std::memory_order_acquire);
    }

    void reset() { w_ = 0; r_ = 0; }

private:
    int16_t* frame(size_t i) { return &buf_[(i & (CAP - 1)) * 2]; }

    void interpolate(size_t ri, double frac, int16_t* out) {
        const int16_t* a = frame(ri);
        const int16_t* b = frame(ri + 1);
        out[0] = (int16_t)std::lrint(a[0] + (b[0] - a[0]) * frac);
        out[1] = (int16_t)std::lrint(a[1] + (b[1] - a[1]) * frac);
    }

    int16_t buf_[CAP * 2] = {};
    std::atomic<size_t> w_{0}, r_{0};
};

// Ring of per-packet frame counts from the Move's IN stream (implicit feedback).
// Producer: IN callback. Consumer: OUT callback. Both run on the event thread, but keep it
// lock-free anyway so it stays correct if that ever changes.
class FeedbackRing {
public:
    static constexpr size_t CAP = 256;  // power of two

    /** Returns false if the ring was full (OUT fell behind) and the value was dropped. */
    bool push(int frames) {
        size_t wi = w_.load(std::memory_order_relaxed);
        if (wi - r_.load(std::memory_order_acquire) >= CAP) return false;
        buf_[wi & (CAP - 1)] = frames;
        w_.store(wi + 1, std::memory_order_release);
        return true;
    }

    /** Next frame count, or -1 if none is queued. */
    int pop() {
        size_t ri = r_.load(std::memory_order_relaxed);
        if (ri == w_.load(std::memory_order_acquire)) return -1;
        int v = buf_[ri & (CAP - 1)];
        r_.store(ri + 1, std::memory_order_release);
        return v;
    }

    void reset() { w_ = 0; r_ = 0; }

private:
    int buf_[CAP] = {};
    std::atomic<size_t> w_{0}, r_{0};
};

}  // namespace mb
