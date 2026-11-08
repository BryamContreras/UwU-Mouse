#include <jni.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <linux/input.h>
#include <memory>
#include <mutex>
#include <poll.h>
#include <stdexcept>
#include <string>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <thread>
#include <time.h>
#include <unordered_map>
#include <unistd.h>
#include <vector>
#include "capture_policy.h"

// Original implementation. Privileged evdev transport, with no Android UI or injector.
namespace {
constexpr size_t kWordBits = sizeof(unsigned long) * 8;
using KeyBits = std::array<unsigned long, (KEY_CNT + kWordBits - 1) / kWordBits>;
using RelBits = std::array<unsigned long, (REL_CNT + kWordBits - 1) / kWordBits>;
using AbsBits = std::array<unsigned long, (ABS_CNT + kWordBits - 1) / kWordBits>;
template <size_t N> bool bit(const std::array<unsigned long,N>& values, unsigned code) {
    return code / kWordBits < N && (values[code/kWordBits] & (1UL << (code%kWordBits)));
}
void keyBit(KeyBits& values, unsigned code, bool down) {
    if (code >= KEY_CNT) return;
    if (down) values[code/kWordBits] |= 1UL << (code%kWordBits);
    else values[code/kWordBits] &= ~(1UL << (code%kWordBits));
}
int64_t monotonic() {
    timespec now{};
    if (clock_gettime(CLOCK_MONOTONIC,&now)) throw std::runtime_error("monotonic_clock_unavailable");
    return static_cast<int64_t>(now.tv_sec)*1'000'000'000LL+now.tv_nsec;
}
bool validPath(const std::string& path) {
    constexpr const char* prefix="/dev/input/event";
    if (path.rfind(prefix,0)!=0 || path.size()<=16 || path.size()>20) return false;
    for (size_t i=16; i<path.size(); ++i) if (path[i]<'0'||path[i]>'9') return false;
    return true;
}
void shellOnly() {
    if (getuid()!=2000 || geteuid()!=2000) throw std::runtime_error("authorized_shell_UID_2000_required");
}
std::string quote(const std::string& value) {
    std::string result="\"";
    auto escape=[&result](uint32_t code) {
        char encoded[7]; std::snprintf(encoded,sizeof encoded,"\\u%04x",code); result+=encoded;
    };
    for (size_t index=0; index<value.size();) {
        const unsigned char c=value[index++];
        if (c=='"'||c=='\\') { result+='\\'; result+=static_cast<char>(c); }
        else if (c<32) escape(c);
        else if (c<128) result+=static_cast<char>(c);
        else {
            // JSON is ASCII escaped UTF-16, so NewStringUTF never receives raw four-byte
            // UTF-8 or invalid bytes from a device-provided name.
            unsigned remaining=0; uint32_t code=0,minimum=0;
            if ((c&0xe0)==0xc0) {remaining=1;code=c&0x1f;minimum=0x80;}
            else if ((c&0xf0)==0xe0) {remaining=2;code=c&0x0f;minimum=0x800;}
            else if ((c&0xf8)==0xf0) {remaining=3;code=c&0x07;minimum=0x10000;}
            bool valid=remaining>0 && index+remaining<=value.size();
            for (unsigned extra=0;valid && extra<remaining;++extra) {
                const unsigned char next=value[index+extra];
                if ((next&0xc0)!=0x80) valid=false;
                else code=(code<<6)|(next&0x3f);
            }
            if (!valid || code<minimum || code>0x10ffff || (code>=0xd800 && code<=0xdfff)) {escape(0xfffd);continue;}
            index+=remaining;
            if (code<=0xffff) escape(code);
            else {code-=0x10000;escape(0xd800+(code>>10));escape(0xdc00+(code&0x3ff));}
        }
    }
    return result+'"';
}
int buttons(const KeyBits& held) {
    return (bit(held,BTN_LEFT)?1:0) | (bit(held,BTN_RIGHT)?2:0) | (bit(held,BTN_MIDDLE)?4:0) |
        ((bit(held,BTN_SIDE)||bit(held,BTN_BACK))?8:0) |
        ((bit(held,BTN_EXTRA)||bit(held,BTN_FORWARD))?16:0);
}
bool anyHeld(const KeyBits& held) {
    return std::any_of(held.begin(),held.end(),[](unsigned long value){ return value!=0; });
}
struct PendingKey { int code; int state; int64_t time; };
struct Device {
    int fd=-1;
    bool grabbed=false;
    std::string path,name,physicalId,uniqueId;
    input_id identity{};
    bool mouse=false,keyboard=false;
    int initialButtons=0;
    KeyBits held{};
    KeyBits keyCapabilities{};
    RelBits relativeCapabilities{};
    AbsBits absoluteCapabilities{};
    int reportedButtons=0;
    int64_t dx=0,dy=0,vertical=0,horizontal=0,highVertical=0,highHorizontal=0;
    bool highVerticalSeen=false,highHorizontalSeen=false,mouseDirty=false;
    std::vector<PendingKey> pending;
    std::string fingerprint() const {
        constexpr const char* hex="0123456789abcdef";
        auto encoded=[&](const std::string& value) {
            std::string result;
            for (unsigned char c:value) { result+=hex[c>>4];result+=hex[c&15]; }
            return result;
        };
        auto bits=[](const auto& values) {
            std::string result;
            for (auto value:values) result+=std::to_string(value)+",";
            return result;
        };
        return std::to_string(identity.bustype)+"/"+std::to_string(identity.vendor)+"/"+std::to_string(identity.product)+
            "/"+std::to_string(identity.version)+"/"+(mouse?"1":"0")+"/"+(keyboard?"1":"0")+"/"+encoded(name)+
            "/"+encoded(physicalId)+"/"+encoded(uniqueId)+"/"+bits(keyCapabilities)+"/"+bits(relativeCapabilities)+"/"+bits(absoluteCapabilities);
    }
    std::string json() const {
        auto codes=[](const auto& capabilities,unsigned first,unsigned end,const auto& accepts) {
            std::string result="[";bool next=false;
            for (unsigned code=first;code<end;++code) if (accepts(code) && bit(capabilities,code)) {
                if (next) result+=',';
                next=true;result+=std::to_string(code);
            }
            return result+"]";
        };
        return "{\"path\":"+quote(path)+",\"name\":"+quote(name)+",\"deviceId\":"+path.substr(16)+
            ",\"bus\":"+std::to_string(identity.bustype)+",\"vendorId\":"+std::to_string(identity.vendor)+
            ",\"productId\":"+std::to_string(identity.product)+",\"mouse\":"+(mouse?"true":"false")+
            ",\"keyboard\":"+(keyboard?"true":"false")+",\"initialButtons\":"+std::to_string(initialButtons)+
            ",\"keyCodes\":"+codes(keyCapabilities,1,KEY_CNT,[](unsigned code){return uwu::keyboardCode(code);})+
            ",\"relativeAxes\":"+codes(relativeCapabilities,0,REL_CNT,[](unsigned){return true;})+
            ",\"mouseButtonCodes\":"+codes(keyCapabilities,0x110,0x120,[](unsigned){return true;})+
            ",\"physicalId\":"+quote(physicalId)+",\"uniqueId\":"+quote(uniqueId)+
            ",\"identityFingerprint\":"+quote(fingerprint())+"}";
    }
};
bool inspect(int fd,Device& device) {
    struct stat st{};
    if (fstat(fd,&st) || !S_ISCHR(st.st_mode) || ioctl(fd,EVIOCGID,&device.identity)) return false;
    if (device.identity.bustype!=BUS_USB && device.identity.bustype!=BUS_BLUETOOTH) return false;
    char name[256]{};
    if (ioctl(fd,EVIOCGNAME(sizeof name-1),name)<=0 || !name[0]) return false;
    device.name=name;
    char physical[256]{},unique[256]{};
    if (ioctl(fd,EVIOCGPHYS(sizeof physical-1),physical)>0) device.physicalId=physical;
    if (ioctl(fd,EVIOCGUNIQ(sizeof unique-1),unique)>0) device.uniqueId=unique;
    if (ioctl(fd,EVIOCGBIT(EV_KEY,sizeof device.keyCapabilities),device.keyCapabilities.data())<0) return false;
    (void)ioctl(fd,EVIOCGBIT(EV_REL,sizeof device.relativeCapabilities),device.relativeCapabilities.data());
    (void)ioctl(fd,EVIOCGBIT(EV_ABS,sizeof device.absoluteCapabilities),device.absoluteCapabilities.data());
    const auto roles=uwu::classifyDevice(device.identity.bustype,
        [&](unsigned code){return bit(device.keyCapabilities,code);},
        [&](unsigned code){return bit(device.relativeCapabilities,code);},
        [&](unsigned code){return bit(device.absoluteCapabilities,code);});
    device.mouse=roles.mouse;device.keyboard=roles.keyboard;
    return device.mouse || device.keyboard;
}
void releasedBaseline(Device& device) {
    device.held.fill(0);
    if (ioctl(device.fd,EVIOCGKEY(sizeof device.held),device.held.data())<0)
        throw std::runtime_error("cannot_verify_released_input");
    device.initialButtons=buttons(device.held);
    if (anyHeld(device.held)) throw std::runtime_error("release_all_external_keys_and_buttons_before_capture");
}
std::vector<std::string> eventPaths() {
    std::vector<std::string> paths;
    DIR* directory=opendir("/dev/input");
    if (!directory) throw std::runtime_error("cannot_list_input_devices");
    while (dirent* item=readdir(directory)) {
        std::string path="/dev/input/"+std::string(item->d_name);
        if (validPath(path)) paths.push_back(path);
        if (paths.size()>1024) { closedir(directory); throw std::runtime_error("too_many_input_devices"); }
    }
    closedir(directory);
    std::sort(paths.begin(),paths.end());
    return paths;
}
std::string inventory() {
    shellOnly();
    std::string result="[";
    bool first=true;
    for (const auto& path : eventPaths()) {
        const int fd=open(path.c_str(),O_RDONLY|O_NONBLOCK|O_CLOEXEC|O_NOFOLLOW);
        if (fd<0) continue;
        Device device; device.path=path;
        const bool accepted=inspect(fd,device);
        close(fd);
        if (!accepted) continue;
        if (!first) result+=',';
        first=false; result+=device.json();
    }
    return result+"]";
}

struct Capture : std::enable_shared_from_this<Capture> {
    uint64_t id;
    JavaVM* vm;
    jobject receiver;
    jmethodID frameMethod,lostMethod,batchMethod;
    std::vector<Device> devices;
    int wakeFd=-1;
    int64_t leaseNanos;
    bool allowMouse,allowKeyboard;
    uwu::MouseMotionBatcher mouseBatcher;
    std::atomic<int> requestedMouseRateHz;
    std::atomic<uint64_t> rateRevision{0};
    uint64_t appliedRateRevision=0;
    std::atomic<uint64_t> rawMouseSamples{0},rawInputRecords{0},nativeReadCalls{0},mouseEdgeFrames{0},keyFrames{0};
    int64_t armedAt=0;
    std::atomic<int64_t> deadline{0};
    std::atomic<bool> stopRequested{false},running{false},notified{false};
    std::mutex ioMutex,reasonMutex,completionMutex,joinMutex;
    std::condition_variable completion,watchWake;
    std::atomic<bool> readerDone{false},watcherDone{false};
    std::string reason;
    std::thread reader,watcher;

    Capture(uint64_t value,JNIEnv* env,jobject owner,int leaseMillis,bool mouse,bool keyboard,int mouseRateHz):id(value),receiver(nullptr),leaseNanos(leaseMillis*1'000'000LL),allowMouse(mouse),allowKeyboard(keyboard),mouseBatcher(mouseRateHz),requestedMouseRateHz(mouseRateHz) {
        env->GetJavaVM(&vm);
        jclass type=env->GetObjectClass(owner);
        frameMethod=env->GetMethodID(type,"onNativeFrame","(JIJFFFFIII)V");
        lostMethod=env->GetMethodID(type,"onNativeLost","(JLjava/lang/String;)V");
        batchMethod=env->GetMethodID(type,"onNativeMotionBatch","(J[I[J[F[FFF)V");
        env->DeleteLocalRef(type);
        if (!frameMethod || !lostMethod || !batchMethod) throw std::runtime_error("native_callback_contract_unavailable");
        receiver=env->NewGlobalRef(owner);
        if (!receiver) throw std::runtime_error("native_callback_reference_unavailable");
    }
    ~Capture() {
        releaseAndClose();
        if (wakeFd>=0) close(wakeFd);
        JNIEnv* env=nullptr;
        bool attached=vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)==JNI_EDETACHED;
        if (attached && vm->AttachCurrentThread(&env,nullptr)!=JNI_OK) return;
        if (env && receiver) env->DeleteGlobalRef(receiver);
        if (attached) vm->DetachCurrentThread();
    }
    void releaseGrabs() {
        std::lock_guard<std::mutex> lock(ioMutex);
        for (auto& device: devices) if (device.fd>=0 && device.grabbed) {
            if (ioctl(device.fd,EVIOCGRAB,0)) {
                // Closing is the fallback release. Reads and this exceptional close share
                // ioMutex, so a recycled descriptor can never be used by the reader.
                close(device.fd); device.fd=-1;
            }
            device.grabbed=false;
        }
    }
    void releaseAndClose() {
        std::lock_guard<std::mutex> lock(ioMutex);
        for (auto& device: devices) if (device.fd>=0) {
            if (device.grabbed) ioctl(device.fd,EVIOCGRAB,0);
            close(device.fd); device.fd=-1; device.grabbed=false;
        }
    }
    void stop(const std::string& why) {
        {
            std::lock_guard<std::mutex> lock(reasonMutex);
            if (reason.empty()) reason=why;
        }
        stopRequested=true;
        // Separate watchdog can relinquish grabs even if a Java/Binder callback stalls.
        // Normal closing belongs to the reader. If release ioctl fails, a serialized
        // close is mandatory even when the Java callback has not returned.
        releaseGrabs();
        if (wakeFd>=0) { uint64_t signal=1; (void)write(wakeFd,&signal,sizeof signal); }
        watchWake.notify_all();
    }
    std::string reasonText() { std::lock_guard<std::mutex> lock(reasonMutex); return reason; }
    void notifyLost(JNIEnv* env) {
        const auto why=reasonText();
        if (why.empty() || why=="stopped" || notified.exchange(true)) return;
        jstring message=env->NewStringUTF(why.c_str());
        if (!message) { env->ExceptionClear(); return; }
        env->CallVoidMethod(receiver,lostMethod,static_cast<jlong>(id),message);
        env->DeleteLocalRef(message);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    void acquire(const std::vector<std::string>& paths,const std::vector<std::string>& identities) {
        devices.reserve(paths.size());
        for (size_t slot=0;slot<paths.size();++slot) {
            const auto& path=paths[slot];
            if (!validPath(path)) throw std::runtime_error("invalid_input_device_path");
            Device device; device.path=path;
            device.fd=open(path.c_str(),O_RDONLY|O_NONBLOCK|O_CLOEXEC|O_NOFOLLOW);
            if (device.fd<0) throw std::runtime_error("cannot_open_external_input_errno_"+std::to_string(errno));
            if (!inspect(device.fd,device)) { close(device.fd); throw std::runtime_error("input_identity_is_not_external_mouse_or_keyboard"); }
            if (device.fingerprint()!=identities[slot]) {
                close(device.fd); throw std::runtime_error("selected_input_identity_changed_before_acquisition");
            }
            if ((device.mouse&&!allowMouse) || (device.keyboard&&!allowKeyboard)) {
                close(device.fd); throw std::runtime_error("mixed_role_input_node_cannot_be_partially_grabbed");
            }
            devices.push_back(std::move(device));
            auto& opened=devices.back();
            opened.pending.reserve(128);
            const int clock=CLOCK_MONOTONIC;
            if (ioctl(opened.fd,EVIOCSCLOCKID,&clock)) throw std::runtime_error("input_monotonic_clock_required");
            releasedBaseline(opened);
        }
        for (auto& device:devices) {
            if (ioctl(device.fd,EVIOCGRAB,1)) throw std::runtime_error("exclusive_input_grab_denied_errno_"+std::to_string(errno));
            device.grabbed=true;
        }
        // Discard pre-acquisition samples; do not replay held edges or clock-change markers.
        input_event discarded[64];
        for (auto& device:devices) {
            size_t drained=0;
            for (;;) {
                const ssize_t bytes=read(device.fd,discarded,sizeof discarded);
                if (bytes>0) {
                    drained+=static_cast<size_t>(bytes)/sizeof(input_event);
                    if (drained>4096) throw std::runtime_error("input_startup_queue_not_quiet");
                    continue;
                }
                if (bytes<0 && errno==EINTR) continue;
                if (bytes<0 && (errno==EAGAIN || errno==EWOULDBLOCK)) break;
                throw std::runtime_error("input_disconnected_during_acquisition");
            }
            releasedBaseline(device);
        }
        wakeFd=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
        if (wakeFd<0) throw std::runtime_error("input_stop_signal_unavailable");
        armedAt=monotonic(); deadline=armedAt+leaseNanos;
    }
    bool emit(JNIEnv* env,size_t slot,int64_t time,float dx,float dy,float vertical,float horizontal,int buttonMask,int scan,int state) {
        if (stopRequested) return false;
        if (monotonic()>=deadline.load()) { stop("lease_expired"); return false; }
        env->CallVoidMethod(receiver,frameMethod,static_cast<jlong>(id),static_cast<jint>(slot),static_cast<jlong>(time),dx,dy,vertical,horizontal,buttonMask,scan,state);
        if (env->ExceptionCheck()) { env->ExceptionClear(); stop("input_consumer_callback_failed"); return false; }
        return !stopRequested;
    }
    bool emitMouseBatch(JNIEnv* env,const uwu::RawMouseSample* samples,size_t count) {
        if (stopRequested) return false;
        if (monotonic()>=deadline.load()) { stop("lease_expired");return false; }
        if (count==1) {
            const auto& sample=samples[0];
            return emit(env,sample.slot,sample.time,sample.dx,sample.dy,sample.wheel,sample.horizontalWheel,
                devices[sample.slot].reportedButtons,-1,-1);
        }
        std::array<jint,uwu::MouseMotionBatcher::kCapacity> slots{};
        std::array<jlong,uwu::MouseMotionBatcher::kCapacity> times{};
        std::array<jfloat,uwu::MouseMotionBatcher::kCapacity> xs{},ys{};
        double wheel=0,horizontal=0;
        for (size_t index=0;index<count;++index) {
            slots[index]=samples[index].slot;times[index]=samples[index].time;
            xs[index]=samples[index].dx;ys[index]=samples[index].dy;
            wheel+=samples[index].wheel;horizontal+=samples[index].horizontalWheel;
        }
        auto javaSlots=env->NewIntArray(static_cast<jsize>(count));
        auto javaTimes=env->NewLongArray(static_cast<jsize>(count));
        auto javaXs=env->NewFloatArray(static_cast<jsize>(count));
        auto javaYs=env->NewFloatArray(static_cast<jsize>(count));
        if (javaSlots && javaTimes && javaXs && javaYs) {
            env->SetIntArrayRegion(javaSlots,0,static_cast<jsize>(count),slots.data());
            env->SetLongArrayRegion(javaTimes,0,static_cast<jsize>(count),times.data());
            env->SetFloatArrayRegion(javaXs,0,static_cast<jsize>(count),xs.data());
            env->SetFloatArrayRegion(javaYs,0,static_cast<jsize>(count),ys.data());
            if (!env->ExceptionCheck()) env->CallVoidMethod(receiver,batchMethod,static_cast<jlong>(id),javaSlots,javaTimes,javaXs,javaYs,
                static_cast<jfloat>(wheel),static_cast<jfloat>(horizontal));
        }
        const bool failed=!javaSlots || !javaTimes || !javaXs || !javaYs || env->ExceptionCheck();
        if (javaSlots) env->DeleteLocalRef(javaSlots);
        if (javaTimes) env->DeleteLocalRef(javaTimes);
        if (javaXs) env->DeleteLocalRef(javaXs);
        if (javaYs) env->DeleteLocalRef(javaYs);
        if (failed) { env->ExceptionClear();stop("input_batch_consumer_failed");return false; }
        return !stopRequested;
    }
    bool flushMouse(JNIEnv* env,bool barrier=true) {
        return mouseBatcher.flush(monotonic(),[&](const auto* samples,size_t count){return emitMouseBatch(env,samples,count);},barrier);
    }
    bool applyMouseRate(JNIEnv* env) {
        const auto revision=rateRevision.load(std::memory_order_acquire);
        if (revision==appliedRateRevision) return true;
        if (!mouseBatcher.configure(requestedMouseRateHz.load(std::memory_order_acquire),monotonic(),
            [&](const auto* samples,size_t count){return emitMouseBatch(env,samples,count);})) return false;
        appliedRateRevision=revision;
        return true;
    }
    bool configureMouseRate(int rate) {
        if (rate<0 || rate>1000 || stopRequested || !running || monotonic()>=deadline.load()) return false;
        if (rate==requestedMouseRateHz.load(std::memory_order_acquire)) return true;
        requestedMouseRateHz.store(rate,std::memory_order_release);
        rateRevision.fetch_add(1,std::memory_order_release);
        uint64_t signal=1;
        if (write(wakeFd,&signal,sizeof signal)<0 && errno!=EAGAIN) { stop("input_rate_signal_failed");return false; }
        return true;
    }
    bool event(JNIEnv* env,size_t slot,const input_event& input) {
        auto& device=devices[slot];
        const int64_t time=static_cast<int64_t>(input.time.tv_sec)*1'000'000'000LL+input.time.tv_usec*1000LL;
        if (time<armedAt) return true;
        if (input.type==EV_SYN && input.code==SYN_DROPPED) { stop("input_SYN_DROPPED"); return false; }
        if (input.type==EV_KEY) {
            if (input.value<0 || input.value>2 || input.code>=KEY_CNT) { stop("invalid_input_key_state"); return false; }
            keyBit(device.held,input.code,input.value!=0);
            if (device.mouse && input.code>=BTN_LEFT && input.code<=BTN_TASK) device.mouseDirty=true;
            if (device.keyboard && uwu::keyboardCode(input.code)) {
                if (device.pending.size()>=128) { stop("input_key_frame_overflow"); return false; }
                device.pending.push_back({input.code,input.value,time});
            }
        } else if (device.mouse && input.type==EV_REL) {
            switch (input.code) {
                case REL_X: device.dx+=input.value; device.mouseDirty=true; break;
                case REL_Y: device.dy+=input.value; device.mouseDirty=true; break;
                case REL_WHEEL: device.vertical+=input.value; device.mouseDirty=true; break;
                case REL_HWHEEL: device.horizontal+=input.value; device.mouseDirty=true; break;
                case REL_WHEEL_HI_RES: device.highVertical+=input.value; device.highVerticalSeen=true; device.mouseDirty=true; break;
                case REL_HWHEEL_HI_RES: device.highHorizontal+=input.value; device.highHorizontalSeen=true; device.mouseDirty=true; break;
            }
        } else if (input.type==EV_SYN && input.code==SYN_REPORT) {
            if (device.mouseDirty) {
                rawMouseSamples.fetch_add(1,std::memory_order_relaxed);
                const float vertical=device.highVerticalSeen?device.highVertical/120.f:static_cast<float>(device.vertical);
                const float horizontal=device.highHorizontalSeen?device.highHorizontal/120.f:static_cast<float>(device.horizontal);
                const int currentButtons=buttons(device.held);
                if (currentButtons!=device.reportedButtons) {
                    // Movement already observed belongs before this button edge, including
                    // when this is an auxiliary mouse interface on a USB keyboard.
                    if (!flushMouse(env)) return false;
                    device.reportedButtons=currentButtons;
                    mouseEdgeFrames.fetch_add(1,std::memory_order_relaxed);
                    if (!emit(env,slot,time,static_cast<float>(device.dx),static_cast<float>(device.dy),vertical,horizontal,currentButtons,-1,-1)) return false;
                } else if (device.dx!=0 || device.dy!=0 || vertical!=0 || horizontal!=0) {
                    const uwu::RawMouseSample sample{static_cast<int>(slot),time,static_cast<float>(device.dx),static_cast<float>(device.dy),vertical,horizontal};
                    if (!mouseBatcher.offer(sample,monotonic(),[&](const auto* samples,size_t count){return emitMouseBatch(env,samples,count);})) {
                        if (!stopRequested) stop("input_motion_batch_overflow_or_consumer_failed");
                        return false;
                    }
                }
            }
            device.dx=0;device.dy=0;device.vertical=0;device.horizontal=0;device.highVertical=0;device.highHorizontal=0;
            device.highVerticalSeen=false;device.highHorizontalSeen=false;device.mouseDirty=false;
            for (const auto& key:device.pending) {
                if (!flushMouse(env)) return false;
                keyFrames.fetch_add(1,std::memory_order_relaxed);
                if (!emit(env,slot,key.time,0,0,0,0,buttons(device.held),key.code,key.state)) return false;
            }
            device.pending.clear();
        }
        return true;
    }
    void runReader() {
        JNIEnv* env=nullptr;
        const bool attached=vm->AttachCurrentThread(&env,nullptr)==JNI_OK;
        if (!attached) stop("native_reader_JVM_attach_failed");
        try {
            std::vector<pollfd> watched; watched.reserve(devices.size()+1);
            watched.push_back({wakeFd,POLLIN,0});
            {
                std::lock_guard<std::mutex> lock(ioMutex);
                for (const auto& device:devices) watched.push_back({device.fd,POLLIN,0});
            }
            input_event batch[64];
            while (attached && !stopRequested) {
                if (!applyMouseRate(env)) break;
                const auto emitBatch=[&](const auto* samples,size_t count){return emitMouseBatch(env,samples,count);};
                if (!mouseBatcher.flushDue(monotonic(),emitBatch)) break;
                const int64_t now=monotonic();
                const int64_t remaining=deadline.load()-now;
                if (remaining<=0) { stop("lease_expired"); break; }
                int64_t wait=std::min<int64_t>(remaining,100'000'000LL);
                const int64_t movementWait=mouseBatcher.waitNanos(now);
                if (movementWait>=0) wait=std::min(wait,movementWait);
                const timespec timeout{static_cast<time_t>(wait/1'000'000'000LL),static_cast<long>(wait%1'000'000'000LL)};
                const int ready=ppoll(watched.data(),watched.size(),&timeout,nullptr);
                if (ready<0) { if (errno==EINTR) continue; stop("input_poll_failed"); break; }
                if (ready==0) continue;
                if (watched[0].revents) {
                    uint64_t signal;
                    while (read(wakeFd,&signal,sizeof signal)<0 && errno==EINTR) {}
                    if (stopRequested) break;
                    if (!applyMouseRate(env)) break;
                }
                for (size_t slot=0;slot<devices.size() && !stopRequested;++slot) {
                    const short revents=watched[slot+1].revents;
                    if (revents&(POLLERR|POLLHUP|POLLNVAL)) { stop("input_device_disconnected"); break; }
                    if (!(revents&POLLIN)) continue;
                    // One bounded read per ready node preserves fairness and stop/lease checks.
                    ssize_t bytes;
                    {
                        std::lock_guard<std::mutex> lock(ioMutex);
                        if (stopRequested) break;
                        bytes=read(devices[slot].fd,batch,sizeof batch);
                    }
                    nativeReadCalls.fetch_add(1,std::memory_order_relaxed);
                    if (bytes<0 && (errno==EAGAIN || errno==EWOULDBLOCK || errno==EINTR)) continue;
                    if (bytes<=0 || bytes%sizeof(input_event)) { stop("input_read_failed_or_partial"); break; }
                    rawInputRecords.fetch_add(static_cast<size_t>(bytes)/sizeof(input_event),std::memory_order_relaxed);
                    for (size_t index=0;index<static_cast<size_t>(bytes)/sizeof(input_event) && !stopRequested;++index)
                        if (!event(env,slot,batch[index])) break;
                    // Unrestricted mode uses only work already present in this bounded
                    // read. It introduces no timer or polling-rate ceiling.
                    if (!stopRequested && mouseBatcher.rate()==0 && !flushMouse(env,false)) break;
                }
            }
        } catch (...) { stop("native_reader_failed"); }
        mouseBatcher.discard();
        releaseAndClose(); running=false;
        watchWake.notify_all();
        if (attached) { notifyLost(env); vm->DetachCurrentThread(); }
        { std::lock_guard<std::mutex> lock(completionMutex); readerDone=true; }
        completion.notify_all();
    }
    void runWatcher() {
        while (!stopRequested) {
            std::unique_lock<std::mutex> lock(completionMutex);
            watchWake.wait_for(lock,std::chrono::milliseconds(25),[this]{return stopRequested.load();});
            lock.unlock();
            if (stopRequested) break;
            try { if (monotonic()>=deadline.load()) { stop("lease_expired"); break; } }
            catch (...) { stop("watchdog_clock_failed"); break; }
        }
        // A blocked Binder callback cannot prevent releasing exclusive grabs or reporting loss.
        JNIEnv* env=nullptr;
        if (vm->AttachCurrentThread(&env,nullptr)==JNI_OK) { notifyLost(env); vm->DetachCurrentThread(); }
        { std::lock_guard<std::mutex> lock(completionMutex); watcherDone=true; }
        completion.notify_all();
    }
    void launch() {
        running=true;
        auto self=shared_from_this();
        try {
            reader=std::thread([self]{self->runReader();});
            watcher=std::thread([self]{self->runWatcher();});
        } catch (...) { stop("native_thread_start_failed"); join(); throw; }
    }
    void join() {
        std::lock_guard<std::mutex> serial(joinMutex);
        {
            std::unique_lock<std::mutex> lock(completionMutex);
            completion.wait_for(lock,std::chrono::milliseconds(1000),[this]{return (!reader.joinable() || readerDone) && (!watcher.joinable() || watcherDone);});
        }
        if (reader.joinable()) { if (readerDone && reader.get_id()!=std::this_thread::get_id()) reader.join(); else reader.detach(); }
        if (watcher.joinable()) { if (watcherDone && watcher.get_id()!=std::this_thread::get_id()) watcher.join(); else watcher.detach(); }
    }
    bool renew() {
        if (stopRequested || monotonic()>=deadline.load()) { stop("lease_expired"); return false; }
        deadline=monotonic()+leaseNanos;
        return !stopRequested;
    }
    std::string json() {
        const auto delivered=mouseBatcher.deliveredSamples.load();
        const auto batches=mouseBatcher.batches.load();
        std::string result="{\"running\":"+std::string(running && !stopRequested && monotonic()<deadline.load()?"true":"false")+
            ",\"leaseExpiresMonotonicNanos\":"+std::to_string(deadline.load())+",\"reason\":"+quote(reasonText())+
            ",\"mouseRateHz\":"+std::to_string(mouseBatcher.rate())+",\"requestedMouseRateHz\":"+std::to_string(requestedMouseRateHz.load())+
            ",\"rawMouseSamples\":"+std::to_string(rawMouseSamples.load())+",\"rawMotionSamples\":"+std::to_string(mouseBatcher.rawSamples.load())+
            ",\"deliveredMotionSamples\":"+std::to_string(mouseBatcher.deliveredSamples.load())+",\"mouseBatches\":"+std::to_string(mouseBatcher.batches.load())+
            ",\"coalescedMouseSamples\":"+std::to_string(delivered>=batches?delivered-batches:0)+
            ",\"barrierFlushes\":"+std::to_string(mouseBatcher.barrierFlushes.load())+",\"discardedMotionSamples\":"+std::to_string(mouseBatcher.discardedSamples.load())+
            ",\"pendingMotionSamples\":"+std::to_string(mouseBatcher.pendingSamples.load())+",\"maxMotionBatchSamples\":"+std::to_string(mouseBatcher.maxBatchSamples.load())+
            ",\"rawInputRecords\":"+std::to_string(rawInputRecords.load())+",\"nativeReadCalls\":"+std::to_string(nativeReadCalls.load())+
            ",\"mouseEdgeFrames\":"+std::to_string(mouseEdgeFrames.load())+",\"keyFrames\":"+std::to_string(keyFrames.load())+",\"devices\":[";
        for (size_t index=0;index<devices.size();++index) { if (index) result+=','; result+=devices[index].json(); }
        return result+"]}";
    }
};
std::mutex registryMutex;
std::unordered_map<uint64_t,std::shared_ptr<Capture>> registry;
std::atomic<uint64_t> nextId{1};
std::shared_ptr<Capture> lookup(jlong id) {
    std::lock_guard<std::mutex> lock(registryMutex);
    const auto found=registry.find(static_cast<uint64_t>(id));
    return found==registry.end()?nullptr:found->second;
}
void javaFailure(JNIEnv* env,const char* message) {
    if (env->ExceptionCheck()) env->ExceptionClear();
    jclass type=env->FindClass("java/lang/IllegalStateException");
    if (type) { env->ThrowNew(type,message); env->DeleteLocalRef(type); }
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_mobimapper_capture_nativeinput_ShellEvdevCapture_nativeInventory(JNIEnv* env,jobject) {
    try { return env->NewStringUTF(inventory().c_str()); }
    catch (const std::exception& failure) { javaFailure(env,failure.what()); return nullptr; }
}
extern "C" JNIEXPORT jlong JNICALL
Java_dev_mobimapper_capture_nativeinput_ShellEvdevCapture_nativeStart(JNIEnv* env,jobject owner,jobjectArray paths,jobjectArray expectedIdentities,jint leaseMillis,jboolean mouse,jboolean keyboard,jint mouseRateHz) {
    try {
        shellOnly();
        const jsize count=env->GetArrayLength(paths);
        if (count<=0 || count>16 || env->GetArrayLength(expectedIdentities)!=count || leaseMillis<250 || leaseMillis>2000 || (!mouse&&!keyboard) || mouseRateHz<0 || mouseRateHz>1000) throw std::runtime_error("invalid_capture_configuration");
        std::vector<std::string> selected,identities; selected.reserve(count); identities.reserve(count);
        for (jsize index=0;index<count;++index) {
            auto path=static_cast<jstring>(env->GetObjectArrayElement(paths,index));
            if (!path) throw std::runtime_error("null_device_path");
            const char* value=env->GetStringUTFChars(path,nullptr);
            if (!value) { env->DeleteLocalRef(path); throw std::runtime_error("device_path_unavailable"); }
            std::string copy=value; env->ReleaseStringUTFChars(path,value); env->DeleteLocalRef(path);
            if (std::find(selected.begin(),selected.end(),copy)!=selected.end()) throw std::runtime_error("duplicate_device_path");
            selected.push_back(std::move(copy));
            auto identity=static_cast<jstring>(env->GetObjectArrayElement(expectedIdentities,index));
            if (!identity) throw std::runtime_error("null_device_identity");
            const char* identityText=env->GetStringUTFChars(identity,nullptr);
            if (!identityText) { env->DeleteLocalRef(identity); throw std::runtime_error("device_identity_unavailable"); }
            identities.emplace_back(identityText);
            env->ReleaseStringUTFChars(identity,identityText); env->DeleteLocalRef(identity);
        }
        std::lock_guard<std::mutex> lock(registryMutex);
        if (!registry.empty()) throw std::runtime_error("stop_existing_native_capture_first");
        const uint64_t id=nextId++;
        auto capture=std::make_shared<Capture>(id,env,owner,leaseMillis,mouse,keyboard,mouseRateHz);
        capture->acquire(selected,identities);
        registry.emplace(id,capture);
        try { capture->launch(); } catch (...) { registry.erase(id); throw; }
        return static_cast<jlong>(id);
    } catch (const std::exception& failure) { javaFailure(env,failure.what()); return 0; }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_mobimapper_capture_nativeinput_ShellEvdevCapture_nativeConfigureMouseRate(JNIEnv* env,jobject,jlong id,jint rateHz) {
    try { shellOnly();auto capture=lookup(id);return capture && capture->configureMouseRate(rateHz); }
    catch (const std::exception& failure) { javaFailure(env,failure.what());return false; }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_mobimapper_capture_nativeinput_ShellEvdevCapture_nativeRenew(JNIEnv* env,jobject,jlong id) {
    try { shellOnly(); auto capture=lookup(id); return capture && capture->renew(); }
    catch (const std::exception& failure) { javaFailure(env,failure.what()); return false; }
}
extern "C" JNIEXPORT void JNICALL
Java_dev_mobimapper_capture_nativeinput_ShellEvdevCapture_nativeStop(JNIEnv* env,jobject,jlong id) {
    try {
        shellOnly(); std::shared_ptr<Capture> capture;
        { std::lock_guard<std::mutex> lock(registryMutex); auto found=registry.find(id); if(found==registry.end()) return; capture=found->second; registry.erase(found); }
        capture->stop("stopped"); capture->join();
    } catch (const std::exception& failure) { javaFailure(env,failure.what()); }
}
extern "C" JNIEXPORT jstring JNICALL
Java_dev_mobimapper_capture_nativeinput_ShellEvdevCapture_nativeStatus(JNIEnv* env,jobject,jlong id) {
    try {
        shellOnly(); auto capture=lookup(id);
        return env->NewStringUTF(capture?capture->json().c_str():"{\"running\":false,\"reason\":\"capture_closed\",\"devices\":[]}");
    } catch (const std::exception& failure) { javaFailure(env,failure.what()); return nullptr; }
}
