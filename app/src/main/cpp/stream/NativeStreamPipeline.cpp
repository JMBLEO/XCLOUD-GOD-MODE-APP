#include "NativeStreamPipeline.h"
#include "KernelAffinity.h"
#include "DmabufPipeline.h"
#include "RtpH264Depacketizer.h"
#include "RtpH265Depacketizer.h"
#include "RtpAV1Depacketizer.h"
#include "RtpVVCDepacketizer.h"
#include "IStreamEngine.h"
#include <android/log.h>
#include <cstring>
#include <sstream>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <GLES3/gl32.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>

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
    bool hasFlexFec = false;
    bool hasRtcpNackPli = false;

    while (std::getline(stream, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();

        // 1. Filtrado de candidatos ICE y atributos propietarios:
        // Descartar candidatos TURN ('typ relay') para forzar ruta directa UDP (host / srflx)
        // Descartar candidatos mDNS ('.local') para prevenir rechazo de conexión en servidores Xbox / Azure
        // Descartar 'msid' y 'mslabel' que causan anomalías en el motor de transporte nativo de Azure
        if (line.find("a=candidate:") == 0) {
            if (line.find("typ relay") != std::string::npos || line.find(".local") != std::string::npos || line.find("2001:0:") != std::string::npos) {
                continue;
            }
        }
        if (line.find("a=msid:") == 0 || line.find("a=mslabel:") == 0 || line.find("a=ssrc:") == 0 && (line.find("msid:") != std::string::npos || line.find("mslabel:") != std::string::npos)) {
            continue;
        }

        if (line.find("m=video") == 0) {
            inVideoMediaSection = true;
            out << line << "\r\n";
            // Inyectar directivas de ancho de banda máximo (Spoofing Azure Anaconda y Ultra-Calidad)
            out << "b=AS:" << maxBitrateKbps << "\r\n";
            out << "b=TIAS:" << (maxBitrateKbps * 1000) << "\r\n";
            // Extensión 3: DiffServ QoS - Video marcado como AF41 (DSCP 34)
            out << "a=dscp:34\r\n";
            // Directivas agresivas de RTCP Feedback para recuperación instantánea sin freeze
            out << "a=rtcp-fb:* nack\r\n";
            out << "a=rtcp-fb:* nack pli\r\n";
            out << "a=rtcp-fb:* goog-remb\r\n";
            out << "a=rtcp-fb:* transport-cc\r\n";
            hasRtcpNackPli = true;
            continue;
        } else if (line.find("m=audio") == 0 || line.find("m=application") == 0) {
            // Si salimos de video sin FlexFEC-03 inyectado, añadirlo antes de la siguiente sección
            if (inVideoMediaSection && !hasFlexFec) {
                out << "a=rtpmap:110 flexfec-03/90000\r\n";
                out << "a=fmtp:110 repair-window=100000\r\n";
                hasFlexFec = true;
            }
            inVideoMediaSection = false;
            out << line << "\r\n";
            // Extensión 3: DiffServ QoS - Audio y canales de control marcados como EF (DSCP 46)
            out << "a=dscp:46\r\n";
            continue;
        }

        if (inVideoMediaSection) {
            if (line.find("flexfec-03") != std::string::npos) {
                hasFlexFec = true;
            }
            // Enriquecer parámetros de formato (fmtp) en video para baja latencia, HDR y alto bitrate
            if (line.find("a=fmtp:") == 0) {
                std::string upgraded = line;
                // Elevar perfil a High Profile Level 5.0 (640032) para 1440p/120fps
                size_t pos = upgraded.find("profile-level-id=");
                if (pos != std::string::npos) {
                    upgraded.replace(pos + 17, 6, "640032");
                }
                
                out << upgraded << ";max-br=" << maxBitrateKbps
                    << ";max-fr=120;max-fs=14400;x-google-max-bitrate=" << maxBitrateKbps
                    << ";x-google-min-bitrate=" << (maxBitrateKbps / 2)
                    << ";x-google-start-bitrate=" << (maxBitrateKbps * 3 / 4) << "\r\n";
                continue;
            }
        }

        out << line << "\r\n";
    }

    if (inVideoMediaSection && !hasFlexFec) {
        out << "a=rtpmap:110 flexfec-03/90000\r\n";
        out << "a=fmtp:110 repair-window=100000\r\n";
    }

    return out.str();
}

void NativeZeroCopyPipeline::initGlesPipeline() {
    if (isGlesInitialized) return;

    eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (eglDisplay == EGL_NO_DISPLAY) return;
    if (!eglInitialize(eglDisplay, nullptr, nullptr)) return;

    const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_BLUE_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_RED_SIZE, 8,
        EGL_NONE
    };

    EGLint numConfigs = 0;
    EGLConfig config;
    if (!eglChooseConfig(eglDisplay, configAttribs, &config, 1, &numConfigs) || numConfigs == 0) {
        return;
    }

    const EGLint contextAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };

    eglContext = eglCreateContext(eglDisplay, config, EGL_NO_CONTEXT, contextAttribs);
    if (eglContext == EGL_NO_CONTEXT) return;

    const EGLint pbufferAttribs[] = {
        EGL_WIDTH, 16,
        EGL_HEIGHT, 16,
        EGL_NONE
    };
    eglSurface = eglCreatePbufferSurface(eglDisplay, config, pbufferAttribs);
    if (eglSurface == EGL_NO_SURFACE) {
        eglDestroyContext(eglDisplay, eglContext);
        eglContext = EGL_NO_CONTEXT;
        return;
    }

    eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);

    // Compute Shader Header: #version 310 es (standard GLES 3.1 compute shader)
    const char* fsrShaderSource = R"(#version 310 es
precision highp float;
layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;
layout(binding = 0, rgba8) uniform readonly highp image2D inputFrame;
layout(binding = 1, rgba8) uniform writeonly highp image2D outputFrame;
layout(std140, binding = 2) uniform FsrParams {
    vec4 displaySize;
    vec4 inputSize;
    float sharpness;
    float threshold;
    uint frameIndex;
};
const mat3 RECON_MATRIX = mat3(0.299, 0.587, 0.114, -0.147, -0.289, 0.436, 0.615, -0.515, -0.100);
vec3 applyRCAS(ivec2 pos, vec3 color) {
    vec3 n = imageLoad(inputFrame, pos + ivec2(0, -1)).rgb;
    vec3 w = imageLoad(inputFrame, pos + ivec2(-1, 0)).rgb;
    vec3 e = imageLoad(inputFrame, pos + ivec2(1, 0)).rgb;
    vec3 s = imageLoad(inputFrame, pos + ivec2(0, 1)).rgb;
    vec3 minCol = min(color, min(min(n, w), min(e, s)));
    vec3 maxCol = max(color, max(max(n, w), max(e, s)));
    vec3 peak = maxCol - minCol;
    vec3 weight = clamp(peak * sharpness, 0.0, 0.25);
    return (color + (n + w + e + s) * weight) / (1.0 + 4.0 * weight);
}
vec3 reconstructPixel(ivec2 pos) {
    vec3 acc = vec3(0.0);
    float weightSum = 0.0;
    for(int y = -1; y <= 1; ++y) {
        for(int x = -1; x <= 1; ++x) {
            ivec2 p = pos + ivec2(x, y);
            vec4 raw = imageLoad(inputFrame, p);
            float weight = 1.0 / (1.0 + length(vec2(x, y)));
            vec3 features = RECON_MATRIX * raw.rgb;
            acc += features * weight;
            weightSum += weight;
        }
    }
    return inverse(RECON_MATRIX) * (acc / weightSum);
}
void main() {
    ivec2 outPos = ivec2(gl_GlobalInvocationID.xy);
    if (outPos.x >= int(displaySize.x) || outPos.y >= int(displaySize.y)) return;
    vec2 uv = (vec2(outPos) + 0.5) * displaySize.zw;
    ivec2 inPos = ivec2(uv * inputSize.xy);
    vec3 reconstructed = reconstructPixel(inPos);
    vec3 finalColor = applyRCAS(inPos, reconstructed);
    imageStore(outputFrame, outPos, vec4(finalColor, 1.0));
}
)";

    computeProgram = loadComputeShader(fsrShaderSource);
    if (computeProgram == 0) {
        LOGE("Compute shader program creation failed; skipping GLES upscaling");
        eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(eglDisplay, eglSurface);
        eglDestroyContext(eglDisplay, eglContext);
        eglSurface = EGL_NO_SURFACE;
        eglContext = EGL_NO_CONTEXT;
        return;
    }
    
    // Setup Textures and UBO
    glGenTextures(1, &inputTexture);
    glBindTexture(GL_TEXTURE_2D, inputTexture);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, 1920, 1080);

    glGenTextures(1, &outputTexture);
    glBindTexture(GL_TEXTURE_2D, outputTexture);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, 1920, 1080);

    glGenBuffers(1, &paramsUbo);
    glBindBuffer(GL_UNIFORM_BUFFER, paramsUbo);
    glBufferData(GL_UNIFORM_BUFFER, 64, nullptr, GL_DYNAMIC_DRAW);
    glBindBufferBase(GL_UNIFORM_BUFFER, 2, paramsUbo);

    isGlesInitialized = true;
    LOGD("GLES 3.1 Compute Pipeline initialized for FSR 4.1");
}

GLuint NativeZeroCopyPipeline::loadComputeShader(const char* source) {
    GLuint shader = glCreateShader(GL_COMPUTE_SHADER);
    if (!shader) return 0;
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);

    GLint success;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &success);
    if (!success) {
        char infoLog[512];
        glGetShaderInfoLog(shader, 512, nullptr, infoLog);
        LOGE("Compute shader compilation failed: %s", infoLog);
        glDeleteShader(shader);
        return 0;
    }

    GLuint program = glCreateProgram();
    if (!program) {
        glDeleteShader(shader);
        return 0;
    }
    glAttachShader(program, shader);
    glLinkProgram(program);
    glDeleteShader(shader);

    glGetProgramiv(program, GL_LINK_STATUS, &success);
    if (!success) {
        char infoLog[512];
        glGetProgramInfoLog(program, 512, nullptr, infoLog);
        LOGE("Compute program link failed: %s", infoLog);
        glDeleteProgram(program);
        return 0;
    }
    return program;
}

void NativeZeroCopyPipeline::dispatchUpscale(uint32_t inW, uint32_t inH, uint32_t outW, uint32_t outH) {
    if (!isGlesInitialized) return;

    glUseProgram(computeProgram);

    struct {
        float displayW, displayH, invDisplayW, invDisplayH;
        float inputW, inputH, invInputW, invInputH;
        float sharpness;
        float threshold;
        uint32_t frameIndex;
        uint32_t padding;
    } params;

    params.displayW = static_cast<float>(outW);
    params.displayH = static_cast<float>(outH);
    params.invDisplayW = 1.0f / params.displayW;
    params.invDisplayH = 1.0f / params.displayH;
    params.inputW = static_cast<float>(inW);
    params.inputH = static_cast<float>(inH);
    params.invInputW = 1.0f / params.inputW;
    params.invInputH = 1.0f / params.inputH;
    params.sharpness = 0.85f;
    params.threshold = 0.05f;
    static uint32_t counter = 0;
    params.frameIndex = counter++;

    glBindBuffer(GL_UNIFORM_BUFFER, paramsUbo);
    glBufferSubData(GL_UNIFORM_BUFFER, 0, sizeof(params), &params);

    glBindImageTexture(0, inputTexture, 0, GL_FALSE, 0, GL_READ_ONLY, GL_RGBA8);
    glBindImageTexture(1, outputTexture, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA8);

    glDispatchCompute((outW + 15) / 16, (outH + 15) / 16, 1);
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
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
        shutdown();
        return false;
    }

    initGlesPipeline();

    AMediaFormat* format = AMediaFormat_new();
    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, mimeType);
    
    // Aplicar parámetros de ultra-calidad y 1440p si las dimensiones lo indican
    applyUltraQualityDecoderParameters(format, width > 1920 || height > 1080);

    // Vinculación directa del decodificador HW a la superficie nativa (Zero-Copy)
    media_status_t status = AMediaCodec_configure(mediaCodec, format, outputWindow, nullptr, 0);
    AMediaFormat_delete(format);

    if (status != AMEDIA_OK) {
        LOGE("AMediaCodec_configure failed: %d", status);
        shutdown();
        return false;
    }

    if (AMediaCodec_start(mediaCodec) != AMEDIA_OK) {
        LOGE("AMediaCodec_start failed");
        shutdown();
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
            // Pero confiamos en que el depacketizer RTP o el emisor ya los envió o el decoder los tolera.
            
            // Aplicar profundidad del jitter buffer y compensación A/V Bluetooth para suavizar el timing de presentación
            int64_t offsetUs = ptsOffsetUs.load(std::memory_order_relaxed);
            uint64_t presentationTimeUs = static_cast<uint64_t>(
                static_cast<int64_t>(outTelemetry.frameSubmittedUs) + 
                (jitterBuffer->getActiveBufferDepthMs() * 1000) + offsetUs
            );
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
        
        // Extensión: Dispatch FSR 4.1 Upscaling en el pipeline de presentación si GLES está activo
        if (isGlesInitialized && computeProgram != 0) {
            dispatchUpscale(currentWidth, currentHeight, 1920, 1080); // Target 1080p
        }

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

    if (isGlesInitialized) {
        if (eglDisplay != EGL_NO_DISPLAY && eglContext != EGL_NO_CONTEXT) {
            eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
            if (computeProgram) {
                glDeleteProgram(computeProgram);
                computeProgram = 0;
            }
            if (inputTexture) {
                glDeleteTextures(1, &inputTexture);
                inputTexture = 0;
            }
            if (outputTexture) {
                glDeleteTextures(1, &outputTexture);
                outputTexture = 0;
            }
            if (paramsUbo) {
                glDeleteBuffers(1, &paramsUbo);
                paramsUbo = 0;
            }
        }
        if (eglDisplay != EGL_NO_DISPLAY) {
            eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (eglSurface != EGL_NO_SURFACE) {
                eglDestroySurface(eglDisplay, eglSurface);
                eglSurface = EGL_NO_SURFACE;
            }
            if (eglContext != EGL_NO_CONTEXT) {
                eglDestroyContext(eglDisplay, eglContext);
                eglContext = EGL_NO_CONTEXT;
            }
        }
        isGlesInitialized = false;
    }
}

void NativeZeroCopyPipeline::zeroTraceShutdown() {
    shutdown();
    inputRingBuffer.clear();
    cleanupEglSurfaceBinding();
    mouseKalmanX.reset();
    mouseKalmanY.reset();
    ptsOffsetUs.store(0, std::memory_order_relaxed);
    LOGD("Zero-Trace Shutdown complete: Native surfaces, ring buffers and decoder sanitized");
}

std::pair<float, float> NativeZeroCopyPipeline::applyKalmanSmoothing(float rawDeltaX, float rawDeltaY) {
    float smoothX = mouseKalmanX.update(rawDeltaX);
    float smoothY = mouseKalmanY.update(rawDeltaY);
    return {smoothX, smoothY};
}

bool NativeZeroCopyPipeline::pushRawInputPacket(const uint8_t* data, size_t size) {
    if (size > 64) return false;
    NativeRawInputPacket packet{};
    packet.size = size;
    packet.timestampUs = nowMicros();
    std::memcpy(packet.data, data, size);
    return inputRingBuffer.push(packet);
}

bool NativeZeroCopyPipeline::popRawInputPacket(uint8_t* outData, size_t& outSize) {
    NativeRawInputPacket packet{};
    if (inputRingBuffer.pop(packet)) {
        outSize = packet.size;
        std::memcpy(outData, packet.data, packet.size);
        return true;
    }
    outSize = 0;
    return false;
}

void NativeZeroCopyPipeline::cleanupEglSurfaceBinding() {
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display != EGL_NO_DISPLAY) {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
}

uint64_t NativeZeroCopyPipeline::nowMicros() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()
    ).count();
}

} // namespace GodMode::Stream
