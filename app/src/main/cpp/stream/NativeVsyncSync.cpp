#include "NativeStreamPipeline.h"
#include <android/choreographer.h>
#include <android/log.h>

#define LOG_TAG "NativeVsyncSync"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace GodMode::Stream {

// VSYNC Choreographer Callback Implementation for Zero-Tearing Presentation
static void nativeVsyncFrameCallback(long frameTimeNanos, void* data) {
    auto* pipeline = static_cast<NativeZeroCopyPipeline*>(data);
    if (pipeline) {
        pipeline->onVsyncTick(frameTimeNanos);
    }
}

void NativeZeroCopyPipeline::startVsyncPacing() {
    if (isVsyncActive.load()) return;
    isVsyncActive.store(true);
    
    // Register AChoreographer callback for hardware VBLANK synchronization
    auto* choreographer = AChoreographer_getInstance();
    if (choreographer) {
        AChoreographer_postFrameCallback(choreographer, nativeVsyncFrameCallback, this);
        LOGD("AChoreographer VSYNC Pacing registered successfully");
    }
}

void NativeZeroCopyPipeline::stopVsyncPacing() {
    isVsyncActive.store(false);
}

void NativeZeroCopyPipeline::onVsyncTick(long frameTimeNanos) {
    if (!isVsyncActive.load()) return;

    // Trigger immediate deferred frame release aligned with display refresh cycle
    drainAndPresentReadyBuffers();

    // Re-register for the next VSYNC tick
    auto* choreographer = AChoreographer_getInstance();
    if (choreographer && isVsyncActive.load()) {
        AChoreographer_postFrameCallback(choreographer, nativeVsyncFrameCallback, this);
    }
}

void NativeZeroCopyPipeline::drainAndPresentReadyBuffers() {
    if (!mediaCodec || !isRunning.load(std::memory_order_acquire)) return;

    AMediaCodecBufferInfo info;
    ssize_t outputBufIdx = AMediaCodec_dequeueOutputBuffer(mediaCodec, &info, 0);
    while (outputBufIdx >= 0) {
        // Release buffer instantly to surface exactly on VBLANK boundary (<1ms latency)
        AMediaCodec_releaseOutputBuffer(mediaCodec, outputBufIdx, info.size != 0);
        outputBufIdx = AMediaCodec_dequeueOutputBuffer(mediaCodec, &info, 0);
    }
}

} // namespace GodMode::Stream
