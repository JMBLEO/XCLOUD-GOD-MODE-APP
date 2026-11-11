#pragma once

#include <cstdint>
#include <cstddef>
#include <vector>
#include <array>
#include <chrono>
#include <functional>
#include <cstring>
#include <arpa/inet.h>
#include <android/log.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#define GM_INGRESS_TAG "GodModeVideoIngress"
#ifndef GM_LOGD
#define GM_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, GM_INGRESS_TAG, __VA_ARGS__)
#endif
#ifndef GM_LOGI
#define GM_LOGI(...) __android_log_print(ANDROID_LOG_INFO, GM_INGRESS_TAG, __VA_ARGS__)
#endif
#ifndef GM_LOGW
#define GM_LOGW(...) __android_log_print(ANDROID_LOG_WARN, GM_INGRESS_TAG, __VA_ARGS__)
#endif
#ifndef GM_LOGE
#define GM_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, GM_INGRESS_TAG, __VA_ARGS__)
#endif

namespace GodMode::Stream {

// -----------------------------------------------------------------------------
// 1. Estructura de Paquete RTP Desencapsulado sin asignación en Heap
// -----------------------------------------------------------------------------
struct RtpVideoPacket {
    uint16_t seqNum = 0;
    uint32_t timestamp = 0;
    uint32_t ssrc = 0;
    bool     marker = false;
    uint8_t  payloadType = 0;
    const uint8_t* payload = nullptr;
    size_t   payloadLen = 0;
    bool     valid = false;
};

// -----------------------------------------------------------------------------
// 2. Parser de Ingesta Cruda RTP (Cabecera fija, CSRC, RFC 8285 y Padding)
// -----------------------------------------------------------------------------
class VideoIngressRtpParser {
public:
    static bool parseRtp(const uint8_t* buf, size_t len, RtpVideoPacket& out) {
        if (!buf || len < 12) {
            out.valid = false;
            return false;
        }

        uint8_t v  = (buf[0] >> 6) & 0x03;
        uint8_t p  = (buf[0] >> 5) & 0x01;
        uint8_t x  = (buf[0] >> 4) & 0x01;
        uint8_t cc = buf[0] & 0x0F;

        if (v != 2) {
            out.valid = false;
            return false;
        }

        out.marker = ((buf[1] >> 7) & 0x01) != 0;
        out.payloadType = buf[1] & 0x7F;
        out.seqNum = (static_cast<uint16_t>(buf[2]) << 8) | buf[3];
        out.timestamp = (static_cast<uint32_t>(buf[4]) << 24) |
                        (static_cast<uint32_t>(buf[5]) << 16) |
                        (static_cast<uint32_t>(buf[6]) << 8)  | buf[7];
        out.ssrc = (static_cast<uint32_t>(buf[8]) << 24)  |
                   (static_cast<uint32_t>(buf[9]) << 16)  |
                   (static_cast<uint32_t>(buf[10]) << 8) | buf[11];

        size_t offset = 12 + (static_cast<size_t>(cc) * 4);
        if (len < offset) {
            out.valid = false;
            return false;
        }

        // Extensión de cabecera (RFC 8285 One-Byte 0xBEDE / Two-Byte 0x1000 / RFC 3550)
        if (x == 1) {
            if (len < offset + 4) {
                out.valid = false;
                return false;
            }
            uint16_t extLenWords = (static_cast<uint16_t>(buf[offset + 2]) << 8) | buf[offset + 3];
            size_t extBytes = 4 + (static_cast<size_t>(extLenWords) * 4);
            offset += extBytes;
            if (len < offset) {
                out.valid = false;
                return false;
            }
        }

        // Deducción de padding
        size_t endOffset = len;
        if (p == 1) {
            uint8_t paddingBytes = buf[len - 1];
            if (paddingBytes == 0 || offset + paddingBytes > len) {
                out.valid = false;
                return false;
            }
            endOffset -= paddingBytes;
        }

        if (offset >= endOffset) {
            out.valid = false;
            return false;
        }

        out.payload = buf + offset;
        out.payloadLen = endOffset - offset;
        out.valid = true;
        return true;
    }
};

// -----------------------------------------------------------------------------
// 3. Estructuras de Bitstream SPS y Colorimetría VUI
// -----------------------------------------------------------------------------
struct H264SpsInfo {
    int width = 1920;
    int height = 1080;
    int profileIdc = 100;
    int levelIdc = 41;
    bool fullRange = false;        // 0: Limited (16..235), 1: Full (0..255)
    uint8_t matrixCoefficients = 1; // 1: BT.709, 6: BT.601, 9: BT.2020
    uint8_t colourPrimaries = 1;    // 1: BT.709
    uint8_t transferCharacteristics = 1;
    bool valid = false;
};

class H264SpsParser {
public:
    static bool parseSps(const uint8_t* spsNal, size_t len, H264SpsInfo& outInfo);

private:
    static void removeEmulationPrevention(const uint8_t* src, size_t len, std::vector<uint8_t>& rbsp);
};

// -----------------------------------------------------------------------------
// 4. Buffer de Reordenamiento Circular (512 slots) con Grace Inbox para NACKs
// -----------------------------------------------------------------------------
struct RtcpNackPacket {
    uint8_t  header = 0x81;     // V=2, P=0, FMT=1 (Generic NACK)
    uint8_t  packetType = 205;  // RTPFB (Transport Layer Feedback)
    uint16_t length = 0;        // htons(3) = 4 palabras de 32 bits menos 1
    uint32_t senderSsrc = 0;
    uint32_t mediaSsrc = 0;
    uint16_t pid = 0;           // Packet ID (primer paquete faltante)
    uint16_t blp = 0;           // Bitmask of Lost Packets (siguientes 16)
};

class RtpSequenceReorderBuffer {
public:
    static constexpr size_t RING_CAPACITY = 512;
    static constexpr int16_t MAX_REORDER_DRIFT = 256;

    struct Slot {
        uint8_t  data[2048];
        size_t   size = 0;
        uint16_t seqNum = 0;
        uint32_t timestamp = 0;
        bool     marker = false;
        bool     occupied = false;
        std::chrono::steady_clock::time_point arrivalTime;
    };

    using PacketConsumer = std::function<void(const uint8_t* payload, size_t len, uint32_t ts, bool marker, bool discontinuous)>;
    using NackSender = std::function<void(const uint8_t* nackData, size_t nackLen)>;

    RtpSequenceReorderBuffer();

    void reset();
    void pushPacket(const RtpVideoPacket& pkt, uint32_t clientSsrc, const PacketConsumer& consumer, const NackSender& nackSender);
    void flushPendingPackets(const PacketConsumer& consumer);

private:
    static inline int16_t seqDiff(uint16_t a, uint16_t b) {
        return static_cast<int16_t>(a - b);
    }

    void evaluateNackGraceInbox(uint32_t clientSsrc, const NackSender& nackSender);

    Slot ring[RING_CAPACITY];
    uint16_t expectedSeq = 0;
    uint16_t highestSeq = 0;
    bool initialized = false;

    // Grace inbox para NACKs (espera 3 a 5 ms antes de enviar NACK para evitar retransmisiones innecesarias)
    struct MissingHole {
        uint16_t seq = 0;
        std::chrono::steady_clock::time_point detectedAt;
        uint32_t packetsReceivedSinceGap = 0;
        bool nackSent = false;
    };
    std::vector<MissingHole> pendingHoles;
    uint32_t lastServerSsrc = 0;
    std::chrono::steady_clock::time_point lastNackSentTime;
};

// -----------------------------------------------------------------------------
// 5. Máquina de Estados de Integridad IDR y Recuperación Rápida
// -----------------------------------------------------------------------------
enum class IdrRecoveryState {
    WAITING_FIRST_SYNC = 0,   // Esperando primer SPS/PPS + IDR limpio
    HEALTHY = 1,              // Flujo continuo y referencias limpias
    BROKEN_REFERENCE = 2      // Referencia corrupta por pérdida de paquete en AU
};

class IdrRecoveryStateMachine {
public:
    IdrRecoveryStateMachine();

    IdrRecoveryState getState() const { return currentState; }

    // Procesa una Access Unit completa. Devuelve true si la AU debe enviarse al decodificador
    bool processAccessUnit(bool isIdr, bool hasDiscontinuity, bool hasSps, bool hasPps,
                           uint32_t clientSsrc, uint32_t serverVideoSsrc,
                           std::vector<uint8_t>& outPliPacket);

    void onDecoderError();
    void reset();

private:
    IdrRecoveryState currentState = IdrRecoveryState::WAITING_FIRST_SYNC;
    std::chrono::steady_clock::time_point lastPliTime;
    static constexpr uint32_t PLI_COOLDOWN_MS = 280; // Rate limiter de 280ms
};

// -----------------------------------------------------------------------------
// 6. Ensamblador Manual de Access Units (AU) en Annex-B
// -----------------------------------------------------------------------------
class CompleteH264AccessUnitAssembler {
public:
    using OnCompleteAuCallback = std::function<void(const uint8_t* auData, size_t auSize, uint64_t ptsUs, bool isIdr, const H264SpsInfo& spsInfo)>;
    using PliCallback = std::function<void(const uint8_t* pliData, size_t pliLen)>;

    CompleteH264AccessUnitAssembler();

    void reset();
    void feedRtpPayload(const uint8_t* payload, size_t len, uint32_t rtpTimestamp, bool marker, bool discontinuous,
                        uint32_t clientSsrc, uint32_t serverSsrc,
                        const OnCompleteAuCallback& onAu,
                        const PliCallback& onPli);

    const H264SpsInfo& getSpsInfo() const { return currentSpsInfo; }
    IdrRecoveryState getIdrState() const { return idrStateMachine.getState(); }

private:
    void appendStartCode();
    void appendData(const uint8_t* data, size_t len);
    void handleNalUnit(const uint8_t* nal, size_t len);

    std::vector<uint8_t> currentAuBuffer;
    std::vector<uint8_t> fuaAccumulator;
    std::vector<uint8_t> cachedSps;
    std::vector<uint8_t> cachedPps;
    H264SpsInfo currentSpsInfo;

    uint32_t activeTimestamp = 0;
    bool activeAuIsIdr = false;
    bool activeAuDiscontinuous = false;
    bool hasSeenSpsThisAu = false;
    bool hasSeenPpsThisAu = false;

    IdrRecoveryStateMachine idrStateMachine;
    static constexpr uint8_t ANNEXB_START_CODE[4] = {0x00, 0x00, 0x00, 0x01};
};

// -----------------------------------------------------------------------------
// 7. Ensamblador Manual de Access Units HEVC (RFC 7798, AP tipo 48, FU tipo 49)
// -----------------------------------------------------------------------------
struct HevcNalInfo {
    uint8_t nalType = 0;
    uint8_t layerId = 0;
    uint8_t tid = 0;
    bool isVps = false;   // 32
    bool isSps = false;   // 33
    bool isPps = false;   // 34
    bool isIrap = false;  // 16..21 (BLA, IDR, CRA)
};

class CompleteHevcAccessUnitAssembler {
public:
    using OnCompleteHevcAuCallback = std::function<void(const uint8_t* auData, size_t auSize, uint64_t ptsUs, bool isKeyframe)>;
    using PliCallback = std::function<void(const uint8_t* pliData, size_t pliLen)>;

    CompleteHevcAccessUnitAssembler();

    void reset();
    void feedRtpPayload(const uint8_t* payload, size_t len, uint32_t rtpTimestamp, bool marker, bool discontinuous,
                        uint32_t clientSsrc, uint32_t serverSsrc,
                        const OnCompleteHevcAuCallback& onAu,
                        const PliCallback& onPli);

    bool hasCachedConfig() const { return !cachedVps.empty() && !cachedSps.empty() && !cachedPps.empty(); }

private:
    void appendStartCode();
    void appendData(const uint8_t* data, size_t len);
    void handleNalu(const uint8_t* nalu, size_t len);

    std::vector<uint8_t> currentAuBuffer;
    std::vector<uint8_t> fuAccumulator;
    std::vector<uint8_t> cachedVps;
    std::vector<uint8_t> cachedSps;
    std::vector<uint8_t> cachedPps;

    uint32_t activeTimestamp = 0;
    bool activeAuIsKeyframe = false;
    bool activeAuDiscontinuous = false;
    bool hasAppendedVpsSpsPps = false;

    static constexpr uint8_t ANNEXB_START_CODE[4] = {0x00, 0x00, 0x00, 0x01};
};

// -----------------------------------------------------------------------------
// 8. Ensamblador Manual de Access Units AV1 (AOMedia RTP Payload & LEB128)
// -----------------------------------------------------------------------------
struct Av1ObuInfo {
    uint8_t obuType = 0;
    bool hasSizeField = false;
    bool hasExtension = false;
    bool isSequenceHeader = false;     // 1
    bool isTemporalDelimiter = false;  // 2
    bool isFrameHeader = false;        // 3
    bool isFrame = false;              // 6
    bool isKeyframe = false;
};

class Leb128Codec {
public:
    static inline uint32_t readLeb128(const uint8_t* buf, size_t len, size_t* bytesRead) {
        uint32_t value = 0;
        size_t i = 0;
        for (; i < len && i < 8; ++i) {
            uint8_t byte = buf[i];
            value |= static_cast<uint32_t>(byte & 0x7F) << (i * 7);
            if ((byte & 0x80) == 0) {
                i++;
                break;
            }
        }
        if (bytesRead) *bytesRead = i;
        return value;
    }

    static inline size_t writeLeb128(uint32_t value, uint8_t* buf) {
        size_t bytes = 0;
        do {
            uint8_t byte = value & 0x7F;
            value >>= 7;
            if (value != 0) {
                byte |= 0x80;
            }
            buf[bytes++] = byte;
        } while (value != 0);
        return bytes;
    }
};

class CompleteAv1AccessUnitAssembler {
public:
    using OnCompleteAv1AuCallback = std::function<void(const uint8_t* auData, size_t auSize, uint64_t ptsUs, bool isKeyframe)>;
    using PliCallback = std::function<void(const uint8_t* pliData, size_t pliLen)>;

    CompleteAv1AccessUnitAssembler();

    void reset();
    void feedRtpPayload(const uint8_t* payload, size_t len, uint32_t rtpTimestamp, bool marker, bool discontinuous,
                        uint32_t clientSsrc, uint32_t serverSsrc,
                        const OnCompleteAv1AuCallback& onAu,
                        const PliCallback& onPli);

    bool hasCachedSequenceHeader() const { return !cachedSeqHeader.empty(); }

private:
    void injectTemporalDelimiter();
    void appendData(const uint8_t* data, size_t len);

    std::vector<uint8_t> currentAuBuffer;
    std::vector<uint8_t> cachedSeqHeader;

    uint32_t activeTimestamp = 0;
    bool activeAuIsKeyframe = false;
    bool activeAuDiscontinuous = false;
    bool hasTemporalDelimiter = false;

    static constexpr uint8_t OBU_TEMPORAL_DELIMITER_BYTES[2] = {0x12, 0x00};
};

} // namespace GodMode::Stream

// -----------------------------------------------------------------------------
// 9. Funciones Nativas C de Ingesta Directa y Negociación SDP NextGen
// -----------------------------------------------------------------------------
extern "C" {
    bool x4_ingress_push_rtp_hevc(const uint8_t* payload, size_t len, uint32_t rtpTimestamp, bool marker,
                                  uint8_t* outAu, size_t* outAuLen, bool* outIsKeyframe);
    bool x4_ingress_push_rtp_av1(const uint8_t* payload, size_t len, uint32_t rtpTimestamp, bool marker,
                                 uint8_t* outAu, size_t* outAuLen, bool* outIsKeyframe);
    size_t x4_sdp_build_video_offer_nextgen(char* outBuf, size_t maxLen, uint32_t maxBitrateKbps);
}

