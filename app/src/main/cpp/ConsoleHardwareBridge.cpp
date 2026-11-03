#include <jni.h>
#include <android/surface_control.h>
#include <aaudio/AAudio.h>
#include <atomic>
#include <android/log.h>
#include <dlfcn.h>

#define LOG_TAG "ConsoleHardware"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {
    std::atomic<int64_t> g_nextVsyncTimestampNs{0};
    std::atomic<float> g_roiCenterX{0.5f};
    std::atomic<float> g_roiCenterY{0.5f};
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_xcloud_godmode_consoletech_ConsoleGradeHardwareEnhancer_nativeNotifyNextVsyncQft(
        JNIEnv* env, jobject thiz, jlong nextVsyncTimeNs) {
    g_nextVsyncTimestampNs.store(static_cast<int64_t>(nextVsyncTimeNs), std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_xcloud_godmode_consoletech_ConsoleGradeHardwareEnhancer_nativeUpdateFoveatedRoiCenter(
        JNIEnv* env, jobject thiz, jfloat normX, jfloat normY) {
    g_roiCenterX.store(normX, std::memory_order_relaxed);
    g_roiCenterY.store(normY, std::memory_order_relaxed);
}

// Global C symbol to avoid mangling and match the caller
void applyQftDesiredPresentTime(void* tx_void) {
    ASurfaceTransaction* tx = static_cast<ASurfaceTransaction*>(tx_void);
    int64_t targetVsyncNs = g_nextVsyncTimestampNs.load(std::memory_order_relaxed);
    if (targetVsyncNs > 0 && tx != nullptr) {
        static auto fpSetDesiredPresentTime = (void (*)(ASurfaceTransaction*, int64_t))
            dlsym(RTLD_DEFAULT, "ASurfaceTransaction_setDesiredPresentTime");
            
        if (fpSetDesiredPresentTime) {
            fpSetDesiredPresentTime(tx, targetVsyncNs - 1500000LL); // 1.5ms before vsync latch
        }
    }
}

} // extern "C"
