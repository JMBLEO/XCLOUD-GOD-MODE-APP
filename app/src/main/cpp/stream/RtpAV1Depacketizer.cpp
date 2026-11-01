#include "RtpAV1Depacketizer.h"
#include <cstring>
#include <android/log.h>

#define LOG_TAG "RtpAV1Depacketizer"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace GodMode::Stream {

RtpAV1Depacketizer::RtpAV1Depacketizer() {
    currentAccessUnit.reserve(512 * 1024);
}

void RtpAV1Depacketizer::processRtpPayload(const uint8_t* rtpPayload, size_t payloadLen, bool markerBit, AMediaCodec* codec) {
    if (!rtpPayload || payloadLen < 1 || !codec) return;

    // AV1 RTP Payload Header (Simplified)
    // Z (1): Zero-bit (start of OBU)
    // Y (1): Set if the payload starts with a full OBU
    // W (2): Number of OBUs in the payload (if Y=0) or OBU fragmentation info
    
    uint8_t header = rtpPayload[0];
    bool startOfObu = (header & 0x80) == 0; // Z=0

    // For simplicity, we append the payload (skipping the 1-byte RTP AV1 header)
    // The hardware AV1 decoder usually expects OBU stream directly.
    currentAccessUnit.insert(currentAccessUnit.end(), rtpPayload + 1, rtpPayload + payloadLen);

    if (markerBit && !currentAccessUnit.empty()) {
        submitAccessUnitToMediaCodec(codec, currentAccessUnit.data(), currentAccessUnit.size());
        currentAccessUnit.clear();
    }
}

void RtpAV1Depacketizer::submitAccessUnitToMediaCodec(AMediaCodec* codec, const uint8_t* data, size_t size) {
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
