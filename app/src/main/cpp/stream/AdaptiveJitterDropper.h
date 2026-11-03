#ifndef ADAPTIVE_JITTER_DROPPER_H
#define ADAPTIVE_JITTER_DROPPER_H

#include <cstdint>
#include <chrono>
#include <android/log.h>

namespace godmode {

class AdaptiveJitterDropper {
public:
    AdaptiveJitterDropper(uint64_t maxLateUs = 90000) : maxLateUs(maxLateUs) {}

    bool shouldDropFrame(uint64_t expectedPtsUs, uint64_t currentClockUs) {
        if (expectedPtsUs < currentClockUs) {
            uint64_t drift = currentClockUs - expectedPtsUs;
            if (drift > maxLateUs) {
                __android_log_print(ANDROID_LOG_WARN, "JitterDropper", "Dropping frame: drift %llu us > threshold %llu us", 
                                    (unsigned long long)drift, (unsigned long long)maxLateUs);
                return true;
            }
        }
        return false;
    }

private:
    uint64_t maxLateUs;
};

} // namespace godmode

#endif // ADAPTIVE_JITTER_DROPPER_H
