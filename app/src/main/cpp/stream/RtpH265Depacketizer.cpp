#include "RtpH265Depacketizer.h"
#include <cstring>
#include <android/log.h>

#define LOG_TAG "RtpH265Depacketizer"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace GodMode::Stream {

RtpH265Depacketizer::RtpH265Depacketizer() {
    currentAccessUnit.reserve(512 * 1024);
}

void RtpH265Depacketizer::processRtpPayload(const uint8_t* rtpPayload, size_t payloadLen, bool markerBit, AMediaCodec* codec) {
    if (!rtpPayload || payloadLen < 2 || !codec) return;

    uint8_t nalHeader1 = rtpPayload[0];
    uint8_t nalType = (nalHeader1 >> 1) & 0x3F;

    if (nalType <= 40) { // Single NAL Unit
        if (nalType == 32) cachedVps.assign(rtpPayload, rtpPayload + payloadLen);
        else if (nalType == 33) cachedSps.assign(rtpPayload, rtpPayload + payloadLen);
        else if (nalType == 34) cachedPps.assign(rtpPayload, rtpPayload + payloadLen);
        
        appendAnnexBNal(rtpPayload, payloadLen);
    } else if (nalType == 48) { // AP (Aggregation Packet)
        size_t offset = 2;
        while (offset + 2 <= payloadLen) {
            uint16_t naluSize = (static_cast<uint16_t>(rtpPayload[offset]) << 8) | rtpPayload[offset + 1];
            offset += 2;
            if (offset + naluSize > payloadLen) break;

            uint8_t subNalType = (rtpPayload[offset] >> 1) & 0x3F;
            if (subNalType == 32) cachedVps.assign(rtpPayload + offset, rtpPayload + offset + naluSize);
            else if (subNalType == 33) cachedSps.assign(rtpPayload + offset, rtpPayload + offset + naluSize);
            else if (subNalType == 34) cachedPps.assign(rtpPayload + offset, rtpPayload + offset + naluSize);

            appendAnnexBNal(rtpPayload + offset, naluSize);
            offset += naluSize;
        }
    } else if (nalType == 49) { // FU (Fragmentation Unit)
        if (payloadLen < 3) return;
        uint8_t fuHeader = rtpPayload[2];
        bool isStart = (fuHeader & 0x80) != 0;
        uint8_t actualNalType = fuHeader & 0x3F;

        if (isStart) {
            // Reconstruct H.265 NAL header
            // Bit 0: Forbidden (0)
            // Bits 1-6: NAL Type
            // Bits 7-12: Layer ID (usually 0)
            // Bits 13-15: TID (usually 1)
            uint8_t reconstructedHeader1 = (actualNalType << 1) & 0x7E;
            uint8_t reconstructedHeader2 = rtpPayload[1]; // Keep LayerId and TID from RTP payload

            // For IDR frames, ensure we have VPS/SPS/PPS
            if (actualNalType >= 16 && actualNalType <= 21) { // IRAP types
                if (!cachedVps.empty() && !cachedSps.empty() && !cachedPps.empty()) {
                    appendAnnexBNal(cachedVps.data(), cachedVps.size());
                    appendAnnexBNal(cachedSps.data(), cachedSps.size());
                    appendAnnexBNal(cachedPps.data(), cachedPps.size());
                }
            }

            currentAccessUnit.insert(currentAccessUnit.end(), ANNEXB_START_CODE, ANNEXB_START_CODE + 4);
            currentAccessUnit.push_back(reconstructedHeader1);
            currentAccessUnit.push_back(reconstructedHeader2);
        }
        currentAccessUnit.insert(currentAccessUnit.end(), rtpPayload + 3, rtpPayload + payloadLen);
    }

    if (!hasConfiguredCsd && !cachedVps.empty() && !cachedSps.empty() && !cachedPps.empty()) {
        submitCodecConfigToMediaCodec(codec);
    }

    if (markerBit && !currentAccessUnit.empty()) {
        submitAccessUnitToMediaCodec(codec, currentAccessUnit.data(), currentAccessUnit.size());
        currentAccessUnit.clear();
    }
}

void RtpH265Depacketizer::appendAnnexBNal(const uint8_t* data, size_t len) {
    currentAccessUnit.insert(currentAccessUnit.end(), ANNEXB_START_CODE, ANNEXB_START_CODE + 4);
    currentAccessUnit.insert(currentAccessUnit.end(), data, data + len);
}

void RtpH265Depacketizer::submitCodecConfigToMediaCodec(AMediaCodec* codec) {
    ssize_t bufIdx = AMediaCodec_dequeueInputBuffer(codec, 5000);
    if (bufIdx >= 0) {
        size_t bufSize = 0;
        uint8_t* dst = AMediaCodec_getInputBuffer(codec, bufIdx, &bufSize);
        std::vector<uint8_t> csd;
        
        csd.insert(csd.end(), ANNEXB_START_CODE, ANNEXB_START_CODE + 4);
        csd.insert(csd.end(), cachedVps.begin(), cachedVps.end());
        csd.insert(csd.end(), ANNEXB_START_CODE, ANNEXB_START_CODE + 4);
        csd.insert(csd.end(), cachedSps.begin(), cachedSps.end());
        csd.insert(csd.end(), ANNEXB_START_CODE, ANNEXB_START_CODE + 4);
        csd.insert(csd.end(), cachedPps.begin(), cachedPps.end());

        if (dst && csd.size() <= bufSize) {
            std::memcpy(dst, csd.data(), csd.size());
            AMediaCodec_queueInputBuffer(codec, bufIdx, 0, csd.size(), 0, AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG);
            hasConfiguredCsd = true;
            LOGD("HEVC Codec Config (VPS/SPS/PPS) submitted");
        }
    }
}

void RtpH265Depacketizer::submitAccessUnitToMediaCodec(AMediaCodec* codec, const uint8_t* data, size_t size) {
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
