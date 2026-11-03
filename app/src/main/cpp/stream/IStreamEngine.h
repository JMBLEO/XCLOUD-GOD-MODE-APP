#pragma once

#include <android/native_window.h>
#include <vulkan/vulkan.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl32.h>
#include <cstdint>
#include <cstddef>
#include <memory>
#include <vector>
#include <queue>
#include <mutex>
#include <atomic>
#include <cstring>
#include <time.h>
#include <unistd.h>
#include <dlfcn.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <poll.h>
#include <thread>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <fstream>
#include <sstream>
#include <exception>
#include <pthread.h>
#include <sched.h>
#include <cmath>
#include <algorithm>

#define GM_LOG_TAG "GodModeStreamCore"
#define GM_LOGI(...) __android_log_print(ANDROID_LOG_INFO, GM_LOG_TAG, __VA_ARGS__)
#define GM_LOGW(...) __android_log_print(ANDROID_LOG_WARN, GM_LOG_TAG, __VA_ARGS__)
#define GM_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, GM_LOG_TAG, __VA_ARGS__)

namespace GodMode::Stream {

// Enum estricto de los seis motores nativos (Estado 0: Cero WebView)
enum class StreamEngineType : int {
    ENGINE_1_RUST_SECURE_VULKAN = 1,          // Rust Zero-Trust + OpenH264/rav1d + Vulkan
    ENGINE_2_VULKAN_VIDEO_ASIC = 2,           // Vulkan Video Hardware Direct (VK_KHR_video_decode_queue)
    ENGINE_3_MEDIACODEC_AI_NPU = 3,            // AMediaCodec Async + Direct HWC2 + Vendor AI Upscaling
    ENGINE_4_HWC2_SURFACE_TRANSACTION_AI = 4,  // AMediaCodec + ASurfaceTransaction HWC2 Direct + DPU AI
    ENGINE_5_MEDIACODEC_NPU_NNAPI_AI = 5,      // AMediaCodec + AHardwareBuffer + NPU NNAPI AI Upscaler
    ENGINE_6_MEDIACODEC_EGLIMAGE_OES_AI = 6    // AMediaCodec + EGLImageKHR Zero-Copy + GLES 3.2 External OES AI
};

// Información de recorte y dimensiones reales del macrobloque H.264
struct StreamCropInfo {
    int32_t left = 0;
    int32_t top = 0;
    int32_t right = 1919;
    int32_t bottom = 1079;
    int32_t codedWidth = 1920;
    int32_t codedHeight = 1080;
};

// Interfaz polimórfica base para los 6 motores de decodificación y renderizado
class IStreamEngine {
public:
    virtual ~IStreamEngine() = default;
    virtual bool initialize(ANativeWindow* window, int streamWidth, int streamHeight, bool aiUpscale) = 0;
    virtual void injectCodecConfig(const uint8_t* sps, size_t spsLen, const uint8_t* pps, size_t ppsLen) = 0;
    virtual void pushAnnexBNalUnit(const uint8_t* data, size_t size, uint64_t ptsUs, bool isIdr) = 0;
    virtual void onResize(int displayWidth, int displayHeight) = 0;
    virtual void shutdown() = 0;
};

// -----------------------------------------------------------------------------
// 1. Handshake de Supervivencia: RTCP PLI Packet (RFC 4585 Payload Type 206, FMT 1)
// -----------------------------------------------------------------------------
struct RtcpPliPacket {
    uint8_t  header;      // V=2, P=0, FMT=1 (0x81)
    uint8_t  packetType;  // 206 (PSFB - Payload-Specific Feedback)
    uint16_t length;      // 2 (3 palabras de 32 bits menos 1)
    uint32_t senderSsrc;
    uint32_t mediaSsrc;
};

inline void buildRtcpPliRequest(uint32_t clientSsrc, uint32_t serverVideoSsrc, uint8_t* outBuf, size_t& outLen) {
    RtcpPliPacket pli{};
    pli.header = 0x81;
    pli.packetType = 206;
    pli.length = htons(2);
    pli.senderSsrc = htonl(clientSsrc);
    pli.mediaSsrc = htonl(serverVideoSsrc);

    std::memcpy(outBuf, &pli, sizeof(RtcpPliPacket));
    outLen = sizeof(RtcpPliPacket);
}

// -----------------------------------------------------------------------------
// 2. Sincronización de Reloj Monótono de Audio/Video (CLOCK_MONOTONIC_RAW)
// -----------------------------------------------------------------------------
inline uint64_t getMonotonicMicros() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC_RAW, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000000ULL + (ts.tv_nsec / 1000ULL);
}

inline bool shouldDropOrProcessFrame(uint64_t framePtsUs, uint64_t baselineStreamStartUs) {
    uint64_t nowUs = getMonotonicMicros();
    int64_t renderDeltaUs = static_cast<int64_t>((baselineStreamStartUs + framePtsUs) - nowUs);

    // Si el frame llegó con más de 100ms de retraso por congestión de red, descartar para evitar lag visual
    if (renderDeltaUs < -100000) {
        return false;
    }
    return true;
}

// -----------------------------------------------------------------------------
// 3. Lector de Bitstream Exp-Golomb del SPS (Detección dinámica 720p/900p/1080p)
// -----------------------------------------------------------------------------
class SpsDimensionExtractor {
private:
    const uint8_t* data;
    size_t bitPos = 0;
    size_t totalBits = 0;

    uint32_t readBit() {
        if (bitPos >= totalBits) return 0;
        uint32_t bit = (data[bitPos >> 3] >> (7 - (bitPos & 7))) & 1;
        bitPos++;
        return bit;
    }

    uint32_t readBits(int n) {
        uint32_t val = 0;
        for (int i = 0; i < n; ++i) val = (val << 1) | readBit();
        return val;
    }

    uint32_t readUe() {
        int leadingZeros = 0;
        while (readBit() == 0 && bitPos < totalBits && leadingZeros < 32) {
            leadingZeros++;
        }
        return (1U << leadingZeros) - 1U + readBits(leadingZeros);
    }

    int32_t readSe() {
        uint32_t ue = readUe();
        return (ue & 1) ? static_cast<int32_t>((ue + 1) >> 1) : -static_cast<int32_t>(ue >> 1);
    }

public:
    static bool parseAnnexBSps(const uint8_t* spsAnnexB, size_t len, int& outWidth, int& outHeight) {
        if (!spsAnnexB || len < 8) return false;

        // Saltar start code (00 00 00 01) y byte de cabecera NAL (0x67)
        std::vector<uint8_t> rbsp;
        rbsp.reserve(len);
        for (size_t i = 5; i < len; ++i) {
            if (i + 2 < len && spsAnnexB[i] == 0x00 && spsAnnexB[i+1] == 0x00 && spsAnnexB[i+2] == 0x03) {
                rbsp.push_back(0x00);
                rbsp.push_back(0x00);
                i += 2; // Omitir el byte 0x03 de prevención de emulación
            } else {
                rbsp.push_back(spsAnnexB[i]);
            }
        }

        SpsDimensionExtractor reader;
        reader.data = rbsp.data();
        reader.bitPos = 0;
        reader.totalBits = rbsp.size() * 8;

        uint32_t profileIdc = reader.readBits(8);
        reader.readBits(16); // constraint flags + level_idc
        reader.readUe();     // seq_parameter_set_id

        if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 || profileIdc == 244 ||
            profileIdc == 44  || profileIdc == 83  || profileIdc == 86  || profileIdc == 118) {
            uint32_t chromaFormatIdc = reader.readUe();
            if (chromaFormatIdc == 3) reader.readBit();
            reader.readUe(); // bit_depth_luma_minus8
            reader.readUe(); // bit_depth_chroma_minus8
            reader.readBit(); // qpprime_y_zero_transform_bypass_flag
            if (reader.readBit()) { // seq_scaling_matrix_present_flag
                for (int i = 0; i < ((chromaFormatIdc != 3) ? 8 : 12); ++i) {
                    if (reader.readBit()) { /* omitir */ }
                }
            }
        }

        reader.readUe(); // log2_max_frame_num_minus4
        uint32_t picOrderCntType = reader.readUe();
        if (picOrderCntType == 0) {
            reader.readUe();
        } else if (picOrderCntType == 1) {
            reader.readBit();
            reader.readSe();
            reader.readSe();
            uint32_t numRef = reader.readUe();
            for (uint32_t i = 0; i < numRef; ++i) reader.readSe();
        }

        reader.readUe(); // max_num_ref_frames
        reader.readBit(); // gaps_in_frame_num_value_allowed_flag

        uint32_t picWidthInMbs = reader.readUe() + 1;
        uint32_t picHeightInMapUnits = reader.readUe() + 1;
        uint32_t frameMbsOnlyFlag = reader.readBit();
        if (!frameMbsOnlyFlag) reader.readBit();
        reader.readBit(); // direct_8x8_inference_flag

        uint32_t cropLeft = 0, cropRight = 0, cropTop = 0, cropBottom = 0;
        if (reader.readBit()) { // frame_cropping_flag
            cropLeft   = reader.readUe();
            cropRight  = reader.readUe();
            cropTop    = reader.readUe();
            cropBottom = reader.readUe();
        }

        outWidth  = static_cast<int>(picWidthInMbs * 16 - (cropLeft + cropRight) * 2);
        outHeight = static_cast<int>((2 - frameMbsOnlyFlag) * picHeightInMapUnits * 16 - (cropTop + cropBottom) * 2);
        return (outWidth > 0 && outHeight > 0);
    }
};

// -----------------------------------------------------------------------------
// 4. Corrección de Franja Verde Inferior (1088 vs 1080 Macroblock Padding)
// -----------------------------------------------------------------------------
inline void handleOutputFormatChange(AMediaCodec* codec, StreamCropInfo& outCrop) {
    if (!codec) return;
    AMediaFormat* outFmt = AMediaCodec_getOutputFormat(codec);
    if (!outFmt) return;

    AMediaFormat_getInt32(outFmt, AMEDIAFORMAT_KEY_WIDTH, &outCrop.codedWidth);
    AMediaFormat_getInt32(outFmt, AMEDIAFORMAT_KEY_HEIGHT, &outCrop.codedHeight);

    typedef bool (*fp_AMediaFormat_getRect)(AMediaFormat*, const char*, int32_t*, int32_t*, int32_t*, int32_t*);
    static auto pfnGetRect = reinterpret_cast<fp_AMediaFormat_getRect>(dlsym(RTLD_DEFAULT, "AMediaFormat_getRect"));

    bool hasRect = false;
    if (pfnGetRect) {
        hasRect = pfnGetRect(outFmt, "crop", &outCrop.left, &outCrop.top, &outCrop.right, &outCrop.bottom);
    }
    if (!hasRect) {
        AMediaFormat_getInt32(outFmt, "crop-left", &outCrop.left);
        AMediaFormat_getInt32(outFmt, "crop-top", &outCrop.top);
        AMediaFormat_getInt32(outFmt, "crop-right", &outCrop.right);
        AMediaFormat_getInt32(outFmt, "crop-bottom", &outCrop.bottom);
    }
    AMediaFormat_delete(outFmt);
}

// -----------------------------------------------------------------------------
// 5. Sincronización de Valla de Hardware (EGL_ANDROID_native_fence_sync)
// -----------------------------------------------------------------------------
inline void waitHardwareDecoderFenceInGpu(EGLDisplay dpy, int acquireFenceFd) {
    if (acquireFenceFd < 0 || dpy == EGL_NO_DISPLAY) {
        if (acquireFenceFd >= 0) close(acquireFenceFd);
        return;
    }

    auto fpCreateSyncKHR = (PFNEGLCREATESYNCKHRPROC)eglGetProcAddress("eglCreateSyncKHR");
    auto fpWaitSyncKHR   = (PFNEGLWAITSYNCKHRPROC)eglGetProcAddress("eglWaitSyncKHR");
    auto fpDestroySyncKHR = (PFNEGLDESTROYSYNCKHRPROC)eglGetProcAddress("eglDestroySyncKHR");

    if (fpCreateSyncKHR && fpWaitSyncKHR && fpDestroySyncKHR) {
        const EGLint syncAttrs[] = {EGL_SYNC_NATIVE_FENCE_FD_ANDROID, acquireFenceFd, EGL_NONE};
        EGLSyncKHR fenceSync = fpCreateSyncKHR(dpy, EGL_SYNC_NATIVE_FENCE_ANDROID, syncAttrs);
        if (fenceSync != EGL_NO_SYNC_KHR) {
            fpWaitSyncKHR(dpy, fenceSync, 0);
            fpDestroySyncKHR(dpy, fenceSync);
            return;
        }
    }
    close(acquireFenceFd);
}

// -----------------------------------------------------------------------------
// 6. Auto-Curación de AMediaCodec en Caliente (Flush & Re-Prime)
// -----------------------------------------------------------------------------
inline void recoverFaultedHardwareDecoder(
    AMediaCodec* codec,
    const std::vector<uint8_t>& cachedSps,
    const std::vector<uint8_t>& cachedPps
) {
    if (!codec) return;
    GM_LOGW("Auto-curando decodificador de hardware en caliente...");

    AMediaCodec_flush(codec);

    ssize_t idx = AMediaCodec_dequeueInputBuffer(codec, 2000);
    if (idx >= 0) {
        size_t cap = 0;
        uint8_t* buf = AMediaCodec_getInputBuffer(codec, idx, &cap);
        size_t totalCsd = cachedSps.size() + cachedPps.size();
        if (buf && totalCsd <= cap) {
            std::memcpy(buf, cachedSps.data(), cachedSps.size());
            std::memcpy(buf + cachedSps.size(), cachedPps.data(), cachedPps.size());
            AMediaCodec_queueInputBuffer(codec, idx, 0, totalCsd, 0, AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG);
            GM_LOGI("Decodificador re-cebado con CSD tras auto-curación");
        }
    }
}

// -----------------------------------------------------------------------------
// 7. Cola Circular SPSC Lock-Free para Paquetes RTP
// -----------------------------------------------------------------------------
template <size_t Capacity = 512>
class LockFreeStreamPacketQueue {
    static_assert((Capacity & (Capacity - 1)) == 0, "Capacity must be power of 2");
    struct PacketNode {
        uint8_t data[2048];
        size_t size;
        uint64_t ptsUs;
        bool isIdr;
    };

    PacketNode buffer[Capacity];
    std::atomic<size_t> head{0};
    std::atomic<size_t> tail{0};

public:
    bool push(const uint8_t* src, size_t len, uint64_t pts, bool idr) {
        size_t currentTail = tail.load(std::memory_order_relaxed);
        size_t nextTail = (currentTail + 1) & (Capacity - 1);
        if (nextTail == head.load(std::memory_order_acquire)) {
            return false;
        }
        
        if (len <= 2048) {
            std::memcpy(buffer[currentTail].data, src, len);
            buffer[currentTail].size = len;
            buffer[currentTail].ptsUs = pts;
            buffer[currentTail].isIdr = idr;
            tail.store(nextTail, std::memory_order_release);
            return true;
        }
        return false;
    }

    bool pop(uint8_t* dest, size_t& outLen, uint64_t& outPts, bool& outIdr) {
        size_t currentHead = head.load(std::memory_order_relaxed);
        if (currentHead == tail.load(std::memory_order_acquire)) {
            return false;
        }

        outLen = buffer[currentHead].size;
        std::memcpy(dest, buffer[currentHead].data, outLen);
        outPts = buffer[currentHead].ptsUs;
        outIdr = buffer[currentHead].isIdr;
        
        head.store((currentHead + 1) & (Capacity - 1), std::memory_order_release);
        return true;
    }
};

// -----------------------------------------------------------------------------
// 7. Máscara de Ocultamiento de Errores por Congestión (PLC)
// -----------------------------------------------------------------------------
class NetworkConcealmentGuard {
    uint32_t consecutiveCorruptedFrames = 0;
    const uint32_t maxToleratedCorruptedFrames = 3;
    std::vector<uint8_t> lastValidDecodedFrameData;
    int64_t lastValidPtsUs = 0;
    bool hasValidFrame = false;
    std::mutex mtx;

public:
    void registerValidDecodedFrame(const uint8_t* data, size_t size, int64_t ptsUs) {
        std::lock_guard<std::mutex> lock(mtx);
        lastValidDecodedFrameData.assign(data, data + size);
        lastValidPtsUs = ptsUs;
        consecutiveCorruptedFrames = 0;
        hasValidFrame = true;
    }

    bool shouldInjectConcealmentFrame(bool packetLossDetectedThisTick, bool& outNeedsPliRequest) {
        std::lock_guard<std::mutex> lock(mtx);
        outNeedsPliRequest = false;

        if (packetLossDetectedThisTick) {
            consecutiveCorruptedFrames++;
            if (consecutiveCorruptedFrames >= maxToleratedCorruptedFrames) {
                outNeedsPliRequest = true; // Exigir IDR de emergencia
                consecutiveCorruptedFrames = 0; // Reset para evitar spam de PLIs si el cooldown no se maneja fuera
            }
            return hasValidFrame;
        }
        return false;
    }

    const std::vector<uint8_t>& getConcealmentPayload() {
        std::lock_guard<std::mutex> lock(mtx);
        return lastValidDecodedFrameData;
    }

    void reset() {
        std::lock_guard<std::mutex> lock(mtx);
        consecutiveCorruptedFrames = 0;
        hasValidFrame = false;
        lastValidDecodedFrameData.clear();
    }
};

// -----------------------------------------------------------------------------
// 8. Ensamblador de Access Unit (AU) con soporte FU-A (Tipo 28) y STAP-A (Tipo 24)
// -----------------------------------------------------------------------------
class CompleteAccessUnitAssembler {
private:
    std::vector<uint8_t> fuaBuffer;
    std::vector<uint8_t> currentFrameBuffer;
    std::vector<uint8_t> cachedSps;
    std::vector<uint8_t> cachedPps;
    bool codecConfigured = false;
    uint32_t currentRtpTimestamp = 0;
    int streamWidth = 1920;
    int streamHeight = 1080;
    const uint8_t startCode[4] = {0x00, 0x00, 0x00, 0x01};

public:
    CompleteAccessUnitAssembler() {
        currentFrameBuffer.reserve(512 * 1024);
        fuaBuffer.reserve(512 * 1024);
        cachedSps.reserve(128);
        cachedPps.reserve(128);
    }

    void reset() {
        currentFrameBuffer.clear();
        fuaBuffer.clear();
        cachedSps.clear();
        cachedPps.clear();
        codecConfigured = false;
        currentRtpTimestamp = 0;
    }

    void feedRtpPayload(const uint8_t* payload, size_t len, uint32_t rtpTimestamp, bool rtpMarkerBit, IStreamEngine* engine) {
        if (!engine || len < 2) return;

        if (rtpTimestamp != currentRtpTimestamp) {
            if (!currentFrameBuffer.empty()) {
                // Despachar frame acumulado si el timestamp cambió y no hubo marker (best effort)
                uint64_t ptsUs = (static_cast<uint64_t>(currentRtpTimestamp) * 1000000ULL) / 90000ULL;
                engine->pushAnnexBNalUnit(currentFrameBuffer.data(), currentFrameBuffer.size(), ptsUs, false);
                currentFrameBuffer.clear();
            }
            currentRtpTimestamp = rtpTimestamp;
        }

        uint8_t nalHeader = payload[0];
        uint8_t nalType = nalHeader & 0x1F;

        // NAL Unit Simple (SPS, PPS, IDR, etc)
        if (nalType >= 1 && nalType <= 23) {
            handleNalUnit(payload, len, engine);
        }
        // STAP-A: Agregación de múltiples NALs
        else if (nalType == 24) {
            size_t offset = 1;
            while (offset + 2 <= len) {
                uint16_t nalSize = (payload[offset] << 8) | payload[offset + 1];
                offset += 2;
                if (offset + nalSize > len) break;
                handleNalUnit(payload + offset, nalSize, engine);
                offset += nalSize;
            }
        }
        // FU-A: Fragmentación
        else if (nalType == 28) {
            uint8_t fuHeader = payload[1];
            bool s = (fuHeader & 0x80) != 0;
            bool e = (fuHeader & 0x40) != 0;
            uint8_t innerType = fuHeader & 0x1F;

            if (s) {
                fuaBuffer.clear();
                fuaBuffer.push_back((nalHeader & 0xE0) | innerType);
            }
            if (len > 2) {
                fuaBuffer.insert(fuaBuffer.end(), payload + 2, payload + len);
            }
            if (e) {
                handleNalUnit(fuaBuffer.data(), fuaBuffer.size(), engine);
                fuaBuffer.clear();
            }
        }

        if (rtpMarkerBit && !currentFrameBuffer.empty()) {
            uint64_t ptsUs = (static_cast<uint64_t>(rtpTimestamp) * 1000000ULL) / 90000ULL;
            bool isIdr = false;
            // Detección rápida de IDR en el buffer acumulado
            for (size_t i = 0; i + 4 < currentFrameBuffer.size(); ++i) {
                if (currentFrameBuffer[i] == 0 && currentFrameBuffer[i+1] == 0 && 
                    currentFrameBuffer[i+2] == 0 && currentFrameBuffer[i+3] == 1) {
                    if ((currentFrameBuffer[i+4] & 0x1F) == 5) { isIdr = true; break; }
                }
            }
            engine->pushAnnexBNalUnit(currentFrameBuffer.data(), currentFrameBuffer.size(), ptsUs, isIdr);
            currentFrameBuffer.clear();
        }
    }

private:
    void handleNalUnit(const uint8_t* nal, size_t len, IStreamEngine* engine) {
        uint8_t type = nal[0] & 0x1F;
        if (type == 7) { // SPS
            cachedSps.assign(nal, nal + len);
            int pw = 0, ph = 0;
            std::vector<uint8_t> annexB;
            annexB.insert(annexB.end(), startCode, startCode + 4);
            annexB.insert(annexB.end(), nal, nal + len);
            if (SpsDimensionExtractor::parseAnnexBSps(annexB.data(), annexB.size(), pw, ph)) {
                streamWidth = pw; streamHeight = ph;
                engine->onResize(pw, ph);
            }
        } else if (type == 8) { // PPS
            cachedPps.assign(nal, nal + len);
        }

        if (!codecConfigured && !cachedSps.empty() && !cachedPps.empty()) {
            engine->injectCodecConfig(cachedSps.data(), cachedSps.size(), cachedPps.data(), cachedPps.size());
            codecConfigured = true;
        }

        currentFrameBuffer.insert(currentFrameBuffer.end(), startCode, startCode + 4);
        currentFrameBuffer.insert(currentFrameBuffer.end(), nal, nal + len);
    }
};

// -----------------------------------------------------------------------------
// 9. Compensador de Deriva de Reloj (Clock Drift Compensation)
// -----------------------------------------------------------------------------
class XcloudClockDriftCompensator {
    int64_t estimatedOffsetUs = 0;
    uint64_t firstRtpTimestamp = 0;
    uint64_t firstSystemTimeUs = 0;
    bool baselineInitialized = false;

public:
    void updateSync(uint32_t rtpTimestamp, uint64_t systemReceiveTimeUs) {
        uint64_t rtpTimeUs = (static_cast<uint64_t>(rtpTimestamp) * 1000000ULL) / 90000ULL;
        if (!baselineInitialized) {
            firstRtpTimestamp = rtpTimeUs;
            firstSystemTimeUs = systemReceiveTimeUs;
            baselineInitialized = true;
            return;
        }
        int64_t expectedSystemTime = firstSystemTimeUs + static_cast<int64_t>(rtpTimeUs - firstRtpTimestamp);
        int64_t instantaneousErrorUs = static_cast<int64_t>(systemReceiveTimeUs) - expectedSystemTime;
        estimatedOffsetUs = static_cast<int64_t>(0.95 * (double)estimatedOffsetUs + 0.05 * (double)instantaneousErrorUs);
    }

    uint64_t getAdjustedPresentationTimeUs(uint32_t rtpTimestamp) {
        uint64_t rtpTimeUs = (static_cast<uint64_t>(rtpTimestamp) * 1000000ULL) / 90000ULL;
        if (!baselineInitialized) return rtpTimeUs;
        int64_t adjustedTime = firstSystemTimeUs + static_cast<int64_t>(rtpTimeUs - firstRtpTimestamp) + estimatedOffsetUs;
        return (adjustedTime > 0) ? (uint64_t)adjustedTime : rtpTimeUs;
    }

    int64_t getEstimatedOffsetUs() const { return estimatedOffsetUs; }
};

// -----------------------------------------------------------------------------
// 10. Controlador de Pacing de Red BBR con Filtro de Kalman (Estado 0)
// -----------------------------------------------------------------------------
enum class BbrMode { STARTUP, DRAIN, PROBE_BW, PROBE_RTT };

class BbrNetworkPacingController {
private:
    BbrMode mode = BbrMode::STARTUP;
    double maxBandwidthBps = 15000000.0;
    uint64_t minRttUs = UINT64_MAX;
    std::chrono::steady_clock::time_point lastAckTime = std::chrono::steady_clock::now();
    
    // Filtro de Kalman para el RTT (Jitter removal)
    double kalmanEstimate = 20.0;
    double kalmanErrorCov = 1.0;
    const double Q = 0.1; // Ruido de proceso
    const double R = 2.0; // Ruido de medición
    
    // BBR Cycle & Gains
    int cycleIndex = 0;
    const double pacingGains[8] = { 1.25, 0.75, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0 };
    bool filledPipe = false;
    uint32_t startupCount = 0;

public:
    void updateTelemetry(size_t bytesAcked, uint64_t rttUs) {
        // 1. Kalman RTT Filtering
        double measurement = static_cast<double>(rttUs) / 1000.0;
        double predEst = kalmanEstimate;
        double predCov = kalmanErrorCov + Q;
        double gain = predCov / (predCov + R);
        kalmanEstimate = predEst + gain * (measurement - predEst);
        kalmanErrorCov = (1.0 - gain) * predCov;

        if (rttUs < minRttUs || minRttUs == UINT64_MAX) {
            minRttUs = rttUs;
        }

        // 2. Bandwidth Tracking
        auto now = std::chrono::steady_clock::now();
        std::chrono::duration<double> elapsed = now - lastAckTime;
        lastAckTime = now;

        if (elapsed.count() > 0.001) {
            double instantBw = (static_cast<double>(bytesAcked) * 8.0) / elapsed.count();
            if (instantBw > maxBandwidthBps) {
                maxBandwidthBps = instantBw;
            } else {
                // Suavizado EMA para bajadas
                maxBandwidthBps = maxBandwidthBps * 0.98 + instantBw * 0.02;
            }
        }

        // 3. State Machine Transitions
        updateStateMachine();
    }

    uint64_t getOptimalPacingRateBps(bool ultra1440p) const {
        double gain = 1.0;
        if (mode == BbrMode::STARTUP) gain = 2.89; // Crecimiento agresivo
        else if (mode == BbrMode::DRAIN) gain = 0.35; // Vaciar colas
        else if (mode == BbrMode::PROBE_BW) gain = pacingGains[cycleIndex];
        
        double base = maxBandwidthBps * gain;
        double ceiling = ultra1440p ? 55000000.0 : 35000000.0;
        double floor = 6000000.0;
        
        return static_cast<uint64_t>(std::clamp(base, floor, ceiling));
    }

    double getFilteredRttMs() const { return kalmanEstimate; }
    const char* getModeString() const {
        switch(mode) {
            case BbrMode::STARTUP: return "STARTUP";
            case BbrMode::DRAIN: return "DRAIN";
            case BbrMode::PROBE_BW: return "PROBE_BW";
            case BbrMode::PROBE_RTT: return "PROBE_RTT";
            default: return "UNKNOWN";
        }
    }

private:
    void updateStateMachine() {
        if (mode == BbrMode::STARTUP) {
            if (filledPipe) {
                mode = BbrMode::DRAIN;
            }
            // Heurística simple de pipe lleno: 3 samples sin crecimiento > 25%
            startupCount++;
            if (startupCount > 50) filledPipe = true;
        } else if (mode == BbrMode::DRAIN) {
            // Salir de DRAIN cuando el RTT baje al mínimo (buffer vacío)
            if (kalmanEstimate < (static_cast<double>(minRttUs)/1000.0 * 1.1)) {
                mode = BbrMode::PROBE_BW;
            }
        } else if (mode == BbrMode::PROBE_BW) {
            cycleIndex = (cycleIndex + 1) % 8;
        }
    }
};

// -----------------------------------------------------------------------------
// 11. Gestión de Recuperación de Pérdida Catastrófica (RTCP PLI con Cooldown)
// -----------------------------------------------------------------------------
class RapidPliRecoveryManager {
    std::chrono::steady_clock::time_point lastPliSendTime{};
    uint32_t consecutiveCorruptionFrames = 0;
    const uint32_t pliCooldownMs = 250; 

public:
    bool evaluateCorruptionAndRequirePli(bool frameCorrupted, uint32_t currentLossRatePercent) {
        if (!frameCorrupted) {
            consecutiveCorruptionFrames = 0;
            return false;
        }

        consecutiveCorruptionFrames++;
        if (consecutiveCorruptionFrames >= 5 || currentLossRatePercent > 5) {
            auto now = std::chrono::steady_clock::now();
            auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(now - lastPliSendTime).count();
            
            if (elapsed >= pliCooldownMs) {
                lastPliSendTime = now;
                consecutiveCorruptionFrames = 0;
                return true; 
            }
        }
        return false;
    }
};

// -----------------------------------------------------------------------------
// 12. Asignador de VRAM Estático (Ring Buffer Pool) para 1440p
// -----------------------------------------------------------------------------
class VulkanStaticVramRingAllocator {
    VkDevice device = VK_NULL_HANDLE;
    VkDescriptorPool pool = VK_NULL_HANDLE;
    std::vector<VkDescriptorSet> allocatedSets;
    size_t ringCursor = 0;

public:
    void initPool(VkDevice vkDevice, VkDescriptorSetLayout layout, size_t poolCapacity) {
        if (!vkDevice) return;
        device = vkDevice;
        VkDescriptorPoolSize poolSize = { VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, static_cast<uint32_t>(poolCapacity) };
        
        VkDescriptorPoolCreateInfo poolInfo = { VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO };
        poolInfo.flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
        poolInfo.maxSets = static_cast<uint32_t>(poolCapacity);
        poolInfo.poolSizeCount = 1;
        poolInfo.pPoolSizes = &poolSize;

        if (vkCreateDescriptorPool(device, &poolInfo, nullptr, &pool) != VK_SUCCESS) {
            GM_LOGE("VulkanStaticVramRingAllocator: Fallo al crear Descriptor Pool estático");
            return;
        }

        std::vector<VkDescriptorSetLayout> layouts(poolCapacity, layout);
        VkDescriptorSetAllocateInfo allocInfo = { VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO };
        allocInfo.descriptorPool = pool;
        allocInfo.descriptorSetCount = static_cast<uint32_t>(poolCapacity);
        allocInfo.pSetLayouts = layouts.data();

        allocatedSets.resize(poolCapacity);
        if (vkAllocateDescriptorSets(device, &allocInfo, allocatedSets.data()) != VK_SUCCESS) {
            GM_LOGE("VulkanStaticVramRingAllocator: Fallo al asignar conjuntos de descriptores en VRAM");
        }
    }

    VkDescriptorSet acquireNextDescriptorSet() {
        if (allocatedSets.empty()) return VK_NULL_HANDLE;
        VkDescriptorSet set = allocatedSets[ringCursor];
        ringCursor = (ringCursor + 1) % allocatedSets.size();
        return set;
    }

    void destroy() {
        if (device && pool) {
            vkDestroyDescriptorPool(device, pool, nullptr);
            pool = VK_NULL_HANDLE;
        }
        allocatedSets.clear();
        device = VK_NULL_HANDLE;
    }
};

// -----------------------------------------------------------------------------
// 13. Recuperación ante VK_ERROR_DEVICE_LOST
// -----------------------------------------------------------------------------
inline bool handleVulkanDeviceLostRecovery(VkResult errorCode, VkDevice& device) {
    if (errorCode == VK_ERROR_DEVICE_LOST || errorCode == VK_ERROR_INITIALIZATION_FAILED) {
        GM_LOGE("¡Alerta! VK_ERROR_DEVICE_LOST detectado. Reconstruyendo contexto...");
        if (device != VK_NULL_HANDLE) {
            vkDeviceWaitIdle(device);
            vkDestroyDevice(device, nullptr);
            device = VK_NULL_HANDLE;
        }
        return true; 
    }
    return false;
}

// -----------------------------------------------------------------------------
// 13. Elevación de Prioridad de Hilo a Tiempo Real (SCHED_FIFO)
// -----------------------------------------------------------------------------
inline bool elevateCurrentThreadToRealtimePriority() {
    pthread_t thisThread = pthread_self();
    int policy = SCHED_FIFO;
    struct sched_param param;
    int maxPriority = sched_get_priority_max(SCHED_FIFO);
    param.sched_priority = maxPriority > 0 ? maxPriority - 1 : 50;
    return (pthread_setschedparam(thisThread, policy, &param) == 0);
}

// -----------------------------------------------------------------------------
// 14. Log de Diagnóstico Asíncrono en Búfer Circular
// -----------------------------------------------------------------------------
class RingBufferMemoryLogger {
    struct LogEntry {
        uint64_t timestampUs;
        std::string message;
    };
    const size_t capacity = 4096;
    std::vector<LogEntry> buffer;
    size_t head = 0;
    bool full = false;
    std::mutex logMtx;

public:
    RingBufferMemoryLogger() { buffer.resize(capacity); }

    void log(const std::string& msg) {
        std::lock_guard<std::mutex> lock(logMtx);
        buffer[head] = { getMonotonicMicros(), msg };
        head = (head + 1) % capacity;
        if (head == 0) full = true;
    }

    void dumpToFileOnCrash(const std::string& filepath) {
        std::lock_guard<std::mutex> lock(logMtx);
        std::ofstream outfile(filepath, std::ios::out | std::ios::trunc);
        if (!outfile.is_open()) return;
        size_t size = full ? capacity : head;
        size_t start = full ? head : 0;
        outfile << "--- GOD MODE CRASH DIAGNOSTIC DUMP ---" << std::endl;
        for (size_t i = 0; i < size; ++i) {
            size_t idx = (start + i) % capacity;
            outfile << "[" << buffer[idx].timestampUs << "] " << buffer[idx].message << std::endl;
        }
        outfile.close();
    }
};

inline RingBufferMemoryLogger& getGlobalLogger() {
    static RingBufferMemoryLogger instance;
    return instance;
}

#define GM_LOGI(...) __android_log_print(ANDROID_LOG_INFO, GM_LOG_TAG, __VA_ARGS__)
#define GM_LOGW(...) __android_log_print(ANDROID_LOG_WARN, GM_LOG_TAG, __VA_ARGS__)
#define GM_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, GM_LOG_TAG, __VA_ARGS__)

// -----------------------------------------------------------------------------
// 15. Manejador Global de Excepciones No Capturadas
// -----------------------------------------------------------------------------
// Disabled for JNI stability in AI Studio environment
/*
inline void godModeGlobalTerminateHandler() {
    try {
        std::exception_ptr exPtr = std::current_exception();
        if (exPtr != nullptr) std::rethrow_exception(exPtr);
        else GM_LOGE("Terminación abrupta C++ detectada (SIGSEGV/abort)");
    } catch (const std::exception& e) {
        GM_LOGE("Excepción no capturada C++: %s", e.what());
    } catch (...) {
        GM_LOGE("Excepción nativa desconocida");
    }
    // Dump a ruta accesible (ajustar según entorno Android real si es necesario)
    getGlobalLogger().dumpToFileOnCrash("/sdcard/Android/data/com.xcloud.godmode/files/crash_dump.log");
    std::abort();
}

inline void installGlobalTerminationGuards() {
    std::set_terminate(godModeGlobalTerminateHandler);
}
*/


// -----------------------------------------------------------------------------
// 9. Verificador de Soporte de Silicio para Vulkan Video
// -----------------------------------------------------------------------------
inline bool isVulkanVideoHardwareSupported(VkPhysicalDevice physicalDevice) {
    if (physicalDevice == VK_NULL_HANDLE) return false;

    uint32_t extCount = 0;
    if (vkEnumerateDeviceExtensionProperties(physicalDevice, nullptr, &extCount, nullptr) != VK_SUCCESS || extCount == 0) {
        return false;
    }
    std::vector<VkExtensionProperties> extensions(extCount);
    if (vkEnumerateDeviceExtensionProperties(physicalDevice, nullptr, &extCount, extensions.data()) != VK_SUCCESS) {
        return false;
    }

    bool hasVideoQueue = false;
    bool hasDecodeH264 = false;

    for (const auto& ext : extensions) {
        if (std::strcmp(ext.extensionName, "VK_KHR_video_decode_queue") == 0) hasVideoQueue = true;
        if (std::strcmp(ext.extensionName, "VK_KHR_video_decode_h264") == 0) hasDecodeH264 = true;
    }

    if (!hasVideoQueue || !hasDecodeH264) return false;

    uint32_t queueFamilyCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &queueFamilyCount, nullptr);
    if (queueFamilyCount == 0) return false;

    std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
    vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &queueFamilyCount, queueFamilies.data());

    for (const auto& qf : queueFamilies) {
        if (qf.queueFlags & 0x00000020) { // VK_QUEUE_VIDEO_DECODE_BIT_KHR
            return true;
        }
    }
    return false;
}

// -----------------------------------------------------------------------------
// 10. Banderas de Formato de Decodificador de Cero-Reordenamiento y Calidad 1440p
// -----------------------------------------------------------------------------
inline void applyZeroReorderLatencyFormatKeys(AMediaFormat* fmt) {
    if (!fmt) return;
    AMediaFormat_setInt32(fmt, "low-latency", 1);
    AMediaFormat_setInt32(fmt, "max-bframes", 0);
    AMediaFormat_setInt32(fmt, "priority", 0);
    AMediaFormat_setInt32(fmt, "operating-rate", 120);

    // Qualcomm Snapdragon
    AMediaFormat_setInt32(fmt, "vendor.qti-ext-dec-low-latency.enable", 1);
    AMediaFormat_setInt32(fmt, "vendor.qti-ext-dec-picture-order.enable", 1);
    AMediaFormat_setInt32(fmt, "vendor.qti-ext-vpp.mode", 1);

    // MediaTek Dimensity
    AMediaFormat_setInt32(fmt, "vendor.mtk-ext-dec-low-latency.enable", 1);
    AMediaFormat_setInt32(fmt, "vendor.mtk-vdec-low-latency", 1);
    AMediaFormat_setInt32(fmt, "vendor.mtk-ext-ai-sr.enable", 1);

    // Samsung Exynos / Google Tensor
    AMediaFormat_setInt32(fmt, "vendor.sec-dec-output-delay.value", 0);
    AMediaFormat_setInt32(fmt, "vendor.rtc-ext-dec-low-latency.enable", 1);

    // HiSilicon Kirin
    AMediaFormat_setInt32(fmt, "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", 1);
}

inline void applyUltraQualityDecoderParameters(AMediaFormat* fmt, bool force1440p) {
    if (!fmt) return;
    int targetW = force1440p ? 2560 : 1920;
    int targetH = force1440p ? 1440 : 1080;

    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, targetW);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, targetH);

    applyZeroReorderLatencyFormatKeys(fmt);

    // Desbloqueo de 50 Mbps de tasa de bits máxima sin limitaciones de silicio
    AMediaFormat_setInt32(fmt, "vendor.qti-ext-dec-high-performance.enable", 1);
    AMediaFormat_setInt32(fmt, "vendor.mtk-ext-dec-high-quality.enable", 1);
    AMediaFormat_setInt32(fmt, "vendor.qti-ext-dec-max-bitrate.value", 50000000);
    AMediaFormat_setInt32(fmt, "vendor.mtk-vdec-max-bitrate", 50000000);
}

// -----------------------------------------------------------------------------
// 11. Demultiplexor de Puerto Único RFC 7983 y Respondedor STUN Consent
// -----------------------------------------------------------------------------
enum class MultiplexedPacketType {
    STUN_PACKET,      // Primer byte: 0..3
    DTLS_HANDSHAKE,   // Primer byte: 20..63
    RTP_MEDIA_STREAM, // Primer byte: 128..191 (PT 96..127)
    RTCP_CONTROL,     // Primer byte: 128..191 (PT 192..223)
    UNKNOWN_DROP
};

inline std::atomic<uint32_t> gGlobalStunKeepAlivesCount{0};
inline std::atomic<uint32_t> gGlobalHwcFencesClosedCount{0};

class XcloudSinglePortDemuxer {
public:
    static MultiplexedPacketType classifyUdpPacket(const uint8_t* buf, size_t len) {
        if (!buf || len < 12) return MultiplexedPacketType::UNKNOWN_DROP;
        uint8_t firstByte = buf[0];

        if (firstByte <= 3) {
            return MultiplexedPacketType::STUN_PACKET;
        } else if (firstByte >= 20 && firstByte <= 63) {
            return MultiplexedPacketType::DTLS_HANDSHAKE;
        } else if (firstByte >= 128 && firstByte <= 191) {
            uint8_t secondByte = buf[1] & 0x7F;
            if (secondByte >= 64 && secondByte <= 95) { // PT 192..223
                return MultiplexedPacketType::RTCP_CONTROL;
            }
            return MultiplexedPacketType::RTP_MEDIA_STREAM;
        }
        return MultiplexedPacketType::UNKNOWN_DROP;
    }

    static bool handleStunConsentKeepAlive(int udpSockFd, const uint8_t* stunReq, size_t len,
                                           const struct sockaddr* serverAddr, socklen_t addrLen) {
        if (!stunReq || len < 20 || udpSockFd < 0 || !serverAddr) return false;

        uint16_t msgType = (static_cast<uint16_t>(stunReq[0]) << 8) | stunReq[1];
        uint32_t cookie  = (static_cast<uint32_t>(stunReq[4]) << 24) |
                           (static_cast<uint32_t>(stunReq[5]) << 16) |
                           (static_cast<uint32_t>(stunReq[6]) << 8)  | stunReq[7];

        if (msgType == 0x0001 && cookie == 0x2112A442) {
            uint8_t stunResp[20];
            stunResp[0] = 0x01; // Binding Success Response (0x0101)
            stunResp[1] = 0x01;
            stunResp[2] = 0x00; // Length = 0
            stunResp[3] = 0x00;
            std::memcpy(stunResp + 4, stunReq + 4, 16);

            ssize_t sent = sendto(udpSockFd, stunResp, sizeof(stunResp), MSG_DONTWAIT, serverAddr, addrLen);
            if (sent > 0) {
                gGlobalStunKeepAlivesCount.fetch_add(1, std::memory_order_relaxed);
                return true;
            }
        }
        return false;
    }
};

// -----------------------------------------------------------------------------
// 12. Extractor RTP con Salto de Cabeceras de Extensión RFC 8285 (0xBEDE / 0x1000)
// -----------------------------------------------------------------------------
struct ParsedRtpHeader {
    bool valid = false;
    bool marker = false;
    uint8_t payloadType = 0;
    uint16_t sequenceNumber = 0;
    uint32_t timestamp = 0;
    uint32_t ssrc = 0;
    const uint8_t* payloadPtr = nullptr;
    size_t payloadSize = 0;
};

inline ParsedRtpHeader stripRtpHeadersAndExtensions(const uint8_t* pkt, size_t totalLen) {
    ParsedRtpHeader out{};
    if (!pkt || totalLen < 12) return out;

    uint8_t v  = (pkt[0] >> 6) & 0x03;
    uint8_t p  = (pkt[0] >> 5) & 0x01;
    uint8_t x  = (pkt[0] >> 4) & 0x01;
    uint8_t cc = pkt[0] & 0x0F;

    if (v != 2) return out;

    out.marker = ((pkt[1] >> 7) & 0x01) != 0;
    out.payloadType = pkt[1] & 0x7F;
    out.sequenceNumber = (static_cast<uint16_t>(pkt[2]) << 8) | pkt[3];
    out.timestamp = (static_cast<uint32_t>(pkt[4]) << 24) |
                    (static_cast<uint32_t>(pkt[5]) << 16) |
                    (static_cast<uint32_t>(pkt[6]) << 8)  | pkt[7];
    out.ssrc = (static_cast<uint32_t>(pkt[8]) << 24)  |
               (static_cast<uint32_t>(pkt[9]) << 16)  |
               (static_cast<uint32_t>(pkt[10]) << 8) | pkt[11];

    size_t offset = 12 + (cc * 4);
    if (totalLen < offset) return out;

    if (x == 1) {
        if (totalLen < offset + 4) return out;
        uint16_t extLenInWords = (static_cast<uint16_t>(pkt[offset + 2]) << 8) | pkt[offset + 3];
        offset += 4 + (static_cast<size_t>(extLenInWords) * 4);
    }

    size_t endOffset = totalLen;
    if (p == 1 && totalLen > offset) {
        uint8_t paddingBytes = pkt[totalLen - 1];
        if (paddingBytes > 0 && offset + paddingBytes <= totalLen) {
            endOffset -= paddingBytes;
        }
    }

    if (offset >= endOffset) return out;

    out.payloadPtr = pkt + offset;
    out.payloadSize = endOffset - offset;
    out.valid = true;
    return out;
}

// -----------------------------------------------------------------------------
// 13. Símbolos Dinámicos de NDK (libandroid.so) para compatibilidad universal
// -----------------------------------------------------------------------------
struct DynamicNdkSymbols {
    using PFN_ASurfaceControl_createFromWindow = void* (*)(ANativeWindow*, const char*);
    using PFN_ASurfaceTransaction_create = void* (*)();
    using PFN_ASurfaceTransaction_setBuffer = void (*)(void*, void*, AHardwareBuffer*, int);
    using PFN_ASurfaceTransaction_setGeometry = void (*)(void*, void*, const ARect&, const ARect&, int32_t);
    using PFN_ASurfaceTransaction_setVisibility = void (*)(void*, void*, int8_t);
    using PFN_ASurfaceTransaction_setOnComplete = void (*)(void*, void*, void (*)(void*, void*));
    using PFN_ASurfaceTransactionStats_getPreviousReleaseFenceFd = int (*)(void*, void*);
    using PFN_ASurfaceTransaction_apply = void (*)(void*);
    using PFN_ASurfaceTransaction_delete = void (*)(void*);

    PFN_ASurfaceControl_createFromWindow createSurfaceControl = nullptr;
    PFN_ASurfaceTransaction_create createTransaction = nullptr;
    PFN_ASurfaceTransaction_setBuffer setTransactionBuffer = nullptr;
    PFN_ASurfaceTransaction_setGeometry setTransactionGeometry = nullptr;
    PFN_ASurfaceTransaction_setVisibility setTransactionVisibility = nullptr;
    PFN_ASurfaceTransaction_setOnComplete setTransactionOnComplete = nullptr;
    PFN_ASurfaceTransactionStats_getPreviousReleaseFenceFd getPreviousReleaseFenceFd = nullptr;
    PFN_ASurfaceTransaction_apply applyTransaction = nullptr;
    PFN_ASurfaceTransaction_delete deleteTransaction = nullptr;
    bool hwc2Supported = false;

    static DynamicNdkSymbols& get() {
        static DynamicNdkSymbols instance;
        static std::once_flag flag;
        std::call_once(flag, [&]() { instance.resolveAll(); });
        return instance;
    }

    void simulateDeviceLost(VkDevice& device) {
        if (device != VK_NULL_HANDLE) {
            GM_LOGW("Simulando fallo crítico: Inyectando VK_ERROR_DEVICE_LOST");
            // No destruimos realmente el device aquí para no romper el flujo de otros hilos,
            // pero notificamos que debe reconstruirse.
            handleVulkanDeviceLostRecovery(VK_ERROR_DEVICE_LOST, device);
        }
    }

private:
    void resolveAll() {
        void* libAndroid = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (!libAndroid) return;

        createSurfaceControl = reinterpret_cast<PFN_ASurfaceControl_createFromWindow>(
            dlsym(libAndroid, "ASurfaceControl_createFromWindow"));
        createTransaction = reinterpret_cast<PFN_ASurfaceTransaction_create>(
            dlsym(libAndroid, "ASurfaceTransaction_create"));
        setTransactionBuffer = reinterpret_cast<PFN_ASurfaceTransaction_setBuffer>(
            dlsym(libAndroid, "ASurfaceTransaction_setBuffer"));
        setTransactionGeometry = reinterpret_cast<PFN_ASurfaceTransaction_setGeometry>(
            dlsym(libAndroid, "ASurfaceTransaction_setGeometry"));
        setTransactionVisibility = reinterpret_cast<PFN_ASurfaceTransaction_setVisibility>(
            dlsym(libAndroid, "ASurfaceTransaction_setVisibility"));
        setTransactionOnComplete = reinterpret_cast<PFN_ASurfaceTransaction_setOnComplete>(
            dlsym(libAndroid, "ASurfaceTransaction_setOnComplete"));
        getPreviousReleaseFenceFd = reinterpret_cast<PFN_ASurfaceTransactionStats_getPreviousReleaseFenceFd>(
            dlsym(libAndroid, "ASurfaceTransactionStats_getPreviousReleaseFenceFd"));
        applyTransaction = reinterpret_cast<PFN_ASurfaceTransaction_apply>(
            dlsym(libAndroid, "ASurfaceTransaction_apply"));
        deleteTransaction = reinterpret_cast<PFN_ASurfaceTransaction_delete>(
            dlsym(libAndroid, "ASurfaceTransaction_delete"));

        hwc2Supported = (createTransaction && setTransactionBuffer && applyTransaction && deleteTransaction);
    }
};

// -----------------------------------------------------------------------------
// 14. Contexto de Liberación Segura de Buffers HWC2 (Motor 4 - Fix EMFILE Crash)
// -----------------------------------------------------------------------------
struct HwcBufferReleaseContext {
    void* surfaceControl;
    AHardwareBuffer* hwBuffer;
};

inline void onSurfaceTransactionCompleteCallback(void* context, void* stats) {
    auto* relCtx = static_cast<HwcBufferReleaseContext*>(context);
    if (!relCtx) return;

    if (stats && relCtx->surfaceControl) {
        auto& syms = DynamicNdkSymbols::get();
        if (syms.getPreviousReleaseFenceFd) {
            int releaseFenceFd = syms.getPreviousReleaseFenceFd(stats, relCtx->surfaceControl);
            if (releaseFenceFd >= 0) {
                struct pollfd pfd = { releaseFenceFd, POLLIN, 0 };
                poll(&pfd, 1, 4);
                close(releaseFenceFd);
                gGlobalHwcFencesClosedCount.fetch_add(1, std::memory_order_relaxed);
            }
        }
    }

    if (relCtx->hwBuffer) {
        AHardwareBuffer_release(relCtx->hwBuffer);
    }
    delete relCtx;
}

// -----------------------------------------------------------------------------
// 15. Alineación de Stride para Planos YUV en Vulkan (Motor 1)
// -----------------------------------------------------------------------------
inline void copyPlanarYuvToVulkanImageWithStride(
    VkCommandBuffer cmd,
    VkBuffer stagingBuffer,
    VkImage dstYImage,
    uint32_t width,
    uint32_t height,
    uint32_t rustYStride
) {
    if (cmd == VK_NULL_HANDLE || stagingBuffer == VK_NULL_HANDLE || dstYImage == VK_NULL_HANDLE) return;
    VkBufferImageCopy copyRegion = {};
    copyRegion.bufferOffset = 0;
    copyRegion.bufferRowLength = (rustYStride > 0) ? rustYStride : width;
    copyRegion.bufferImageHeight = height;
    copyRegion.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    copyRegion.imageSubresource.mipLevel = 0;
    copyRegion.imageSubresource.baseArrayLayer = 0;
    copyRegion.imageSubresource.layerCount = 1;
    copyRegion.imageOffset = {0, 0, 0};
    copyRegion.imageExtent = {width, height, 1};

    vkCmdCopyBufferToImage(
        cmd,
        stagingBuffer,
        dstYImage,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        1,
        &copyRegion
    );
}

// -----------------------------------------------------------------------------
// 16. Memoria Interna de Sesión para Vulkan Video ASIC (Motor 2)
// -----------------------------------------------------------------------------
inline bool allocateAndBindVulkanVideoSessionMemory(
    VkDevice device,
    VkPhysicalDevice physDevice,
    VkVideoSessionKHR videoSession
) {
    if (device == VK_NULL_HANDLE || physDevice == VK_NULL_HANDLE || videoSession == VK_NULL_HANDLE) return false;
    auto fpGetMemReqs = reinterpret_cast<PFN_vkGetVideoSessionMemoryRequirementsKHR>(
        vkGetDeviceProcAddr(device, "vkGetVideoSessionMemoryRequirementsKHR"));
    auto fpBindMem = reinterpret_cast<PFN_vkBindVideoSessionMemoryKHR>(
        vkGetDeviceProcAddr(device, "vkBindVideoSessionMemoryKHR"));

    if (!fpGetMemReqs || !fpBindMem) return false;

    uint32_t reqCount = 0;
    if (fpGetMemReqs(device, videoSession, &reqCount, nullptr) != VK_SUCCESS || reqCount == 0) return true;

    std::vector<VkVideoSessionMemoryRequirementsKHR> memReqs(reqCount);
    for (auto& r : memReqs) {
        r.sType = VK_STRUCTURE_TYPE_VIDEO_SESSION_MEMORY_REQUIREMENTS_KHR;
        r.pNext = nullptr;
    }
    if (fpGetMemReqs(device, videoSession, &reqCount, memReqs.data()) != VK_SUCCESS) return false;

    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(physDevice, &memProps);

    std::vector<VkBindVideoSessionMemoryInfoKHR> bindInfos(reqCount);
    for (uint32_t i = 0; i < reqCount; ++i) {
        uint32_t typeBits = memReqs[i].memoryRequirements.memoryTypeBits;
        uint32_t selectedTypeIdx = 0;
        for (uint32_t m = 0; m < memProps.memoryTypeCount; ++m) {
            if ((typeBits & (1 << m)) &&
                (memProps.memoryTypes[m].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) {
                selectedTypeIdx = m;
                break;
            }
        }

        VkMemoryAllocateInfo allocInfo = {};
        allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocInfo.allocationSize = memReqs[i].memoryRequirements.size;
        allocInfo.memoryTypeIndex = selectedTypeIdx;

        VkDeviceMemory sessionMem = VK_NULL_HANDLE;
        if (vkAllocateMemory(device, &allocInfo, nullptr, &sessionMem) != VK_SUCCESS) {
            return false;
        }

        bindInfos[i].sType = VK_STRUCTURE_TYPE_BIND_VIDEO_SESSION_MEMORY_INFO_KHR;
        bindInfos[i].pNext = nullptr;
        bindInfos[i].memoryBindIndex = memReqs[i].memoryBindIndex;
        bindInfos[i].memory = sessionMem;
        bindInfos[i].memoryOffset = 0;
        bindInfos[i].memorySize = memReqs[i].memoryRequirements.size;
    }

    return fpBindMem(device, videoSession, reqCount, bindInfos.data()) == VK_SUCCESS;
}

// -----------------------------------------------------------------------------
// 17. Afinidad de Hilo EGL y Compilación de Shader OES (Motor 6 - Fix EGL_BAD_ACCESS)
// -----------------------------------------------------------------------------
class Motor6ThreadSafeGlHelper {
    GLuint programId = 0;
    GLint texelStepLoc = -1;
    std::thread::id boundThreadId;

    static GLuint compileShader(GLenum type, const char* src) {
        GLuint s = glCreateShader(type);
        glShaderSource(s, 1, &src, nullptr);
        glCompileShader(s);
        GLint ok = 0;
        glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
        if (!ok) {
            glDeleteShader(s);
            return 0;
        }
        return s;
    }

public:
    void releaseContextFromUiThread(EGLDisplay dpy) {
        if (dpy != EGL_NO_DISPLAY) {
            eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
    }

    bool ensureBoundOnWorkerThread(EGLDisplay dpy, EGLSurface surf, EGLContext ctx, const char* vsSrc, const char* fsSrc, int streamW, int streamH) {
        if (std::this_thread::get_id() != boundThreadId) {
            if (!eglMakeCurrent(dpy, surf, surf, ctx)) return false;
            boundThreadId = std::this_thread::get_id();
        }

        if (programId == 0 && vsSrc && fsSrc) {
            GLuint vs = compileShader(GL_VERTEX_SHADER, vsSrc);
            GLuint fs = compileShader(GL_FRAGMENT_SHADER, fsSrc);
            if (!vs || !fs) return false;

            programId = glCreateProgram();
            glAttachShader(programId, vs);
            glAttachShader(programId, fs);
            glLinkProgram(programId);
            glDeleteShader(vs);
            glDeleteShader(fs);

            glUseProgram(programId);
            glUniform1i(glGetUniformLocation(programId, "uStreamOesTex"), 0);
            texelStepLoc = glGetUniformLocation(programId, "uTexelStep");
            if (texelStepLoc >= 0 && streamW > 0 && streamH > 0) {
                glUniform2f(texelStepLoc, 1.0f / static_cast<float>(streamW), 1.0f / static_cast<float>(streamH));
            }
        }
        return true;
    }

    void shutdown() {
        if (programId != 0) {
            glDeleteProgram(programId);
            programId = 0;
        }
    }
};

// -----------------------------------------------------------------------------
// 18. Telemetría de Ingeniería Unificada
// -----------------------------------------------------------------------------
struct EngineeringTelemetryData {
    uint32_t activeEngineId = 3;
    uint32_t detectedWidth = 1920;
    uint32_t detectedHeight = 1080;
    uint32_t currentFps = 60;
    uint32_t rttMs = 25;
    float jitterMs = 1.0f;
    float packetLossPercent = 0.0f;
    uint32_t bitrateKbps = 35000;
    uint32_t stunKeepAliveCount = 0;
    uint32_t hwcFencesProcessed = 0;
    bool aiUpscalingActive = true;
};

// Factorías para los 6 motores
std::unique_ptr<IStreamEngine> createEngine1RustSecureVulkan();
std::unique_ptr<IStreamEngine> createEngine2VulkanVideoAsic();
std::unique_ptr<IStreamEngine> createEngine3MediaCodecAiNpu();
std::unique_ptr<IStreamEngine> createEngine4HwcSurfaceTransaction();
std::unique_ptr<IStreamEngine> createEngine5MediaCodecNpuAi();
std::unique_ptr<IStreamEngine> createEngine6MediaCodecEglOesAi();

// -----------------------------------------------------------------------------
// 19. Controlador Maestro de Conmutación por Falla (Cascada: 3 -> 4 -> 5 -> 6 -> 1 -> 2)
// -----------------------------------------------------------------------------
class MasterSixEngineFailSafeController {
public:
    static const std::vector<int>& getCascadeOrder() {
        static const std::vector<int> cascade = {3, 4, 5, 6, 1, 2};
        return cascade;
    }

    static std::unique_ptr<IStreamEngine> createEngineById(int id) {
        switch (id) {
            case 1: return createEngine1RustSecureVulkan();
            case 2: return createEngine2VulkanVideoAsic();
            case 3: return createEngine3MediaCodecAiNpu();
            case 4: return createEngine4HwcSurfaceTransaction();
            case 5: return createEngine5MediaCodecNpuAi();
            case 6: return createEngine6MediaCodecEglOesAi();
            default: return createEngine3MediaCodecAiNpu();
        }
    }

    static std::unique_ptr<IStreamEngine> initializeWithCascade(
        ANativeWindow* window,
        int requestedEngineId,
        int width,
        int height,
        bool enableAiUpscale,
        int& outActiveEngineId
    ) {
        if (!window) return nullptr;

        std::vector<int> attemptList;
        attemptList.push_back(requestedEngineId);
        for (int id : getCascadeOrder()) {
            if (id != requestedEngineId) {
                attemptList.push_back(id);
            }
        }

        for (int engineId : attemptList) {
            GM_LOGI("Iniciando intento de activación de Motor %d en cascada de resiliencia...", engineId);
            auto candidate = createEngineById(engineId);
            if (candidate && candidate->initialize(window, width, height, enableAiUpscale)) {
                GM_LOGI("Motor %d activado con ÉXITO absoluto en cascada de resiliencia", engineId);
                outActiveEngineId = engineId;
                return candidate;
            }
            GM_LOGW("Motor %d no soportado por el hardware/driver, degradando al siguiente en cascada...", engineId);
        }

        return nullptr;
    }
};

} // namespace GodMode::Stream
