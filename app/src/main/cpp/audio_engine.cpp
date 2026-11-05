#include "audio/GodModeAudioEngine.h"

// Clean bridge ensuring com.xcloud.godmode naming compliance without deprecated third-party markers
namespace GodMode::Audio {
    static GodModeAudioEngine* gSharedAudioEngine = nullptr;

    void setSharedEngine(GodModeAudioEngine* engine) {
        gSharedAudioEngine = engine;
    }

    GodModeAudioEngine* getSharedEngine() {
        return gSharedAudioEngine;
    }
}
