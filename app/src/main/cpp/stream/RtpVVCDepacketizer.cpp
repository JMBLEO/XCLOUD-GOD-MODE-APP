#include "RtpVVCDepacketizer.h"
#include <cstring>
#include <android/log.h>

#define LOG_TAG "RtpVVCDepacketizer"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace GodMode::Stream {

RtpVVCDepacketizer::RtpVVCDepacketizer() {
    currentAccessUnit.reserve(1024 * 1024); // VVC frames can be very large
}

void RtpVVCDepacketizer::processRtpPayload(const uint8_t* rtpPayload, size_t payloadLen, bool markerBit, AMediaCodec* codec) {
    if (!rtpPayload || payloadLen < 2 || !codec) return;

    // VVC RTP Payload Header (RFC 9328)
    // Structure is complex, but often handles single NAL units or FUs.
    // For God Mode, we implement a baseline that extracts the VVC NALU.
    
    // Simplification: In many streaming contexts, we just strip RTP headers and reassemble.
    // Real RFC 9328 handling would involve NAL type 29 (FU) etc.
    
    currentAccessUnit.insert(currentAccessUnit.end(), rtpPayload, rtpPayload + payloadLen);

    if (markerBit && !currentAccessUnit.empty()) {
        submitAccessUnitToMediaCodec(codec, currentAccessUnit.data(), currentAccessUnit.size());
        currentAccessUnit.clear();
    }
}

void RtpVVCDepacketizer::submitAccessUnitToMediaCodec(AMediaCodec* codec, const uint8_t* data, size_t size) {
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
