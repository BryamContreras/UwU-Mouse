#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <linux/input.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

// Bounded capability check for an explicit external input node; no event reading/injection.
namespace {
template <size_t N> bool bit(const unsigned long (&v)[N], unsigned code) {
    constexpr unsigned width = sizeof(unsigned long) * 8;
    return code / width < N && (v[code / width] & (1UL << (code % width)));
}
bool pathValid(const char* path) {
    const char* prefix = "/dev/input/event";
    const size_t length = std::strlen(prefix);
    if (std::strncmp(path, prefix, length) || !path[length]) return false;
    for (const char* p = path + length; *p; ++p) if (*p < '0' || *p > '9') return false;
    return true;
}
}
int main(int argc, char** argv) {
    if (getuid() != 2000 || geteuid() != 2000 || argc != 2 || !pathValid(argv[1])) {
        std::puts("{\"ok\":false,\"stage\":\"guard\",\"grabAttempted\":false}"); return 1;
    }
    // A hard process deadline closes any outstanding grab even if normal cleanup cannot run.
    alarm(1);
    const int fd = open(argv[1], O_RDONLY | O_CLOEXEC | O_NONBLOCK | O_NOFOLLOW);
    if (fd < 0) { std::printf("{\"ok\":false,\"stage\":\"open\",\"errno\":%d,\"grabAttempted\":false}\n", errno); return 1; }
    input_id id{};
    unsigned long relative[(REL_MAX + sizeof(unsigned long)*8) / (sizeof(unsigned long)*8)]{};
    unsigned long keys[(KEY_MAX + sizeof(unsigned long)*8) / (sizeof(unsigned long)*8)]{};
    unsigned long held[(KEY_MAX + sizeof(unsigned long)*8) / (sizeof(unsigned long)*8)]{};
    const bool identity = ioctl(fd, EVIOCGID, &id) == 0 && (id.bustype == BUS_USB || id.bustype == BUS_BLUETOOTH);
    const bool haveKeys = ioctl(fd, EVIOCGBIT(EV_KEY, sizeof keys), keys) >= 0;
    const bool mouse = ioctl(fd, EVIOCGBIT(EV_REL, sizeof relative), relative) >= 0 && bit(relative, REL_X) && bit(relative, REL_Y) && bit(keys, BTN_LEFT);
    const bool keyboard = bit(keys, KEY_A) && bit(keys, KEY_Z) && bit(keys, KEY_ENTER);
    if (!identity || !haveKeys || (!mouse && !keyboard)) {
        close(fd); std::puts("{\"ok\":false,\"stage\":\"external_input_identity\",\"grabAttempted\":false,\"closed\":true}"); return 1;
    }
    if (ioctl(fd, EVIOCGKEY(sizeof held), held) < 0) {
        const int error=errno; close(fd); std::printf("{\"ok\":false,\"stage\":\"key_baseline\",\"errno\":%d,\"grabAttempted\":false,\"closed\":true}\n",error); return 1;
    }
    for (unsigned long value : held) if (value) {
        close(fd); std::puts("{\"ok\":false,\"stage\":\"held_input\",\"grabAttempted\":false,\"closed\":true}"); return 1;
    }
    const int clockId = CLOCK_MONOTONIC;
    if (ioctl(fd, EVIOCSCLOCKID, &clockId) != 0) {
        const int error=errno; close(fd); std::printf("{\"ok\":false,\"stage\":\"clock\",\"errno\":%d,\"grabAttempted\":false,\"closed\":true}\n",error); return 1;
    }
    const int grabbed = ioctl(fd, EVIOCGRAB, 1);
    const int grabError = grabbed == 0 ? 0 : errno;
    int released = -1, releaseError = 0;
    if (grabbed == 0) {
        timespec delay{0, 20'000'000};
        // An interrupt shortens this capability check; it never lengthens the lease.
        nanosleep(&delay, nullptr);
        released = ioctl(fd, EVIOCGRAB, 0);
        if (released != 0) releaseError = errno;
    }
    close(fd);
    std::printf("{\"ok\":%s,\"uid\":2000,\"bus\":%u,\"vendorId\":%u,\"productId\":%u,\"mouse\":%s,\"keyboard\":%s,\"clock\":\"CLOCK_MONOTONIC\",\"grabAttempted\":true,\"grabbed\":%s,\"grabErrno\":%d,\"requestedGrabMs\":20,\"released\":%s,\"releaseErrno\":%d,\"closed\":true}\n",
        grabbed == 0 && released == 0 ? "true":"false", id.bustype,id.vendor,id.product,mouse?"true":"false",keyboard?"true":"false",grabbed==0?"true":"false",grabError,released==0?"true":"false",releaseError);
    return grabbed == 0 && released == 0 ? 0 : 2;
}
