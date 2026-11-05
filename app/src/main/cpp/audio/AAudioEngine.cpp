#include "AAudioEngine.h"
#include "GodModeAudioEngine.h"
#include <memory>

static std::unique_ptr<GodMode::Audio::GodModeAudioEngine> gGlobalAudioEngine;

extern "C" {
    bool initLowLatencyAudio() {
        if (!gGlobalAudioEngine) {
            gGlobalAudioEngine = std::make_unique<GodMode::Audio::GodModeAudioEngine>();
        }
        return gGlobalAudioEngine->start();
    }

    void stopAudioEngine() {
        if (gGlobalAudioEngine) {
            gGlobalAudioEngine->stop();
        }
    }

    void injectAudioFrames(const float* data, int numFrames) {
        if (gGlobalAudioEngine) {
            gGlobalAudioEngine->injectAudioFloat(data, numFrames);
        }
    }

    void syncAudioClock(int64_t audioUs, int64_t videoUs) {
        if (gGlobalAudioEngine) {
            gGlobalAudioEngine->syncAudioClock(audioUs, videoUs);
        }
    }

    int64_t getInternalAudioClockUs() {
        if (gGlobalAudioEngine) {
            return gGlobalAudioEngine->getInternalAudioClockUs();
        }
        return 0;
    }
}
