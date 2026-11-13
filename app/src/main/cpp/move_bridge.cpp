// Move Bridge native engine: streams 44.1 kHz / 16-bit stereo PCM to the Ableton Move's
// USB Audio Class 2.0 input, bypassing Android's audio stack.
//
// Move layout (read from the device, see docs/move-usb-descriptor.md):
//   interface 1 = Audio Control (clock source ID 1)
//   interface 2 = streaming, phone -> Move, iso OUT 0x02, async, max 28 B
//   interface 3 = streaming, Move -> phone, iso IN 0x82, implicit feedback
// Pacing: each OUT packet carries as many frames as the matching IN packet did (implicit
// feedback). Before IN data arrives we fall back to 44100/8000 frames per packet.
//
// Coding rules (NASA "Power of Ten", adapted): functions <= 25 lines, every loop bounded, no
// allocation while streaming (buffers are allocated in startEngine), return values checked.
// Exception: libusb requires callbacks (function pointers) and an event loop that runs for the
// life of the stream; both are documented where they occur.

#include <jni.h>
#include <android/log.h>
#include <libusb.h>
#include <sys/resource.h>
#include <unistd.h>

#include <atomic>
#include <cstdio>
#include <cmath>
#include <cstdarg>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "ring.h"

#define TAG "MoveBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

using mb::FRAME_BYTES;

constexpr int IF_AC = 1, IF_OUT = 2, IF_IN = 3;
constexpr unsigned char EP_OUT = 0x02, EP_IN = 0x82;
constexpr int CLOCK_ID = 1;
constexpr int RATE = 44100;
constexpr int PKTS_PER_SEC = 8000;   // high-speed: one packet per 125 us microframe
constexpr int MAX_PKT = 28;          // wMaxPacketSize from the descriptor
constexpr int MAX_FRAMES = MAX_PKT / FRAME_BYTES;
// USB queue: NUM_XFERS x PKTS packets in flight = 4 ms of audio queued to the Move.
constexpr int PKTS = 8;              // packets per transfer (1 ms)
constexpr int NUM_XFERS = 4;         // transfers in flight per direction
constexpr unsigned CTRL_TIMEOUT_MS = 1000;

// App buffer: the adaptive resampler holds queued audio near TARGET_MS. Capture arrives in
// bursts of up to ~20 ms, so a lower target runs dry between bursts (heard as clicks).
constexpr double TARGET_MS = 18.0;
constexpr double TARGET_FRAMES = RATE * TARGET_MS / 1000.0;
constexpr size_t BACKLOG_LIMIT = RATE * 60 / 1000;  // > 60 ms queued: trim back to the target
constexpr double MAX_RATIO_DEV = 0.005;             // +/-0.5% playback speed, inaudible
constexpr double LEVEL_SMOOTHING = 0.0012;          // per packet: ~100 ms time constant

constexpr int DRAIN_POLLS = 50;      // x 100 ms: how long stop waits for cancelled transfers
constexpr int WRITER_WAIT_SPINS = 100000;

struct Engine {
    libusb_context* ctx = nullptr;
    libusb_device_handle* h = nullptr;
    std::thread events;
    std::atomic<bool> running{false};
    std::atomic<int> active{0};          // transfers currently submitted
    std::vector<libusb_transfer*> xfers;
    mb::AudioRing ring;
    mb::FeedbackRing feedback;
    bool inStream = false;
    // Event-thread state.
    double nominalFrac = 0;              // fractional frames for nominal pacing
    double rsFrac = 0;                   // resampler read position
    double levelEma = 0;                 // smoothed buffer level, frames
    bool primed = false;                 // buffer reached the target since the last underrun
    bool tone = false;
    double phase = 0;
    // Counters for stats().
    std::atomic<uint64_t> outPkts{0}, inPkts{0}, underruns{0}, errors{0}, framesSent{0};
    std::atomic<uint64_t> qSum{0}, qCount{0}, qMax{0};
    // Diagnostics: OUT packets paced by the nominal estimate instead of the Move's feedback,
    // feedback entries dropped (queue full), sudden jumps in the outgoing audio (clicks), trims.
    std::atomic<uint64_t> nominalPkts{0}, fbDropped{0}, jumps{0}, trims{0};
    std::atomic<uint64_t> lostPkts{0};
    FILE* dump = nullptr;            // debug: what we send, first 15 s (see setDumpPath)
    uint64_t dumpLeft = 0;           // frames still to record  // OUT packets the controller didn't deliver (e.g. submitted late)
    int16_t lastSample = 0;  // event thread: previous outgoing left sample
    std::atomic<int> peak{0};            // loudest sample since the last level() read
    std::atomic<int> writers{0};         // JNI write() calls in progress
    std::mutex errMu;                    // lastError: written by callbacks, read by stats()
    std::string lastError;
} E;

// ---------- small helpers ----------

void setError(const std::string& s) {
    std::lock_guard<std::mutex> g(E.errMu);
    E.lastError = s;
}

std::string getError() {
    std::lock_guard<std::mutex> g(E.errMu);
    return E.lastError;
}

/** printf-style append to a log string. */
void appendf(std::string& out, const char* fmt, ...) {
    char line[200];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(line, sizeof line, fmt, ap);
    va_end(ap);
    if (n > 0) out += line;
}

/** Logs a libusb call that failed during cleanup; nothing else can be done there. */
void checkCleanup(int rc, const char* what) {
    if (rc != 0) LOGE("cleanup: %s: %s", what, libusb_error_name(rc));
}

// ---------- OUT packet contents (event thread) ----------

/** Frames for the next OUT packet: the matching IN packet's count, else 44100/8000 nominal. */
int nextFrameCount() {
    int f = E.feedback.pop();
    if (f > 0) return f > MAX_FRAMES ? MAX_FRAMES : f;
    E.nominalPkts++;
    E.nominalFrac += (double)RATE / PKTS_PER_SEC;
    int n = (int)E.nominalFrac;
    E.nominalFrac -= n;
    return n > MAX_FRAMES ? MAX_FRAMES : n;
}

void fillTone(int16_t* p, int frames) {
    for (int k = 0; k < frames; k++) {
        auto s = (int16_t)(std::sin(E.phase) * 8000.0);  // 440 Hz at about -12 dBFS
        p[k * 2] = s;
        p[k * 2 + 1] = s;
        E.phase += 2.0 * M_PI * 440.0 / RATE;
        if (E.phase > 2.0 * M_PI) E.phase -= 2.0 * M_PI;
    }
}

/** Playback speed that steers the buffer toward TARGET_FRAMES. */
double steeringRatio(double level) {
    E.levelEma += (level - E.levelEma) * LEVEL_SMOOTHING;
    double dev = (E.levelEma - TARGET_FRAMES) / TARGET_FRAMES * MAX_RATIO_DEV;
    if (dev > MAX_RATIO_DEV) dev = MAX_RATIO_DEV;
    if (dev < -MAX_RATIO_DEV) dev = -MAX_RATIO_DEV;
    return 1.0 + dev;
}

void recordLevel(size_t q) {
    E.qSum += q;
    E.qCount++;
    if (q > E.qMax.load()) E.qMax = q;
}

/** Phone audio from the ring, resampled to hold the buffer at the target. */
void fillMusic(int16_t* p, int frames) {
    // Safety net after a capture hiccup: a big backlog is cut straight back to the target.
    if (E.ring.avail() > BACKLOG_LIMIT) {
        E.ring.trimTo((size_t)TARGET_FRAMES);
        E.trims++;
    }
    size_t level = E.ring.avail();
    recordLevel(level);
    if (!E.primed) {
        // Wait until a full cushion is queued, so playback doesn't stutter while it builds.
        E.primed = (double)level >= TARGET_FRAMES;
        if (E.primed) E.levelEma = (double)level;
        memset(p, 0, (size_t)frames * FRAME_BYTES);
        return;
    }
    double ratio = steeringRatio((double)level);
    if (E.ring.readResampled(p, (size_t)frames, ratio, E.rsFrac) < (size_t)frames) {
        E.underruns++;
        E.primed = false;  // ran dry: rebuild the cushion before continuing
    }
}

/** Counts sample-to-sample jumps too large for real music: these are heard as clicks. */
void trackJumps(const int16_t* p, int frames) {
    for (int k = 0; k < frames; k++) {
        if (std::abs(p[k * 2] - E.lastSample) > 16000) E.jumps++;
        E.lastSample = p[k * 2];
    }
}

void trackPeak(const int16_t* p, int frames) {
    int m = 0;
    for (int k = 0; k < frames * 2; k++) {
        int v = p[k] < 0 ? -p[k] : p[k];
        if (v > m) m = v;
    }
    if (m > E.peak.load(std::memory_order_relaxed)) E.peak.store(m, std::memory_order_relaxed);
}

/** Debug recording of the outgoing audio (only when a dump path was set). */
void dumpFrames(const int16_t* p, int frames) {
    if (E.dump == nullptr) return;
    size_t k = (size_t)frames < E.dumpLeft ? (size_t)frames : (size_t)E.dumpLeft;
    fwrite(p, FRAME_BYTES, k, E.dump);
    E.dumpLeft -= k;
    if (E.dumpLeft == 0) {
        fclose(E.dump);
        E.dump = nullptr;
    }
}

void fillOut(libusb_transfer* t) {
    int off = 0;
    for (int i = 0; i < t->num_iso_packets; i++) {
        int frames = nextFrameCount();
        auto* p = reinterpret_cast<int16_t*>(t->buffer + off);
        if (E.tone) fillTone(p, frames);
        else fillMusic(p, frames);
        trackPeak(p, frames);
        trackJumps(p, frames);
        dumpFrames(p, frames);
        t->iso_packet_desc[i].length = (unsigned)(frames * FRAME_BYTES);
        off += frames * FRAME_BYTES;
        E.framesSent += (uint64_t)frames;
    }
    t->length = off;
}

// ---------- libusb callbacks (event thread) ----------

void onDisconnected() {
    E.running = false;
    setError("Move disconnected");
}

/** Packets inside a transfer can fail on their own (e.g. a late resubmit misses its microframe). */
void countLostPackets(const libusb_transfer* t) {
    for (int i = 0; i < t->num_iso_packets; i++)
        if (t->iso_packet_desc[i].status != LIBUSB_TRANSFER_COMPLETED) E.lostPkts++;
}

void LIBUSB_CALL outCb(libusb_transfer* t) {
    if (t->status == LIBUSB_TRANSFER_NO_DEVICE) onDisconnected();
    if (t->status != LIBUSB_TRANSFER_CANCELLED && t->status != LIBUSB_TRANSFER_NO_DEVICE) {
        // COMPLETED, or a transient failure (ERROR, STALL, TIMED_OUT, OVERFLOW): keep going.
        if (t->status != LIBUSB_TRANSFER_COMPLETED) E.errors++;
        E.outPkts += (uint64_t)t->num_iso_packets;
        countLostPackets(t);
        if (E.running) {
            fillOut(t);
            if (libusb_submit_transfer(t) == 0) return;
            E.errors++;  // can't resubmit: the stream is dead, so say so
            E.running = false;
            setError("USB audio stream stopped");
        }
    }
    E.active--;
}

/** One feedback entry per IN packet (0 = use nominal), keeping OUT paired 1:1 with IN. */
void pushFeedback(const libusb_transfer* t) {
    for (int i = 0; i < t->num_iso_packets; i++) {
        const auto& d = t->iso_packet_desc[i];
        bool ok = t->status == LIBUSB_TRANSFER_COMPLETED && d.status == LIBUSB_TRANSFER_COMPLETED;
        if (!E.feedback.push(ok ? (int)d.actual_length / FRAME_BYTES : 0)) E.fbDropped++;
    }
}

void LIBUSB_CALL inCb(libusb_transfer* t) {
    if (t->status == LIBUSB_TRANSFER_NO_DEVICE) onDisconnected();
    if (t->status != LIBUSB_TRANSFER_CANCELLED && t->status != LIBUSB_TRANSFER_NO_DEVICE) {
        E.inPkts += (uint64_t)t->num_iso_packets;
        pushFeedback(t);
        // If IN can't be resubmitted, OUT carries on with nominal pacing.
        if (E.running && libusb_submit_transfer(t) == 0) return;
    }
    E.active--;
}

// ---------- start: device setup ----------

void describeConfig(libusb_device* dev, std::string& log) {
    libusb_config_descriptor* cfg = nullptr;
    if (libusb_get_active_config_descriptor(dev, &cfg) != 0) return;
    for (int i = 0; i < cfg->bNumInterfaces; i++) {
        const auto& itf = cfg->interface[i];
        for (int a = 0; a < itf.num_altsetting; a++) {
            const auto& alt = itf.altsetting[a];
            appendf(log, " if%d alt%d class=%02x/%02x/%02x eps=%d\n", alt.bInterfaceNumber,
                    alt.bAlternateSetting, alt.bInterfaceClass, alt.bInterfaceSubClass,
                    alt.bInterfaceProtocol, alt.bNumEndpoints);
        }
    }
    libusb_free_config_descriptor(cfg);
}

/** Opens the Android USB fd with libusb and checks the link speed. Returns a libusb code. */
int openDevice(int fd, std::string& log) {
    int rc = libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    if (rc) return rc;
    if ((rc = libusb_init(&E.ctx))) return rc;
    if ((rc = libusb_wrap_sys_device(E.ctx, (intptr_t)fd, &E.h))) return rc;
    libusb_device* dev = libusb_get_device(E.h);
    libusb_device_descriptor dd{};
    if (libusb_get_device_descriptor(dev, &dd) == 0)
        appendf(log, "Device %04x:%04x speed=%d\n", dd.idVendor, dd.idProduct, libusb_get_device_speed(dev));
    describeConfig(dev, log);
    // 28-byte packets carry at most 7 frames: only high speed (8000 packets/s) can carry 44.1 kHz.
    if (libusb_get_device_speed(dev) < LIBUSB_SPEED_HIGH) {
        log += "Link is not high-speed (USB 2.0 HS needed)\n";
        return LIBUSB_ERROR_NOT_SUPPORTED;
    }
    return 0;
}

int claimInterfaces(std::string& log) {
    for (int itf : {IF_AC, IF_OUT, IF_IN}) {
        if (libusb_kernel_driver_active(E.h, itf) == 1)
            checkCleanup(libusb_detach_kernel_driver(E.h, itf), "detach kernel driver");
        int rc = libusb_claim_interface(E.h, itf);
        appendf(log, "claim if%d: %s\n", itf, libusb_error_name(rc));
        if (rc && itf != IF_IN) return rc;  // IN is optional (nominal pacing without it)
    }
    return 0;
}

void readClockRange(uint16_t clockIdx, std::string& log) {
    unsigned char buf[64] = {0};
    int rc = libusb_control_transfer(E.h, 0xA1, 0x02, 0x0100, clockIdx, buf, sizeof buf, CTRL_TIMEOUT_MS);
    if (rc < 14) {
        appendf(log, "clock range read: %s\n", rc < 0 ? libusb_error_name(rc) : "short");
        return;
    }
    uint32_t mn, mx;
    memcpy(&mn, buf + 2, 4);
    memcpy(&mx, buf + 6, 4);
    appendf(log, "clock range: %u-%u Hz (%u sub-ranges)\n", mn, mx, buf[0] | (buf[1] << 8));
}

/** UAC2 SET_CUR sampling frequency on the clock source, then read it back. */
void setClock(std::string& log) {
    uint16_t clockIdx = (CLOCK_ID << 8) | IF_AC;
    readClockRange(clockIdx, log);
    uint32_t rate = RATE;
    unsigned char rb[4];
    memcpy(rb, &rate, 4);
    int rc = libusb_control_transfer(E.h, 0x21, 0x01, 0x0100, clockIdx, rb, 4, CTRL_TIMEOUT_MS);
    appendf(log, "set 44100: %s\n", rc < 0 ? libusb_error_name(rc) : "ok");
    memset(rb, 0, 4);
    if (libusb_control_transfer(E.h, 0xA1, 0x01, 0x0100, clockIdx, rb, 4, CTRL_TIMEOUT_MS) == 4) {
        memcpy(&rate, rb, 4);
        appendf(log, "clock now: %u Hz\n", rate);
    }
}

/** Implicit-feedback source (IN) first, then the playback stream (OUT). */
int enableStreams(std::string& log) {
    E.inStream = libusb_set_interface_alt_setting(E.h, IF_IN, 1) == 0;
    log += E.inStream ? "IN stream (if3 alt1): on\n" : "IN stream: off, using nominal pacing\n";
    int rc = libusb_set_interface_alt_setting(E.h, IF_OUT, 1);
    if (rc == 0) log += "OUT stream (if2 alt1): on\n";
    return rc;
}

// ---------- start: streaming ----------

void resetState(bool tone) {
    E.tone = tone;
    E.phase = E.nominalFrac = E.rsFrac = E.levelEma = 0;
    E.primed = false;
    // E.running is false, so no new write() can start; wait out any that already did.
    for (int i = 0; i < WRITER_WAIT_SPINS && E.writers.load() > 0; i++) std::this_thread::yield();
    E.ring.reset();
    E.feedback.reset();
    E.outPkts = E.inPkts = E.underruns = E.errors = E.framesSent = 0;
    E.qSum = E.qCount = E.qMax = 0;
    E.nominalPkts = E.fbDropped = E.jumps = E.trims = E.lostPkts = 0;
    E.lastSample = 0;
    setError("");
}

/** Allocates (once, at start) and submits one transfer. */
int submitNew(unsigned char ep, libusb_transfer_cb_fn cb) {
    libusb_transfer* t = libusb_alloc_transfer(PKTS);
    if (t == nullptr) return LIBUSB_ERROR_NO_MEM;
    auto* b = new unsigned char[PKTS * MAX_PKT]();
    libusb_fill_iso_transfer(t, E.h, ep, b, PKTS * MAX_PKT, PKTS, cb, nullptr, 0);
    libusb_set_iso_packet_lengths(t, MAX_PKT);
    if (ep == EP_OUT) fillOut(t);
    E.xfers.push_back(t);
    int rc = libusb_submit_transfer(t);
    if (rc == 0) E.active++;
    return rc;
}

int submitAll() {
    for (int i = 0; E.inStream && i < NUM_XFERS; i++) {
        if (submitNew(EP_IN, inCb) != 0) LOGE("IN transfer not submitted; nominal pacing");
    }
    for (int i = 0; i < NUM_XFERS; i++) {
        int rc = submitNew(EP_OUT, outCb);
        if (rc) return rc;
    }
    return 0;
}

/**
 * libusb event loop. Deliberately unbounded while streaming (it *is* the stream); once stopped
 * it drains for at most DRAIN_POLLS x 100 ms.
 */
void eventLoop() {
    // Audio priority (Android's THREAD_PRIORITY_URGENT_AUDIO): only 4 ms is queued to the Move.
    if (setpriority(PRIO_PROCESS, gettid(), -19) != 0) LOGE("event thread priority not raised");
    timeval tv{0, 100000};
    int idle = 0;
    while (E.running || E.active > 0) {
        int rc = libusb_handle_events_timeout_completed(E.ctx, &tv, nullptr);
        if (rc != 0 && rc != LIBUSB_ERROR_INTERRUPTED) LOGE("handle_events: %s", libusb_error_name(rc));
        if (!E.running && ++idle > DRAIN_POLLS) break;
    }
}

std::string failed(const char* what, int rc, const std::string& log) {
    std::string line = std::string("ERR ") + what + ": " + libusb_error_name(rc);
    setError(line);
    LOGE("%s", line.c_str());
    return line + "\n" + log;
}

std::string startEngine(int fd, bool tone) {
    std::string log;
    int rc = openDevice(fd, log);
    if (rc == LIBUSB_ERROR_NOT_SUPPORTED) return failed("not high-speed", rc, log);
    if (rc) return failed("open", rc, log);
    if ((rc = claimInterfaces(log))) return failed("claim_interface", rc, log);
    setClock(log);
    if ((rc = enableStreams(log))) return failed("set alt if2", rc, log);
    resetState(tone);
    E.running = true;
    if ((rc = submitAll())) {
        E.running = false;
        return failed("submit OUT", rc, log);
    }
    E.events = std::thread(eventLoop);
    log += tone ? "Streaming 440 Hz test tone\n" : "Streaming phone audio\n";
    LOGI("%s", log.c_str());
    return "OK\n" + log;
}

// ---------- stop ----------

/** Cancels all transfers and waits (bounded) for their callbacks. */
void cancelAndDrain() {
    for (auto* t : E.xfers) libusb_cancel_transfer(t);  // NOT_FOUND for idle ones is fine
    if (E.events.joinable()) {
        E.events.join();
        return;
    }
    // Start failed before the event thread existed: reap cancelled transfers here.
    timeval tv{0, 100000};
    for (int i = 0; i < DRAIN_POLLS && E.active > 0; i++)
        libusb_handle_events_timeout_completed(E.ctx, &tv, nullptr);
}

void freeTransfers() {
    for (auto* t : E.xfers) {
        delete[] t->buffer;
        libusb_free_transfer(t);
    }
    E.xfers.clear();
}

void releaseDevice() {
    if (E.h == nullptr) return;
    checkCleanup(libusb_set_interface_alt_setting(E.h, IF_OUT, 0), "OUT alt 0");
    if (E.inStream) checkCleanup(libusb_set_interface_alt_setting(E.h, IF_IN, 0), "IN alt 0");
    for (int itf : {IF_AC, IF_OUT, IF_IN}) libusb_release_interface(E.h, itf);  // may not be claimed
    libusb_close(E.h);
    E.h = nullptr;
}

void stopEngine() {
    if (!E.ctx) return;
    E.running = false;
    cancelAndDrain();
    if (E.active > 0) {
        // Transfers never completed. Freeing them (or exiting libusb) could let a late
        // completion write into freed memory, so deliberately leak this session instead.
        LOGE("stop: %d transfers still in flight, leaking USB session", E.active.load());
        E.xfers.clear();
        E.active = 0;
        E.h = nullptr;
        E.ctx = nullptr;
        return;
    }
    freeTransfers();
    releaseDevice();
    libusb_exit(E.ctx);
    E.ctx = nullptr;
    LOGI("stopped");
}

std::string statsLine() {
    uint64_t n = E.qCount.exchange(0), sum = E.qSum.exchange(0), mx = E.qMax.exchange(0);
    double avgMs = n ? (double)sum / (double)n * 1000.0 / RATE : 0;
    double maxMs = (double)mx * 1000.0 / RATE;
    std::string err = getError();
    std::string s;
    appendf(s, "%s | buffer %.0f ms (max %.0f) | sent %.1f s", E.running ? "RUNNING" : "STOPPED",
            avgMs, maxMs, (double)E.framesSent.load() / RATE);
    appendf(s, " | out pkts %llu | in pkts %llu", (unsigned long long)E.outPkts.load(),
            (unsigned long long)E.inPkts.load());
    appendf(s, " | underruns %llu | errors %llu", (unsigned long long)E.underruns.load(),
            (unsigned long long)E.errors.load());
    appendf(s, " | lost pkts %llu", (unsigned long long)E.lostPkts.load());
    appendf(s, " | nominal %llu | fb dropped %llu | jumps %llu | trims %llu",
            (unsigned long long)E.nominalPkts.load(), (unsigned long long)E.fbDropped.load(),
            (unsigned long long)E.jumps.load(), (unsigned long long)E.trims.load());
    if (!err.empty()) s += " | " + err;
    return s;
}

}  // namespace

// ---------- JNI (com.renelabs.movebridge.NativeBridge) ----------

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_renelabs_movebridge_NativeBridge_start(JNIEnv* env, jobject, jint fd, jboolean tone) {
    stopEngine();
    std::string r = startEngine(fd, tone);
    if (r.rfind("ERR", 0) == 0) stopEngine();
    return env->NewStringUTF(r.c_str());
}

JNIEXPORT void JNICALL
Java_com_renelabs_movebridge_NativeBridge_write(JNIEnv* env, jobject, jshortArray data, jint frames) {
    E.writers++;
    if (E.running && frames > 0) {
        jsize len = env->GetArrayLength(data);
        if (frames > len / 2) frames = len / 2;
        jshort* p = env->GetShortArrayElements(data, nullptr);
        if (p != nullptr) {
            E.ring.write(reinterpret_cast<int16_t*>(p), (size_t)frames);
            env->ReleaseShortArrayElements(data, p, JNI_ABORT);
        }
    }
    E.writers--;
}

JNIEXPORT void JNICALL
Java_com_renelabs_movebridge_NativeBridge_stop(JNIEnv*, jobject) { stopEngine(); }

/** Debug: record the next 15 s of outgoing audio to [path] (call before start). */
JNIEXPORT void JNICALL
Java_com_renelabs_movebridge_NativeBridge_setDumpPath(JNIEnv* env, jobject, jstring path) {
    const char* c = env->GetStringUTFChars(path, nullptr);
    if (c == nullptr) return;
    if (E.dump != nullptr) fclose(E.dump);
    E.dump = fopen(c, "wb");
    E.dumpLeft = 15ULL * RATE;
    env->ReleaseStringUTFChars(path, c);
}

JNIEXPORT jboolean JNICALL
Java_com_renelabs_movebridge_NativeBridge_isRunning(JNIEnv*, jobject) { return E.running.load(); }

JNIEXPORT jfloat JNICALL
Java_com_renelabs_movebridge_NativeBridge_level(JNIEnv*, jobject) {
    return (float)E.peak.exchange(0) / 32768.0f;
}

JNIEXPORT jstring JNICALL
Java_com_renelabs_movebridge_NativeBridge_stats(JNIEnv* env, jobject) {
    return env->NewStringUTF(statsLine().c_str());
}

}  // extern "C"
