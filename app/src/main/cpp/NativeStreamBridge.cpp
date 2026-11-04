#include <jni.h>
#include <android/native_window_jni.h>
#include "stream/NativeStreamPipeline.h"
#include "audio/GodModeAudioEngine.h"
#include "SecureRamVault.h"
#include <android/log.h>
#include <memory>
#include <mutex>

#include "stream/IStreamEngine.h"
#include "stream/XcloudPtpClockSynchronizer.h"
#include "stream/AdaptiveJitterDropper.h"
#include "stream/MasterAnnexBDepacketizer.h"
#include "stream/ZeroTrustNetworkSecurityGatekeeper.h"
#include "stream/UnifiedZeroCopyStreamReactor.h"
#include "stream/NativeIceDtlsSrtpBridge.h"



#define LOG_TAG "NativeStreamBridge"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#include <android/thermal.h>
#include <android/looper.h>

using namespace GodMode::Stream;
using namespace GodMode::Security;
using namespace GodMode::Audio;
using namespace godmode;

static std::unique_ptr<NativeZeroCopyPipeline> gPipeline;
static std::unique_ptr<GodModeAudioEngine> gAudioEngine;
static std::mutex gPipelineMutex;
static SecureRamVault gVault;

static std::unique_ptr<IStreamEngine> gActiveEngine;
static ANativeWindow* gActiveNativeWindow = nullptr;
static std::mutex gEngineMutex;
static CompleteAccessUnitAssembler gAuAssembler;
static XcloudClockDriftCompensator gClockCompensator;
static BbrNanoCongestionController gNetworkGovernor;
static std::atomic<int32_t> gGlobalThermalStatus{0};
static AThermalManager* gThermalManager = nullptr;
static XcloudPtpClockSynchronizer gPtpSynchronizer;
static godmode::AdaptiveJitterDropper gJitterDropper(90000); // 90ms threshold
static ZeroTrustNetworkSecurityGatekeeper gSecurityGatekeeper;



static void onThermalStatusChanged(void* data, AThermalStatus status) {
    gGlobalThermalStatus.store(static_cast<int32_t>(status), std::memory_order_relaxed);
}

#include "audio/AAudioEngine.h"

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_initPipeline(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    
    // Disabled to prevent SIGTRAP and instability in the AI Studio environment
    // installGlobalTerminationGuards();
    // elevateCurrentThreadToRealtimePriority();

    if (!gPipeline) {
        gPipeline = std::make_unique<NativeZeroCopyPipeline>();
    }
    initLowLatencyAudio();

    if (!gThermalManager) {
        typedef AThermalManager* (*PFN_AThermal_acquireManager)();
        typedef int (*PFN_AThermal_registerThermalStatusListener)(AThermalManager*, void (*)(void*, AThermalStatus), void*);
        
        static auto fpAcquire = reinterpret_cast<PFN_AThermal_acquireManager>(dlsym(RTLD_DEFAULT, "AThermal_acquireManager"));
        static auto fpRegister = reinterpret_cast<PFN_AThermal_registerThermalStatusListener>(dlsym(RTLD_DEFAULT, "AThermal_registerThermalStatusListener"));

        if (fpAcquire && fpRegister) {
            gThermalManager = fpAcquire();
            if (gThermalManager) {
                fpRegister(gThermalManager, onThermalStatusChanged, nullptr);
            }
        }
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
    stopAudioEngine();
}

JNIEXPORT void JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_injectAudio(JNIEnv *env, jobject thiz, jshortArray data, jint num_frames) {
    if (!data) return;
    jshort* frames = env->GetShortArrayElements(data, nullptr);
    if (frames) {
        // Convert shorts to floats for the new AAudio engine
        std::vector<float> floatFrames(num_frames * 2);
        for (int i = 0; i < num_frames * 2; ++i) {
            floatFrames[i] = static_cast<float>(frames[i]) / 32768.0f;
        }
        injectAudioFrames(floatFrames.data(), num_frames);
        env->ReleaseShortArrayElements(data, frames, JNI_ABORT);
    }
}

static uint8_t* gTelemetryBufferPtr = nullptr;
static jsize gTelemetryBufferSize = 0;
static int gCurrentActiveEngineId = 3;

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

// -----------------------------------------------------------------------------
// JNI: Orquestación de los 6 Motores Nativos con Cascada (3 -> 4 -> 5 -> 6 -> 1 -> 2)
// -----------------------------------------------------------------------------
JNIEXPORT jboolean JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_initStreamPipeline(
        JNIEnv* env, jobject thiz, jobject surface, jint engineType,
        jint width, jint height, jboolean enableAiUpscale) {
    try {
        std::lock_guard<std::mutex> lock(gEngineMutex);
        if (gActiveEngine) {
            gActiveEngine->shutdown();
            gActiveEngine.reset();
        }
        if (gActiveNativeWindow) {
            ANativeWindow_release(gActiveNativeWindow);
            gActiveNativeWindow = nullptr;
        }

        if (!surface) return JNI_FALSE;
        gActiveNativeWindow = ANativeWindow_fromSurface(env, surface);
        if (!gActiveNativeWindow) return JNI_FALSE;

        gAuAssembler.reset();

        int resolvedEngineId = engineType;
        gActiveEngine = MasterSixEngineFailSafeController::initializeWithCascade(
            gActiveNativeWindow,
            engineType,
            width,
            height,
            static_cast<bool>(enableAiUpscale),
            resolvedEngineId
        );

        gCurrentActiveEngineId = resolvedEngineId;

        if (gTelemetryBufferPtr && gTelemetryBufferSize >= 48) {
            *reinterpret_cast<int32_t*>(gTelemetryBufferPtr + 16) = gCurrentActiveEngineId;
            *reinterpret_cast<uint32_t*>(gTelemetryBufferPtr + 20) = gGlobalStunKeepAlivesCount.load(std::memory_order_relaxed);
            *reinterpret_cast<uint32_t*>(gTelemetryBufferPtr + 24) = gGlobalHwcFencesClosedCount.load(std::memory_order_relaxed);
            *reinterpret_cast<int32_t*>(gTelemetryBufferPtr + 28) = enableAiUpscale ? 1 : 0;
            *reinterpret_cast<int32_t*>(gTelemetryBufferPtr + 32) = gGlobalThermalStatus.load(std::memory_order_relaxed);
            *reinterpret_cast<int64_t*>(gTelemetryBufferPtr + 36) = 0; // Drift initialized to 0
        }

        LOGD("initStreamPipeline resultado: activo=%d (solicitado=%d)", gCurrentActiveEngineId, engineType);
        return (gActiveEngine != nullptr) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        LOGE("Excepción crítica en initStreamPipeline: %s", e.what());
        return JNI_FALSE;
    } catch (...) {
        LOGE("Excepción nativa desconocida en initStreamPipeline.");
        return JNI_FALSE;
    }
}

JNIEXPORT jint JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_getActiveEngineId(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    return static_cast<jint>(gCurrentActiveEngineId);
}

JNIEXPORT void JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_onSurfaceResized(
        JNIEnv* env, jobject thiz, jint width, jint height) {
    try {
        std::lock_guard<std::mutex> lock(gEngineMutex);
        if (gActiveEngine) {
            gActiveEngine->onResize(width, height);
        }
    } catch (...) {}
}

JNIEXPORT void JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_shutdownStreamPipeline(
        JNIEnv* env, jobject thiz) {
    try {
        std::lock_guard<std::mutex> lock(gEngineMutex);
        if (gActiveEngine) {
            gActiveEngine->shutdown();
            gActiveEngine.reset();
        }
        if (gActiveNativeWindow) {
            ANativeWindow_release(gActiveNativeWindow);
            gActiveNativeWindow = nullptr;
        }
        gAuAssembler.reset();
    } catch (...) {}
}

JNIEXPORT jboolean JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_dispatchRtpPacket(
        JNIEnv* env, jobject thiz, jbyteArray data, jint len, jlong timestamp, jboolean marker) {
    if (!data || len <= 0) return JNI_FALSE;
    try {
        std::lock_guard<std::mutex> lock(gEngineMutex);
        if (!gActiveEngine) return JNI_FALSE;

        jbyte* bytes = env->GetByteArrayElements(data, nullptr);
        if (!bytes) return JNI_FALSE;

        const uint8_t* ubytes = reinterpret_cast<const uint8_t*>(bytes);
        size_t totalLen = static_cast<size_t>(len);

        // Actualizar estadísticas de telemetría hacia el buffer mapeado
        if (gTelemetryBufferPtr && gTelemetryBufferSize >= 48) {
            auto stunReport = NativeIceDtlsSrtpBridge::getInstance().getLatencyReport();
            if (stunReport.currentRttMs > 0.0f) {
                *reinterpret_cast<int32_t*>(gTelemetryBufferPtr) = static_cast<int32_t>(stunReport.currentRttMs);
                *reinterpret_cast<int32_t*>(gTelemetryBufferPtr + 4) = static_cast<int32_t>(stunReport.jitterMs);
            }
            *reinterpret_cast<int32_t*>(gTelemetryBufferPtr + 16) = gCurrentActiveEngineId;
            *reinterpret_cast<uint32_t*>(gTelemetryBufferPtr + 20) = static_cast<uint32_t>(stunReport.totalSuccessReceived);
            *reinterpret_cast<uint32_t*>(gTelemetryBufferPtr + 24) = gGlobalHwcFencesClosedCount.load(std::memory_order_relaxed);
            *reinterpret_cast<int32_t*>(gTelemetryBufferPtr + 32) = gGlobalThermalStatus.load(std::memory_order_relaxed);
            // Clock drift populated later in this function
        }

        // Si el paquete contiene cabecera RTP (versión 2), omitir extensiones RFC 8285 (0xBEDE / 0x1000)
        ParsedRtpHeader parsed = stripRtpHeadersAndExtensions(ubytes, totalLen);
        
        // 1. Zero-Trust Security Inspection
        auto securityVerdict = gSecurityGatekeeper.inspectAndSanitizeIncomingFrame(
            ubytes, totalLen, parsed.sequenceNumber
        );
        
        if (securityVerdict != ZeroTrustNetworkSecurityGatekeeper::SecurityVerdict::ALLOW_PACKET) {
            env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
            return JNI_TRUE; // Packet dropped due to security violation or replay
        }

        uint32_t rtpTs = (timestamp > 0) ? static_cast<uint32_t>(timestamp) : parsed.timestamp;
        uint64_t nowUs = getMonotonicMicros();
        gClockCompensator.updateSync(rtpTs, nowUs);
        uint64_t adjustedPts = gClockCompensator.getAdjustedPresentationTimeUs(rtpTs);

        if (gJitterDropper.shouldDropFrame(adjustedPts, nowUs)) {
            env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
            return JNI_TRUE; // Frame dropped but handled
        }

        if (gTelemetryBufferPtr && gTelemetryBufferSize >= 48) {

             *reinterpret_cast<int64_t*>(gTelemetryBufferPtr + 36) = gClockCompensator.getEstimatedOffsetUs();
        }

        // Track the packet in the BBR governor
        gNetworkGovernor.process_packet_sent(totalLen);

        if (parsed.valid && parsed.payloadPtr && parsed.payloadSize > 0) {
            gAuAssembler.feedRtpPayload(
                parsed.payloadPtr,
                parsed.payloadSize,
                static_cast<uint32_t>((adjustedPts * 90000ULL) / 1000000ULL),
                marker ? true : parsed.marker,
                gActiveEngine.get()
            );
        } else {
            // NAL Annex B directo o Payload RTP simple
            gAuAssembler.feedRtpPayload(
                ubytes,
                totalLen,
                static_cast<uint32_t>((adjustedPts * 90000ULL) / 1000000ULL),
                marker,
                gActiveEngine.get()
            );
        }

        // Close Audio/Video Sync feedback loop
        syncAudioClock(getInternalAudioClockUs(), adjustedPts);

        env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
        return JNI_TRUE;
    } catch (...) {
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_reportPacketAck(JNIEnv* env, jobject thiz, jint bytes, jdouble rtt_us) {
    gNetworkGovernor.process_ack(static_cast<size_t>(bytes), static_cast<uint64_t>(rtt_us));
    
    // Actualizar el buffer de telemetría con el RTT filtrado por Kalman para suavidad en el sparkline
    if (gTelemetryBufferPtr && gTelemetryBufferSize >= 48) {
        *reinterpret_cast<int32_t*>(gTelemetryBufferPtr) = static_cast<int32_t>(gNetworkGovernor.getFilteredRttMs());
    }
}

JNIEXPORT jdouble JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_getTargetPacingRateBps(JNIEnv* env, jobject thiz, jboolean ultra) {
    return static_cast<jdouble>(gNetworkGovernor.getOptimalPacingRateBps(static_cast<bool>(ultra)));
}

JNIEXPORT void JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_simulateDeviceLost(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    // Simular error de Vulkan si el motor activo lo usa o si queremos forzar reconstrucción
    DynamicNdkSymbols::get().simulateDeviceLost(reinterpret_cast<VkDevice&>(gActiveNativeWindow)); // Cast ficticio para trigger
}

JNIEXPORT void JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_forceThermalCritical(JNIEnv *env, jobject thiz) {
    onThermalStatusChanged(nullptr, ATHERMAL_STATUS_CRITICAL);
}

JNIEXPORT void JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_dispatchPtpHeartbeat(
        JNIEnv* env, jobject thiz, jlong t1, jlong t2, jlong t3, jlong t4) {
    gPtpSynchronizer.processHeartbeat(t1, t2, t3, t4);
}

static std::unique_ptr<UnifiedZeroCopyStreamReactor> gReactor;

JNIEXPORT jboolean JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_startNativeReactor(
        JNIEnv *env, jobject thiz, jstring remote_ip, jint remote_port,
        jstring remote_ufrag, jstring remote_pwd, jstring local_ufrag, jboolean enable_ipv6) {
    
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (!gActiveEngine) return JNI_FALSE;

    if (!gReactor) {
        gReactor = std::make_unique<UnifiedZeroCopyStreamReactor>(gActiveEngine.get());
    } else {
        gReactor->setStreamEngine(gActiveEngine.get());
    }

    const char* ip = env->GetStringUTFChars(remote_ip, nullptr);
    const char* r_ufrag = env->GetStringUTFChars(remote_ufrag, nullptr);
    const char* r_pwd = env->GetStringUTFChars(remote_pwd, nullptr);
    const char* l_ufrag = env->GetStringUTFChars(local_ufrag, nullptr);

    UnifiedZeroCopyStreamReactor::Configuration config;
    config.remoteIp = ip;
    config.remotePort = static_cast<uint16_t>(remote_port);
    config.remoteUfrag = r_ufrag;
    config.remotePwd = r_pwd;
    config.localUfrag = l_ufrag;
    config.enableIPv6 = static_cast<bool>(enable_ipv6);

    bool started = gReactor->start(config);

    env->ReleaseStringUTFChars(remote_ip, ip);
    env->ReleaseStringUTFChars(remote_ufrag, r_ufrag);
    env->ReleaseStringUTFChars(remote_pwd, r_pwd);
    env->ReleaseStringUTFChars(local_ufrag, l_ufrag);

    return started ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_stopNativeReactor(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gReactor) {
        gReactor->stop();
        gReactor.reset();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_injectDirectSrtpKeys(
        JNIEnv *env, jobject thiz, jstring key_str, jstring salt_str) {
    if (!key_str || !salt_str) return JNI_FALSE;

    const char* keyCStr = env->GetStringUTFChars(key_str, nullptr);
    const char* saltCStr = env->GetStringUTFChars(salt_str, nullptr);
    if (!keyCStr || !saltCStr) {
        if (keyCStr) env->ReleaseStringUTFChars(key_str, keyCStr);
        if (saltCStr) env->ReleaseStringUTFChars(salt_str, saltCStr);
        return JNI_FALSE;
    }

    uint8_t keyBytes[16]{};
    uint8_t saltBytes[14]{};

    auto decodeKey = [](const char* src, uint8_t* dst, size_t expectedLen) {
        size_t srcLen = std::strlen(src);
        static const char b64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        bool isHex = (srcLen == expectedLen * 2);
        for (size_t i = 0; i < srcLen && isHex; ++i) {
            char c = src[i];
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                isHex = false;
            }
        }
        if (isHex) {
            for (size_t i = 0; i < expectedLen; ++i) {
                unsigned int b = 0;
                sscanf(src + i * 2, "%2x", &b);
                dst[i] = static_cast<uint8_t>(b);
            }
            return true;
        }
        // Base64 decoding
        int val = 0, valb = -8;
        size_t outIdx = 0;
        for (size_t i = 0; i < srcLen; ++i) {
            char c = src[i];
            if (c == '=') break;
            const char* p = std::strchr(b64, c);
            if (p) {
                val = (val << 6) + static_cast<int>(p - b64);
                valb += 6;
                if (valb >= 0) {
                    if (outIdx < expectedLen) {
                        dst[outIdx++] = static_cast<uint8_t>((val >> valb) & 0xFF);
                    }
                    valb -= 8;
                }
            }
        }
        if (outIdx == expectedLen) return true;
        std::memcpy(dst, src, std::min(srcLen, expectedLen));
        return true;
    };

    decodeKey(keyCStr, keyBytes, 16);
    decodeKey(saltCStr, saltBytes, 14);

    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (!gReactor) {
        gReactor = std::make_unique<UnifiedZeroCopyStreamReactor>(gActiveEngine ? gActiveEngine.get() : nullptr);
    }
    gReactor->injectDirectSrtpKeys(keyBytes, 16, saltBytes, 14);

    env->ReleaseStringUTFChars(key_str, keyCStr);
    env->ReleaseStringUTFChars(salt_str, saltCStr);
    return JNI_TRUE;
}

JNIEXPORT jfloatArray JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_getStunLatencyMetrics(JNIEnv *env, jobject thiz) {
    auto report = NativeIceDtlsSrtpBridge::getInstance().getLatencyReport();
    jfloatArray result = env->NewFloatArray(6);
    if (!result) return nullptr;

    jfloat metrics[6] = {
        report.currentRttMs,
        report.minRttMs,
        report.maxRttMs,
        report.smoothedRttMs,
        report.jitterMs,
        static_cast<jfloat>(report.totalSuccessReceived)
    };
    env->SetFloatArrayRegion(result, 0, 6, metrics);
    return result;
}

}


