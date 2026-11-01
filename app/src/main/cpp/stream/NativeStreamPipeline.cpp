#include "NativeStreamPipeline.h"
#include "RtpH264Depacketizer.h"
#include "RtpH265Depacketizer.h"
#include "RtpAV1Depacketizer.h"
#include "RtpVVCDepacketizer.h"
#include <android/log.h>
#include <cstring>
#include <sstream>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#define LOG_TAG "NativeStreamPipeline"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace GodMode::Stream {

std::vector<CodecCapability> CodecCapabilityNegotiator::detectSupportedCodecs() {
    std::vector<std::pair<StreamCodecProfile, std::string>> potentialCodecs = {
        {StreamCodecProfile::H264_HIGH_SVC, "video/avc"},
        {StreamCodecProfile::HEVC_MAIN10, "video/hevc"},
        {StreamCodecProfile::AV1_MAIN, "video/av01"},
        {StreamCodecProfile::VVC_MAIN, "video/vvc"}
    };

    std::vector<CodecCapability> capabilities;
    for (const auto& codec : potentialCodecs) {
        bool supported = isCodecSupported(codec.second);
        capabilities.push_back({codec.first, supported, codec.second});
    }
    return capabilities;
}

bool CodecCapabilityNegotiator::isCodecSupported(const std::string& mime) {
    AMediaCodec* testCodec = AMediaCodec_createDecoderByType(mime.c_str());
    if (testCodec) {
        AMediaCodec_delete(testCodec);
        return true;
    }
    return false;
}

NativeZeroCopyPipeline::NativeZeroCopyPipeline() {
    jitterBuffer = std::make_unique<AdaptiveGeoJitterBuffer>();
}

NativeZeroCopyPipeline::~NativeZeroCopyPipeline() {
    shutdown();
}

std::string NativeZeroCopyPipeline::rewriteSdpForUltraQuality(const std::string& rawSdp, uint32_t maxBitrateKbps) {
    std::istringstream stream(rawSdp);
    std::ostringstream out;
    std::string line;
    bool inVideoMediaSection = false;

    while (std::getline(stream, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();

        // Descartar candidatos TURN ('typ relay') para forzar ruta directa P2P / Teredo
        if (line.find("a=candidate:") == 0 && line.find("typ relay") != std::string::npos) {
            continue;
        }

        if (line.find("m=video") == 0) {
            inVideoMediaSection = true;
            out << line << "\r\n";
            // Inyectar límite de bitrate de hasta 150 Mbps (150000 kbps) en la sección de video
            out << "b=AS:" << maxBitrateKbps << "\r\n";
            out << "b=TIAS:" << (maxBitrateKbps * 1000) << "\r\n";
            continue;
        } else if (line.find("m=audio") == 0 || line.find("m=application") == 0) {
            inVideoMediaSection = false;
        }

        // Enriquecer parámetros de formato (fmtp) en video para baja latencia y alto bitrate
        if (inVideoMediaSection && line.find("a=fmtp:") == 0) {
            out << line << ";x-google-max-bitrate=" << maxBitrateKbps
                << ";x-google-min-bitrate=" << (maxBitrateKbps / 2)
                << ";x-google-start-bitrate=" << (maxBitrateKbps * 3 / 4) << "\r\n";
            continue;
        }

        out << line << "\r\n";
    }
    return out.str();
}

bool NativeZeroCopyPipeline::initializeHardwareDecoder(ANativeWindow* window, StreamCodecProfile profile, uint32_t width, uint32_t height) {
    shutdown(); // Ensure clean state

    outputWindow = window;
    activeCodec = profile;
    currentWidth = width;
    currentHeight = height;

    const char* mimeType = "video/avc";
    if (profile == StreamCodecProfile::HEVC_MAIN10) mimeType = "video/hevc";
    else if (profile == StreamCodecProfile::AV1_MAIN) mimeType = "video/av01";
    else if (profile == StreamCodecProfile::VVC_MAIN) mimeType = "video/vvc";
    else if (profile == StreamCodecProfile::AV2_EXPERIMENTAL) mimeType = "video/av02";
    else if (profile == StreamCodecProfile::AVS3) mimeType = "video/avs3";
    else if (profile == StreamCodecProfile::MPEG5_LCEVC) mimeType = "video/lcevc";
    else if (profile == StreamCodecProfile::APPLE_PRORES_422) mimeType = "video/prores";

    mediaCodec = AMediaCodec_createDecoderByType(mimeType);
    if (!mediaCodec) {
        LOGE("Failed to create decoder for MIME: %s", mimeType);
        return false;
    }

    AMediaFormat* format = AMediaFormat_new();
    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, mimeType);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, static_cast<int32_t>(width));
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, static_cast<int32_t>(height));

    // Directivas estrictas de Ultra-Baja Latencia (Android 11+ / API 30+)
    AMediaFormat_setInt32(format, "low-latency", 1);
    AMediaFormat_setInt32(format, "vendor.qti-ext-dec-low-latency.enable", 1);   // Qualcomm Snapdragon
    AMediaFormat_setInt32(format, "vendor.rtc-ext-dec-low-latency.enable", 1);   // MediaTek / Mali / Exynos
    AMediaFormat_setInt32(format, "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", 1);
    AMediaFormat_setInt32(format, "priority", 0); // Prioridad Tiempo Real (0)

    // Rango de color completo para el pipeline OLED Rec.709 -> DCI-P3
    AMediaFormat_setInt32(format, "color-range", 1); // COLOR_RANGE_FULL

    // Vinculación directa del decodificador HW a la superficie nativa (Zero-Copy)
    media_status_t status = AMediaCodec_configure(mediaCodec, format, outputWindow, nullptr, 0);
    AMediaFormat_delete(format);

    if (status != AMEDIA_OK) {
        LOGE("AMediaCodec_configure failed: %d", status);
        AMediaCodec_delete(mediaCodec);
        mediaCodec = nullptr;
        return false;
    }

    if (AMediaCodec_start(mediaCodec) != AMEDIA_OK) {
        LOGE("AMediaCodec_start failed");
        AMediaCodec_delete(mediaCodec);
        mediaCodec = nullptr;
        return false;
    }

    // Seleccionar el despaquetizador RTP adecuado según el codec
    if (profile == StreamCodecProfile::HEVC_MAIN10) {
        depacketizer = std::make_unique<RtpH265Depacketizer>();
        LOGD("Selected HEVC Depacketizer");
    } else if (profile == StreamCodecProfile::AV1_MAIN) {
        depacketizer = std::make_unique<RtpAV1Depacketizer>();
        LOGD("Selected AV1 Depacketizer");
    } else if (profile == StreamCodecProfile::VVC_MAIN) {
        depacketizer = std::make_unique<RtpVVCDepacketizer>();
        LOGD("Selected VVC Depacketizer");
    } else if (profile == StreamCodecProfile::AV2_EXPERIMENTAL) {
        depacketizer = std::make_unique<RtpAV1Depacketizer>(); // Use AV1 logic for now as AV2 baseline
        LOGD("Selected AV2 Experimental Depacketizer");
    } else {
        depacketizer = std::make_unique<RtpH264Depacketizer>();
        LOGD("Selected H.264 Depacketizer");
    }

    isRunning.store(true, std::memory_order_release);
    lastPliSentTime = std::chrono::steady_clock::now() - std::chrono::seconds(2);
    LOGD("NativeStreamPipeline initialized successfully");
    return true;
}

bool NativeZeroCopyPipeline::shouldDropScalableEnhancementLayer(const uint8_t* nalData, size_t nalSize, float currentPacketLossPct) {
    if (nalSize < 4 || currentPacketLossPct < 2.5f) return false;

    uint8_t nalType = nalData[0] & 0x1F;
    // NAL Type 14 (Prefix NAL) o Type 20 (Coded slice extension para H.264 SVC)
    if (nalType == 14 || nalType == 20) {
        uint8_t temporalId = (nalData[3] >> 5) & 0x07;
        uint8_t qualityId  = (nalData[2] >> 4) & 0x0F;
        // Si hay fluctuación de red, descartar capas de mejora (temporalId > 0 o qualityId > 0)
        // manteniendo intacta la capa base (0) para evitar cualquier congelamiento (freeze)
        if (temporalId > 0 || qualityId > 0) {
            return true;
        }
    }
    return false;
}

bool NativeZeroCopyPipeline::injectAccessUnitZeroCopy(const uint8_t* auBuffer, size_t auSize, uint32_t serverDataKey,
                              float packetLossPct, FrameTelemetry& outTelemetry) {
    if (!isRunning.load(std::memory_order_acquire) || !mediaCodec) return false;

    uint64_t now = nowMicros();
    outTelemetry.serverDataKey = serverDataKey;
    outTelemetry.firstPacketArrivalUs = now;

    // Actualizar estimación de jitter para telemetría adaptable
    jitterBuffer->updateJitterEstimate(0, now); // 0 como placeholder de RTP timestamp

    // Filtro SVC para evitar congelamiento en fluctuaciones de ancho de banda
    if (shouldDropScalableEnhancementLayer(auBuffer, auSize, packetLossPct)) {
        return true;
    }

    // 4.1. Encolar Access Unit en el buffer de hardware (timeout 2000 us)
    ssize_t inputBufIdx = AMediaCodec_dequeueInputBuffer(mediaCodec, 2000);
    if (inputBufIdx >= 0) {
        size_t outBufSize = 0;
        uint8_t* dstBuffer = AMediaCodec_getInputBuffer(mediaCodec, inputBufIdx, &outBufSize);
        if (dstBuffer && auSize <= outBufSize) {
            std::memcpy(dstBuffer, auBuffer, auSize);
            outTelemetry.frameSubmittedUs = nowMicros();
            
            // Si el buffer no tiene start codes de Annex-B, podríamos inyectarlos aquí si fuera necesario.
            // Pero confiamos en que el depacketizer de WebRTC o el emisor ya los envió o el decoder los tolera.
            
            // Aplicar profundidad del jitter buffer para suavizar el timing de presentación
            uint64_t presentationTimeUs = outTelemetry.frameSubmittedUs + (jitterBuffer->getActiveBufferDepthMs() * 1000);
            AMediaCodec_queueInputBuffer(mediaCodec, inputBufIdx, 0, auSize, presentationTimeUs, 0);
        }
    } else {
        // Si el decoder está saturado, podemos pedir un I-Frame de emergencia
        if (canRequestEmergencyKeyframe()) {
             LOGD("Decoder saturated, might need PLI/Keyframe request");
        }
        return false;
    }

    // 4.2. Drenar y presentar inmediatamente en ANativeWindow (Modo VSync-Off / Timestamp 0)
    AMediaCodecBufferInfo info;
    ssize_t outputBufIdx = AMediaCodec_dequeueOutputBuffer(mediaCodec, &info, 0);
    while (outputBufIdx >= 0) {
        outTelemetry.frameDecodedUs = nowMicros();

        // AMediaCodec_releaseOutputBuffer(codec, idx, true) con timestamp 0 para renderizado inmediato
        AMediaCodec_releaseOutputBuffer(mediaCodec, outputBufIdx, info.size != 0);
        outTelemetry.frameRenderedUs = nowMicros();

        outputBufIdx = AMediaCodec_dequeueOutputBuffer(mediaCodec, &info, 0);
    }

    if (outputBufIdx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
        AMediaFormat* newFormat = AMediaCodec_getOutputFormat(mediaCodec);
        if (newFormat) {
            int32_t newW = 0, newH = 0;
            AMediaFormat_getInt32(newFormat, AMEDIAFORMAT_KEY_WIDTH, &newW);
            AMediaFormat_getInt32(newFormat, AMEDIAFORMAT_KEY_HEIGHT, &newH);
            currentWidth = static_cast<uint32_t>(newW);
            currentHeight = static_cast<uint32_t>(newH);
            AMediaFormat_delete(newFormat);
            LOGD("Output format changed: %dx%d", currentWidth, currentHeight);
        }
    }

    return true;
}

bool NativeZeroCopyPipeline::canRequestEmergencyKeyframe() {
    auto now = std::chrono::steady_clock::now();
    if (std::chrono::duration_cast<std::chrono::milliseconds>(now - lastPliSentTime).count() >= 1200) {
        lastPliSentTime = now;
        return true;
    }
    return false;
}

void NativeZeroCopyPipeline::shutdown() {
    isRunning.store(false, std::memory_order_release);
    if (mediaCodec) {
        AMediaCodec_stop(mediaCodec);
        AMediaCodec_delete(mediaCodec);
        mediaCodec = nullptr;
    }
    if (outputWindow) {
        ANativeWindow_release(outputWindow);
        outputWindow = nullptr;
    }
}

uint64_t NativeZeroCopyPipeline::nowMicros() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()
    ).count();
}

} // namespace GodMode::Stream
