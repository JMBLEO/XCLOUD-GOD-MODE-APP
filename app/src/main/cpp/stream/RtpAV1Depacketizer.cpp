#include "RtpAV1Depacketizer.h"
#include "GodModeVideoIngress.h"
#include <cstring>
#include <android/log.h>

#define LOG_TAG "RtpAV1Depacketizer"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace GodMode::Stream {

RtpAV1Depacketizer::RtpAV1Depacketizer() {
    currentAccessUnit.reserve(512 * 1024);
}

void RtpAV1Depacketizer::processRtpPayload(const uint8_t* rtpPayload, size_t payloadLen, bool markerBit, IStreamEngine* engine) {
    if (!rtpPayload || payloadLen < 1 || !engine) return;

    // Inyectar OBU_TEMPORAL_DELIMITER (0x12, 0x00) al inicio del Access Unit
    if (currentAccessUnit.empty()) {
        static const uint8_t OBU_TD[2] = {0x12, 0x00};
        currentAccessUnit.insert(currentAccessUnit.end(), OBU_TD, OBU_TD + 2);
    }

    uint8_t header = rtpPayload[0];
    uint8_t z = (header >> 7) & 0x01;
    uint8_t w = (header >> 4) & 0x03;

    size_t offset = 1;
    if (z == 1) {
        if (w == 0) {
            currentAccessUnit.insert(currentAccessUnit.end(), rtpPayload + offset, rtpPayload + payloadLen);
            offset = payloadLen;
        } else {
            size_t bytesRead = 0;
            uint32_t elemLen = Leb128Codec::readLeb128(rtpPayload + offset, payloadLen - offset, &bytesRead);
            offset += bytesRead;
            if (offset + elemLen <= payloadLen) {
                currentAccessUnit.insert(currentAccessUnit.end(), rtpPayload + offset, rtpPayload + offset + elemLen);
                offset += elemLen;
            }
        }
    }

    while (offset < payloadLen) {
        size_t bytesRead = 0;
        uint32_t obuSize = 0;
        if (offset + 1 < payloadLen) {
            obuSize = Leb128Codec::readLeb128(rtpPayload + offset, payloadLen - offset, &bytesRead);
            offset += bytesRead;
        } else {
            obuSize = static_cast<uint32_t>(payloadLen - offset);
        }

        if (offset + obuSize > payloadLen) {
            obuSize = static_cast<uint32_t>(payloadLen - offset);
        }

        if (obuSize > 0) {
            currentAccessUnit.insert(currentAccessUnit.end(), rtpPayload + offset, rtpPayload + offset + obuSize);
            offset += obuSize;
        } else {
            break;
        }
    }

    if (markerBit && !currentAccessUnit.empty()) {
        submitAccessUnitToEngine(engine, currentAccessUnit.data(), currentAccessUnit.size());
        currentAccessUnit.clear();
    }
}

void RtpAV1Depacketizer::submitAccessUnitToEngine(IStreamEngine* engine, const uint8_t* data, size_t size) {
    if (!engine || !data || size == 0) return;
    
    uint64_t ptsUs = getMonotonicMicros();
    
    // Quick AV1 keyframe check (OBU_SEQUENCE_HEADER = 1)
    bool isKeyframe = false;
    for (size_t i = 0; i < size; ++i) {
        uint8_t obuType = (data[i] >> 3) & 0x0F;
        if (obuType == 1) { isKeyframe = true; break; }
    }

    engine->pushAnnexBNalUnit(data, size, ptsUs, isKeyframe);
}

}
