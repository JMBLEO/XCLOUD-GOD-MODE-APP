#include <jni.h>
#include <android/native_window_jni.h>
#include "stream/NativeStreamPipeline.h"
#include "audio/GodModeAudioEngine.h"
#include "SecureRamVault.h"
#include <android/log.h>
#include <memory>
#include <mutex>

#define LOG_TAG "NativeStreamBridge"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

using namespace GodMode::Stream;
using namespace GodMode::Security;
using namespace GodMode::Audio;

static std::unique_ptr<NativeZeroCopyPipeline> gPipeline;
static std::unique_ptr<GodModeAudioEngine> gAudioEngine;
static std::mutex gPipelineMutex;
static SecureRamVault gVault;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_initPipeline(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (!gPipeline) {
        gPipeline = std::make_unique<NativeZeroCopyPipeline>();
    }
    if (!gAudioEngine) {
        gAudioEngine = std::make_unique<GodModeAudioEngine>();
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

JNIEXPORT void JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_cleanupEgl(JNIEnv *env, jobject thiz) {
    NativeZeroCopyPipeline::cleanupEglSurfaceBinding();
}

JNIEXPORT jfloatArray JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_smoothMouseDelta(JNIEnv *env, jobject thiz, jfloat delta_x, jfloat delta_y) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    jfloatArray result = env->NewFloatArray(2);
    if (!result) return nullptr;

    jfloat smoothed[2] = {delta_x, delta_y};
    if (gPipeline) {
        auto pair = gPipeline->applyKalmanSmoothing(delta_x, delta_y);
        smoothed[0] = pair.first;
        smoothed[1] = pair.second;
    }
    env->SetFloatArrayRegion(result, 0, 2, smoothed);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_pushRawInput(JNIEnv *env, jobject thiz, jbyteArray data, jint size) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (!gPipeline || size <= 0) return JNI_FALSE;

    jbyte* buffer = env->GetByteArrayElements(data, nullptr);
    if (!buffer) return JNI_FALSE;

    bool pushed = gPipeline->pushRawInputPacket(reinterpret_cast<const uint8_t*>(buffer), static_cast<size_t>(size));
    env->ReleaseByteArrayElements(data, buffer, JNI_ABORT);
    return pushed ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_popRawInput(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (!gPipeline) return nullptr;

    uint8_t outBuffer[64];
    size_t outSize = 0;
    if (gPipeline->popRawInputPacket(outBuffer, outSize) && outSize > 0) {
        jbyteArray result = env->NewByteArray(static_cast<jsize>(outSize));
        if (result) {
            env->SetByteArrayRegion(result, 0, static_cast<jsize>(outSize), reinterpret_cast<const jbyte*>(outBuffer));
            return result;
        }
    }
    return nullptr;
}

JNIEXPORT void JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_zeroTraceShutdown(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (gPipeline) {
        gPipeline->zeroTraceShutdown();
    }
    gVault.purge();
}

JNIEXPORT void JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_setPtsOffsetUs(JNIEnv *env, jobject thiz, jlong offset_us) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (gPipeline) {
        gPipeline->setPtsOffsetUs(offset_us);
    }
}

JNIEXPORT void JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_storeSecureToken(JNIEnv *env, jobject thiz, jbyteArray data) {
    if (!data) return;
    jsize len = env->GetArrayLength(data);
    if (len <= 0) return;

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes) {
        gVault.storeToken(reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(len));
        env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_purgeSecureVault(JNIEnv *env, jobject thiz) {
    gVault.purge();
}

JNIEXPORT jboolean JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_startAudio(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    return gAudioEngine && gAudioEngine->start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_stopAudio(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (gAudioEngine) gAudioEngine->stop();
}

JNIEXPORT void JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_injectAudio(JNIEnv *env, jobject thiz, jshortArray data, jint num_frames) {
    if (!gAudioEngine || !data) return;
    jshort* frames = env->GetShortArrayElements(data, nullptr);
    if (frames) {
        gAudioEngine->injectAudio(reinterpret_cast<const int16_t*>(frames), num_frames);
        env->ReleaseShortArrayElements(data, frames, JNI_ABORT);
    }
}

static uint8_t* gTelemetryBufferPtr = nullptr;
static jsize gTelemetryBufferSize = 0;

JNIEXPORT void JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_bindTelemetryBuffer(JNIEnv *env, jobject thiz, jobject buffer) {
    if (!buffer) {
        gTelemetryBufferPtr = nullptr;
        gTelemetryBufferSize = 0;
        return;
    }
    gTelemetryBufferPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    gTelemetryBufferSize = env->GetDirectBufferCapacity(buffer);
}

}
