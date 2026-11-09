#pragma once

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <mutex>

namespace uwu {

/** The owner serializes renewal and stop under waitMutex, then notifies wake on stop. */
template<class Clock>
bool waitForLeaseExpiry(std::mutex& waitMutex,std::condition_variable& wake,
    const std::atomic<bool>& stopped,const std::atomic<int64_t>& deadline,const Clock& now) {
    std::unique_lock<std::mutex> lock(waitMutex);
    while (!stopped.load()) {
        const int64_t remaining=deadline.load()-now();
        if (remaining<=0) return true;
        // Recheck a renewed lease at its previous expiry, with no periodic ticker.
        wake.wait_for(lock,std::chrono::nanoseconds(remaining),[&]{return stopped.load();});
    }
    return false;
}

}
