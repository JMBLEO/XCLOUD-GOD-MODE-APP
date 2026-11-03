#pragma once
#include <cstdint>

extern "C" {
    bool initLowLatencyAudio();
    void stopAudioEngine();
    void injectAudioFrames(const float* data, int numFrames);
    void syncAudioClock(int64_t audioUs, int64_t videoUs);
    int64_t getInternalAudioClockUs();
}
