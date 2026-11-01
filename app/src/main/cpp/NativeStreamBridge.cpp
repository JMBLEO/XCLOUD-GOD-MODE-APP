#include <jni.h>
#include <android/native_window_jni.h>
#include "stream/NativeStreamPipeline.h"
#include <android/log.h>
#include <memory>
#include <mutex>

#define LOG_TAG "NativeStreamBridge"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

using namespace GodMode::Stream;

static std::unique_ptr<NativeZeroCopyPipeline> gPipeline;
static std::mutex gPipelineMutex;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_initPipeline(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (!gPipeline) {
        gPipeline = std::make_unique<NativeZeroCopyPipeline>();
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_attachSurface(JNIEnv *env, jobject thiz, jobject surface, jint profile, jint width, jint height) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (!gPipeline) return JNI_FALSE;

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) return JNI_FALSE;

    StreamCodecProfile codecProfile = static_cast<StreamCodecProfile>(profile);
    bool success = gPipeline->initializeHardwareDecoder(window, codecProfile, width, height);
    
    // ANativeWindow_fromSurface acquires a reference, initializeHardwareDecoder should handle release if it fails or when shutting down
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_detachSurface(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (gPipeline) {
        gPipeline->shutdown();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_injectAccessUnit(JNIEnv *env, jobject thiz, jbyteArray data, jint size, jint server_data_key, jfloat packet_loss) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (!gPipeline) return JNI_FALSE;

    jbyte* buffer = env->GetByteArrayElements(data, nullptr);
    if (!buffer) return JNI_FALSE;

    FrameTelemetry telemetry{};
    bool result = gPipeline->injectAccessUnitZeroCopy(reinterpret_cast<const uint8_t*>(buffer), size, server_data_key, packet_loss, telemetry);

    env->ReleaseByteArrayElements(data, buffer, JNI_ABORT);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_rewriteSdp(JNIEnv *env, jobject thiz, jstring raw_sdp, jint max_bitrate_kbps) {
    const char* sdp_cstr = env->GetStringUTFChars(raw_sdp, nullptr);
    if (!sdp_cstr) return raw_sdp;

    std::string rewritten = NativeZeroCopyPipeline::rewriteSdpForUltraQuality(sdp_cstr, max_bitrate_kbps);
    
    env->ReleaseStringUTFChars(raw_sdp, sdp_cstr);
    return env->NewStringUTF(rewritten.c_str());
}

}
