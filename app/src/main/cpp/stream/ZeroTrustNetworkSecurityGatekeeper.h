#pragma once

#include <cstdint>
#include <cstddef>
#include <vector>
#include <cstring>
#include <android/log.h>

namespace GodMode::Security {

class ZeroTrustNetworkSecurityGatekeeper {
public:
    enum class SecurityVerdict {
        ALLOW_PACKET,
        DROP_MALFORMED_NAL,
        DROP_REPLAY_ATTACK,
        KILL_PROCESS_SECURITY_BREACH
    };

private:
    uint32_t lastValidSequenceNumber = 0;
    bool hasSeenFirstPacket = false;
    const char* TAG = "GodModeZeroTrust";

    // Blindaje Anti-Replay: Ventana deslizante de 1024 paquetes (RFC 3711)
    static constexpr uint16_t REPLAY_WINDOW_SIZE = 1024;
    uint8_t replayWindow[REPLAY_WINDOW_SIZE / 8]{}; // Bitmask de paquetes recibidos
    uint32_t highestSequenceNumber = 0;

    // Hid Input Sequence Tracking
    uint64_t lastHidSequence = 0;

public:
    SecurityVerdict inspectAndSanitizeIncomingFrame(const uint8_t* packetPtr, size_t packetLen, uint16_t rtpSeqNum) {
        // 1. Prevención estricta de desbordamiento de búfer por paquetes gigantes o vacíos
        if (packetLen < 3 || packetLen > 4 * 1024 * 1024) {
            __android_log_print(ANDROID_LOG_WARN, TAG, "Packet size violation: %zu", packetLen);
            return SecurityVerdict::DROP_MALFORMED_NAL;
        }

        // 2. Blindaje SRTP Anti-Replay con Ventana Deslizante (RFC 3711)
        if (!hasSeenFirstPacket) {
            highestSequenceNumber = rtpSeqNum;
            hasSeenFirstPacket = true;
            std::memset(replayWindow, 0, sizeof(replayWindow));
        } else {
            int32_t diff = static_cast<int32_t>(rtpSeqNum) - static_cast<int32_t>(highestSequenceNumber);
            
            if (diff > 0) {
                // Nuevo paquete más reciente: Deslizar ventana (limpieza bitmask)
                if (diff >= REPLAY_WINDOW_SIZE) {
                    std::memset(replayWindow, 0, sizeof(replayWindow));
                } else {
                    for (int i = 0; i < diff; ++i) {
                        uint32_t targetSeq = highestSequenceNumber + i + 1;
                        uint32_t bitIdx = targetSeq % REPLAY_WINDOW_SIZE;
                        replayWindow[bitIdx / 8] &= ~(1 << (bitIdx % 8));
                    }
                }
                highestSequenceNumber = rtpSeqNum;
            } else if (diff <= -REPLAY_WINDOW_SIZE) {
                // Demasiado viejo, fuera de la ventana
                return SecurityVerdict::DROP_REPLAY_ATTACK;
            } else {
                // Dentro de la ventana: verificar bitmask circular
                uint32_t bitIdx = static_cast<uint32_t>(rtpSeqNum) % REPLAY_WINDOW_SIZE;
                if (replayWindow[bitIdx / 8] & (1 << (bitIdx % 8))) {
                    return SecurityVerdict::DROP_REPLAY_ATTACK; // Ya recibido
                }
            }
            // Marcar como recibido
            uint32_t bitIdx = static_cast<uint32_t>(rtpSeqNum) % REPLAY_WINDOW_SIZE;
            replayWindow[bitIdx / 8] |= (1 << (bitIdx % 8));
        }

        // 3. Sanitización de Bitstream NAL (Anti-Injection / Anti-Tampering)
        if (packetLen > 4 && packetPtr[0] == 0x00 && packetPtr[1] == 0x00 && packetPtr[2] == 0x00 && packetPtr[3] == 0x01) {
            uint8_t nalType = packetPtr[4] & 0x1F; // H.264
            if (nalType > 12 && nalType != 28 && nalType != 24) { 
                return SecurityVerdict::DROP_MALFORMED_NAL;
            }
        } else if (packetLen > 2) {
            uint8_t hevcNalType = (packetPtr[0] >> 1) & 0x3F;
            bool isAllowed = (hevcNalType <= 21) || (hevcNalType >= 32 && hevcNalType <= 34) || (hevcNalType == 39) || (hevcNalType == 48) || (hevcNalType == 49);
            if (!isAllowed) {
                return SecurityVerdict::DROP_MALFORMED_NAL;
            }
        }

        return SecurityVerdict::ALLOW_PACKET;
    }

    /// Filtro de Anti-Replay para Comandos de Control HID (SCTP Input)
    bool verifyHidInputSequence(uint64_t seqNum) {
        if (seqNum <= lastHidSequence && lastHidSequence != 0) {
            return false; // Repetición o desorden en el canal de control
        }
        lastHidSequence = seqNum;
        return true;
    }

    void reset() {
        hasSeenFirstPacket = false;
        highestSequenceNumber = 0;
        lastHidSequence = 0;
        std::memset(replayWindow, 0, sizeof(replayWindow));
    }
};

} // namespace GodMode::Security
