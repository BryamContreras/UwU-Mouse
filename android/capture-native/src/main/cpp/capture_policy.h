#pragma once

#include <algorithm>
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <stdexcept>

namespace uwu {

// Linux input-event-codes ranges. Keep button ranges out of keyboard forwarding.
inline bool keyboardCode(unsigned code) {
    return (code > 0 && code < 0x100) ||
        (code >= 0x160 && code < 0x220) ||
        (code >= 0x230 && code < 0x2c0) ||
        (code >= 0x2e8 && code <= 0x2ff);
}

inline bool powerOnlyCode(unsigned code) {
    return code == 116 || code == 142 || code == 143 || code == 205 || code == 408;
}

struct DeviceRoles { bool mouse=false,keyboard=false; };

/** Capability-based classification: no device names, vendors or complete QWERTY requirement. */
template<class Key,class Relative,class Absolute>
DeviceRoles classifyDevice(unsigned bus,const Key& key,const Relative& relative,const Absolute& absolute) {
    if (bus != 3 && bus != 5) return {};
    if (absolute(0x35) || absolute(0x36)) return {}; // Multitouch positions.
    bool mouseButtons=false,keyboard=false,gamepad=false;
    for (unsigned code=0x110;code<0x120;++code) mouseButtons=mouseButtons || key(code);
    for (unsigned code=0x120;code<0x140;++code) gamepad=gamepad || key(code);
    for (unsigned code=1;code<=0x2ff;++code) {
        if (keyboardCode(code) && !powerOnlyCode(code) && code!=240 && key(code)) { keyboard=true;break; }
    }
    // A gamepad's optional keys or relative axes do not authorize grabbing its input.
    if (gamepad) return {};
    const bool relativeXY=relative(0) && relative(1);
    const bool absoluteXY=absolute(0) || absolute(1);
    return {relativeXY || (mouseButtons && !absoluteXY),keyboard};
}

struct RawMouseSample {
    int slot=0;
    int64_t time=0;
    float dx=0,dy=0,wheel=0,horizontalWheel=0;
};

/** Single-reader buffer; only diagnostic counters are read from other threads. */
class MouseMotionBatcher {
public:
    static constexpr size_t kCapacity=256;
    explicit MouseMotionBatcher(int rate=0) : rateHz_(rate) {
        if (rate<0 || rate>1000) throw std::invalid_argument("invalid_mouse_capture_rate");
    }

    template<class Emit>
    bool offer(const RawMouseSample& sample,int64_t now,const Emit& emit) {
        if (count_==kCapacity) return false;
        samples_[count_++]=sample;
        rawSamples.fetch_add(1,std::memory_order_relaxed);
        pendingSamples.store(count_,std::memory_order_relaxed);
        auto previous=maxBatchSamples.load(std::memory_order_relaxed);
        while (previous<count_ && !maxBatchSamples.compare_exchange_weak(previous,count_,std::memory_order_relaxed)) {}
        return rateHz_.load(std::memory_order_relaxed)==0 || flushDue(now,emit);
    }

    template<class Emit>
    bool flushDue(int64_t now,const Emit& emit) {
        return count_==0 || rateHz_.load(std::memory_order_relaxed)==0 ||
            (hasPhase_ && now<nextDeadline_) || flush(now,emit,false);
    }

    template<class Emit>
    bool flush(int64_t now,const Emit& emit,bool barrier=true) {
        if (count_==0) return true;
        const auto count=count_;
        count_=0;pendingSamples.store(0,std::memory_order_relaxed);
        const int rate=rateHz_.load(std::memory_order_relaxed);
        if (rate>0) {
            const int64_t period=(1'000'000'000LL+rate-1)/rate;
            if (!hasPhase_) { hasPhase_=true;nextDeadline_=now+period; }
            else if (now>=nextDeadline_) nextDeadline_+=((now-nextDeadline_)/period+1)*period;
        }
        if (!emit(samples_.data(),count)) return false;
        deliveredSamples.fetch_add(count,std::memory_order_relaxed);
        batches.fetch_add(1,std::memory_order_relaxed);
        if (barrier) barrierFlushes.fetch_add(1,std::memory_order_relaxed);
        return true;
    }

    template<class Emit>
    bool configure(int rate,int64_t now,const Emit& emit) {
        if (rate<0 || rate>1000) return false;
        if (rate==rateHz_.load(std::memory_order_relaxed)) return true;
        if (!flush(now,emit)) return false;
        rateHz_.store(rate,std::memory_order_relaxed);
        hasPhase_=false;nextDeadline_=0;
        return true;
    }

    int64_t waitNanos(int64_t now) const {
        if (count_==0 || rateHz_.load(std::memory_order_relaxed)==0) return -1;
        return hasPhase_?std::max<int64_t>(0,nextDeadline_-now):0;
    }
    int rate() const { return rateHz_.load(std::memory_order_relaxed); }
    void discard() {
        discardedSamples.fetch_add(count_,std::memory_order_relaxed);
        count_=0;pendingSamples.store(0,std::memory_order_relaxed);
    }

    std::atomic<uint64_t> rawSamples{0},deliveredSamples{0},batches{0},barrierFlushes{0},discardedSamples{0};
    std::atomic<size_t> pendingSamples{0},maxBatchSamples{0};
private:
    std::array<RawMouseSample,kCapacity> samples_{};
    size_t count_=0;
    std::atomic<int> rateHz_;
    bool hasPhase_=false;
    int64_t nextDeadline_=0;
};
}
