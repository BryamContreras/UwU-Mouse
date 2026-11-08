#include <algorithm>
#include <cerrno>
#include <cmath>
#include <csignal>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <string>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>
#include <vector>

// Receive-only, explicit-device experiment. Never grabs, writes, or injects input.
namespace {
constexpr size_t kCapacity = 16384;
constexpr int64_t kSecond = 1000000000LL;
volatile sig_atomic_t interrupted = 0;
void stop(int) { interrupted = 1; }

struct Sample {
    int64_t kernelNs;
    int64_t receiptNs;
};

bool monotonicNow(int64_t& value) {
    timespec ts{};
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return false;
    value = static_cast<int64_t>(ts.tv_sec) * kSecond + ts.tv_nsec;
    return true;
}

std::string quote(const char* value) {
    std::string result = "\"";
    for (const unsigned char* p = reinterpret_cast<const unsigned char*>(value); *p; ++p) {
        if (*p == '"' || *p == '\\') {
            result += '\\';
            result += static_cast<char>(*p);
        } else if (*p < 32 || *p > 126) {
            char escaped[7];
            std::snprintf(escaped, sizeof escaped, "\\u%04x", *p);
            result += escaped;
        } else result += static_cast<char>(*p);
    }
    return result + '"';
}

bool explicitEventPath(const char* path) {
    const char* prefix = "/dev/input/event";
    const size_t length = std::strlen(prefix);
    if (std::strncmp(path, prefix, length) != 0 || !path[length]) return false;
    for (const char* p = path + length; *p; ++p) if (*p < '0' || *p > '9') return false;
    return true;
}

template <size_t N>
bool bit(const unsigned long (&bits)[N], unsigned code) {
    constexpr unsigned width = sizeof(unsigned long) * 8;
    return code / width < N && (bits[code / width] & (1UL << (code % width))) != 0;
}

int failure(const char* stage, const char* reason, int error = 0) {
    std::printf("{\"ok\":false,\"stage\":%s,\"reason\":%s,\"errno\":%d,"
                "\"readOnly\":true,\"scope\":\"kernel event timestamp to shell reader; "
                "not global Android capture, mapper latency, or presentation\"}\n",
                quote(stage).c_str(), quote(reason).c_str(), error);
    return 1;
}

double percentile(const std::vector<int64_t>& sorted, double p) {
    if (sorted.empty()) return 0;
    const size_t index = static_cast<size_t>(std::ceil(sorted.size() * p)) - 1;
    return sorted[std::min(index, sorted.size() - 1)] / 1e6;
}
}  // namespace

int main(int argc, char** argv) {
    if (getuid() != 2000 || geteuid() != 2000) return failure("guard", "shell UID 2000 required");
    if (argc < 3 || argc > 4 || !explicitEventPath(argv[1]) ||
        (argc == 4 && std::strcmp(argv[3], "--raw") != 0)) {
        return failure("arguments", "usage: NativeMouseLatencyProbe /dev/input/eventN seconds[1..60] [--raw]");
    }
    char* end = nullptr;
    errno = 0;
    const long seconds = std::strtol(argv[2], &end, 10);
    if (errno || end == argv[2] || *end || seconds < 1 || seconds > 60) {
        return failure("arguments", "duration must be an integer from 1 through 60 seconds");
    }
    const int fd = open(argv[1], O_RDONLY | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) return failure("open", "explicit device could not be opened read-only", errno);
    char name[256]{};
    input_id identity{};
    unsigned long relativeBits[(REL_MAX + sizeof(unsigned long) * 8) / (sizeof(unsigned long) * 8)]{};
    unsigned long keyBits[(KEY_MAX + sizeof(unsigned long) * 8) / (sizeof(unsigned long) * 8)]{};
    const bool named = ioctl(fd, EVIOCGNAME(sizeof name - 1), name) > 0 && name[0] != '\0';
    const bool identified = ioctl(fd, EVIOCGID, &identity) == 0;
    const bool relatives = ioctl(fd, EVIOCGBIT(EV_REL, sizeof relativeBits), relativeBits) >= 0;
    const bool keys = ioctl(fd, EVIOCGBIT(EV_KEY, sizeof keyBits), keyBits) >= 0;
    if (!named || !identified || !relatives || !keys ||
        !bit(relativeBits, REL_X) || !bit(relativeBits, REL_Y) || !bit(keyBits, BTN_LEFT) ||
        (identity.bustype != BUS_USB && identity.bustype != BUS_BLUETOOTH)) {
        close(fd);
        return failure("device_identity", "requires a named USB/Bluetooth relative mouse with BTN_LEFT");
    }
    const int clockId = CLOCK_MONOTONIC;
    if (ioctl(fd, EVIOCSCLOCKID, &clockId) != 0) {
        const int error = errno;
        close(fd);
        return failure("clock", "EVIOCSCLOCKID CLOCK_MONOTONIC required; no fallback", error);
    }
    std::signal(SIGINT, stop);
    std::signal(SIGTERM, stop);
    std::vector<Sample> samples;
    samples.reserve(kCapacity);
    int64_t startedNs = 0;
    if (!monotonicNow(startedNs)) {
        const int error = errno;
        close(fd);
        return failure("clock", "clock_gettime CLOCK_MONOTONIC failed", error);
    }
    const int64_t deadlineNs = startedNs + seconds * kSecond;
    uint64_t count = 0, capacityLost = 0, invalidTimestamp = 0, readCalls = 0, kernelSynDropped = 0;
    int64_t endedNs = startedNs;
    int readError = 0;
    const char* readReason = "";
    while (!interrupted) {
        int64_t nowNs = 0;
        if (!monotonicNow(nowNs)) { readError = errno; readReason = "clock_gettime_failed"; break; }
        if (nowNs >= deadlineNs) break;
        const int timeoutMs = static_cast<int>((deadlineNs - nowNs + 999999) / 1000000);
        pollfd watched{fd, POLLIN, 0};
        const int ready = poll(&watched, 1, timeoutMs);
        if (ready < 0) {
            if (errno == EINTR) continue;
            readError = errno; readReason = "poll_failed"; break;
        }
        if (ready == 0) continue;
        if (watched.revents & (POLLERR | POLLHUP | POLLNVAL)) {
            readError = ENODEV; readReason = "device_disconnected_or_poll_error"; break;
        }
        if (!(watched.revents & POLLIN)) continue;
        // One bounded read per iteration. The outer deadline is checked even with continuous input.
        input_event events[64];
        const ssize_t received = read(fd, events, sizeof events);
        const int savedReadError = received < 0 ? errno : 0;
        int64_t receiptNs = 0;
        const bool clockOk = monotonicNow(receiptNs); // Before inspecting any received event.
        if (!clockOk) { readError = errno; readReason = "clock_gettime_failed"; break; }
        if (received < 0) {
            if (savedReadError == EINTR || savedReadError == EAGAIN) continue;
            readError = savedReadError; readReason = "read_failed"; break;
        }
        if (received == 0 || received % sizeof(input_event) != 0) {
            readError = EIO; readReason = "invalid_input_event_read"; break;
        }
        ++readCalls;
        if (receiptNs > deadlineNs) break;
        const size_t eventCount = static_cast<size_t>(received) / sizeof(input_event);
        for (size_t i = 0; i < eventCount; ++i) {
            const input_event& event = events[i];
            if (event.type == EV_SYN && event.code == SYN_DROPPED) ++kernelSynDropped;
            if (event.type != EV_SYN || event.code != SYN_REPORT) continue;
            ++count;
            const int64_t kernelNs = static_cast<int64_t>(event.time.tv_sec) * kSecond +
                                     static_cast<int64_t>(event.time.tv_usec) * 1000;
            if (kernelNs < startedNs || kernelNs > receiptNs || event.time.tv_usec < 0 ||
                event.time.tv_usec >= 1000000) {
                ++invalidTimestamp;
                continue;
            }
            if (samples.size() < kCapacity) samples.push_back(Sample{kernelNs, receiptNs});
            else ++capacityLost;
        }
    }
    const bool endClockOk = monotonicNow(endedNs);
    close(fd);
    if (!endClockOk) return failure("clock", "final CLOCK_MONOTONIC read failed", errno);
    std::vector<int64_t> latencies;
    latencies.reserve(samples.size());
    for (const Sample& sample : samples) latencies.push_back(sample.receiptNs - sample.kernelNs);
    std::sort(latencies.begin(), latencies.end());
    const bool ok = !readError && !interrupted;
    std::printf("{\"ok\":%s,\"readOnly\":true,\"devicePath\":%s,\"deviceName\":%s,"
                "\"bus\":%u,\"uid\":%u,\"clock\":\"CLOCK_MONOTONIC; EVIOCSCLOCKID required\","
                "\"scope\":\"kernel SYN_REPORT timestamp to shell reader immediately after bounded read; "
                "not global Android capture, mapper latency, or presentation\","
                "\"requestedSeconds\":%ld,\"elapsedSeconds\":%.6f,\"count\":%llu,\"sampleCount\":%zu,"
                "\"capacity\":%zu,\"capacityLost\":%llu,\"invalidTimestamp\":%llu,\"readCalls\":%llu,"
                "\"kernelSynDropped\":%llu,"
                "\"statisticsScope\":\"first retained valid SYN_REPORT samples; nearest-rank percentiles\","
                "\"interrupted\":%s,\"errno\":%d,\"error\":%s,\"kernelToReadMs\":",
                ok ? "true" : "false", quote(argv[1]).c_str(), quote(name).c_str(),
                identity.bustype, getuid(), seconds, (endedNs - startedNs) / 1e9,
                static_cast<unsigned long long>(count), samples.size(), kCapacity,
                static_cast<unsigned long long>(capacityLost), static_cast<unsigned long long>(invalidTimestamp),
                static_cast<unsigned long long>(readCalls), static_cast<unsigned long long>(kernelSynDropped),
                interrupted ? "true" : "false", readError,
                quote(readReason).c_str());
    if (latencies.empty()) std::printf("null");
    else std::printf("{\"p50\":%.6f,\"p95\":%.6f,\"p99\":%.6f,\"max\":%.6f}",
                     percentile(latencies, .50), percentile(latencies, .95), percentile(latencies, .99),
                     latencies.back() / 1e6);
    if (argc == 4) {
        std::printf(",\"rawColumns\":[\"kernelMonotonicNs\",\"receiptMonotonicNs\"],\"raw\":[");
        for (size_t i = 0; i < samples.size(); ++i) {
            std::printf("%s[%lld,%lld]", i ? "," : "", static_cast<long long>(samples[i].kernelNs),
                        static_cast<long long>(samples[i].receiptNs));
        }
        std::printf("]");
    }
    std::printf("}\n");
    return ok ? 0 : 2;
}
