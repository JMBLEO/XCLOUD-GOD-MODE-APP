#include "RtpH264Depacketizer.h"
#include <cstring>
#include <vector>

namespace GodMode::Stream {

RtpH264Depacketizer::RtpH264Depacketizer() {
    currentAccessUnit.reserve(256 * 1024);
}

void RtpH264Depacketizer::processRtpPayload(const uint8_t* rtpPayload, size_t payloadLen, bool markerBit, AMediaCodec* codec) {
    if (!rtpPayload || payloadLen < 1 || !codec) return;

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
            submitCodecConfigToMediaCodec(codec);
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
        submitAccessUnitToMediaCodec(codec, currentAccessUnit.data(), currentAccessUnit.size());
        currentAccessUnit.clear();
    }
}

void RtpH264Depacketizer::appendAnnexBNal(const uint8_t* data, size_t len) {
    currentAccessUnit.insert(currentAccessUnit.end(), ANNEXB_START_CODE, ANNEXB_START_CODE + 4);
    currentAccessUnit.insert(currentAccessUnit.end(), data, data + len);
}

void RtpH264Depacketizer::submitCodecConfigToMediaCodec(AMediaCodec* codec) {
    ssize_t bufIdx = AMediaCodec_dequeueInputBuffer(codec, 5000);
    if (bufIdx >= 0) {
        size_t bufSize = 0;
        uint8_t* dst = AMediaCodec_getInputBuffer(codec, bufIdx, &bufSize);
        std::vector<uint8_t> csd;
        csd.insert(csd.end(), ANNEXB_START_CODE, ANNEXB_START_CODE + 4);
        csd.insert(csd.end(), cachedSps.begin(), cachedSps.end());
        csd.insert(csd.end(), ANNEXB_START_CODE, ANNEXB_START_CODE + 4);
        csd.insert(csd.end(), cachedPps.begin(), cachedPps.end());

        if (dst && csd.size() <= bufSize) {
            std::memcpy(dst, csd.data(), csd.size());
            AMediaCodec_queueInputBuffer(codec, bufIdx, 0, csd.size(), 0, AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG);
            hasConfiguredSpsPps = true;
        }
    }
}

void RtpH264Depacketizer::submitAccessUnitToMediaCodec(AMediaCodec* codec, const uint8_t* data, size_t size) {
    ssize_t bufIdx = AMediaCodec_dequeueInputBuffer(codec, 2000);
    if (bufIdx >= 0) {
        size_t bufSize = 0;
        uint8_t* dst = AMediaCodec_getInputBuffer(codec, bufIdx, &bufSize);
        if (dst && size <= bufSize) {
            std::memcpy(dst, data, size);
            AMediaCodec_queueInputBuffer(codec, bufIdx, 0, size, 0, 0);
        }
    }

    AMediaCodecBufferInfo info;
    ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(codec, &info, 0);
    while (outIdx >= 0) {
        AMediaCodec_releaseOutputBuffer(codec, outIdx, info.size != 0);
        outIdx = AMediaCodec_dequeueOutputBuffer(codec, &info, 0);
    }
}

}
