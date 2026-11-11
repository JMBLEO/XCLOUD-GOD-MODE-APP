#include "RtpH264Depacketizer.h"
#include <cstring>
#include <vector>

namespace GodMode::Stream {

RtpH264Depacketizer::RtpH264Depacketizer() {
    currentAccessUnit.reserve(256 * 1024);
}

void RtpH264Depacketizer::processRtpPayload(const uint8_t* rtpPayload, size_t payloadLen, bool markerBit, IStreamEngine* engine) {
    if (!rtpPayload || payloadLen < 1 || !engine) return;

    uint8_t nalHeader = rtpPayload[0];
    uint8_t nalType = nalHeader & 0x1F;

    if (nalType >= 1 && nalType <= 23) {
        if (nalType == 7) cachedSps.assign(rtpPayload, rtpPayload + payloadLen);
        if (nalType == 8) cachedPps.assign(rtpPayload, rtpPayload + payloadLen);
        appendAnnexBNal(rtpPayload, payloadLen);
    } else if (nalType == 24) {
        size_t offset = 1;
        while (offset + 2 <= payloadLen) {
            uint16_t naluSize = (static_cast<uint16_t>(rtpPayload[offset]) << 8) | rtpPayload[offset + 1];
            offset += 2;
            if (offset + naluSize > payloadLen) break;

            uint8_t subNalType = rtpPayload[offset] & 0x1F;
            if (subNalType == 7) cachedSps.assign(rtpPayload + offset, rtpPayload + offset + naluSize);
            else if (subNalType == 8) cachedPps.assign(rtpPayload + offset, rtpPayload + offset + naluSize);

            appendAnnexBNal(rtpPayload + offset, naluSize);
            offset += naluSize;
        }
        if (!cachedSps.empty() && !cachedPps.empty() && !hasConfiguredSpsPps) {
            submitCodecConfigToEngine(engine);
        }
    } else if (nalType == 28) {
        if (payloadLen < 2) return;
        uint8_t fuIndicator = rtpPayload[0];
        uint8_t fuHeader = rtpPayload[1];
        bool isStart = (fuHeader & 0x80) != 0;
        uint8_t origNalType = fuHeader & 0x1F;
        uint8_t reconstructedNalHeader = (fuIndicator & 0xE0) | origNalType;

        if (isStart) {
            if (origNalType == 5 && !cachedSps.empty() && !cachedPps.empty()) {
                appendAnnexBNal(cachedSps.data(), cachedSps.size());
                appendAnnexBNal(cachedPps.data(), cachedPps.size());
            }
            currentAccessUnit.insert(currentAccessUnit.end(), ANNEXB_START_CODE, ANNEXB_START_CODE + 4);
            currentAccessUnit.push_back(reconstructedNalHeader);
        }
        currentAccessUnit.insert(currentAccessUnit.end(), rtpPayload + 2, rtpPayload + payloadLen);
    }

    if (markerBit && !currentAccessUnit.empty()) {
        submitAccessUnitToEngine(engine, currentAccessUnit.data(), currentAccessUnit.size());
        currentAccessUnit.clear();
    }
}

void RtpH264Depacketizer::appendAnnexBNal(const uint8_t* data, size_t len) {
    currentAccessUnit.insert(currentAccessUnit.end(), ANNEXB_START_CODE, ANNEXB_START_CODE + 4);
    currentAccessUnit.insert(currentAccessUnit.end(), data, data + len);
}

void RtpH264Depacketizer::submitCodecConfigToEngine(IStreamEngine* engine) {
    if (!engine || cachedSps.empty() || cachedPps.empty()) return;
    engine->injectCodecConfig(cachedSps.data(), cachedSps.size(), cachedPps.data(), cachedPps.size());
    hasConfiguredSpsPps = true;
}

void RtpH264Depacketizer::submitAccessUnitToEngine(IStreamEngine* engine, const uint8_t* data, size_t size) {
    if (!engine || !data || size == 0) return;
    
    // In a real scenario, we should get the PTS from the RTP timestamp.
    // For now, we use current monotonic time if not provided.
    uint64_t ptsUs = getMonotonicMicros();
    
    bool isIdr = false;
    if (size > 4 && (data[4] & 0x1F) == 5) isIdr = true;

    engine->pushAnnexBNalUnit(data, size, ptsUs, isIdr);
}

}
