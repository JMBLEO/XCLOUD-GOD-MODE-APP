#include "RtpVVCDepacketizer.h"
#include <cstring>
#include <android/log.h>

#define LOG_TAG "RtpVVCDepacketizer"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace GodMode::Stream {

RtpVVCDepacketizer::RtpVVCDepacketizer() {
    currentAccessUnit.reserve(1024 * 1024); // VVC frames can be very large
}

void RtpVVCDepacketizer::processRtpPayload(const uint8_t* rtpPayload, size_t payloadLen, bool markerBit, IStreamEngine* engine) {
    if (!rtpPayload || payloadLen < 2 || !engine) return;

    // VVC RTP Payload Header (RFC 9328)
    // Structure is complex, but often handles single NAL units or FUs.
    // For God Mode, we implement a baseline that extracts the VVC NALU.
    
    // Simplification: In many streaming contexts, we just strip RTP headers and reassemble.
    // Real RFC 9328 handling would involve NAL type 29 (FU) etc.
    
    currentAccessUnit.insert(currentAccessUnit.end(), rtpPayload, rtpPayload + payloadLen);

    if (markerBit && !currentAccessUnit.empty()) {
        submitAccessUnitToEngine(engine, currentAccessUnit.data(), currentAccessUnit.size());
        currentAccessUnit.clear();
    }
}

void RtpVVCDepacketizer::submitAccessUnitToEngine(IStreamEngine* engine, const uint8_t* data, size_t size) {
    if (!engine || !data || size == 0) return;
    
    // VVC keyframe check (NAL types like IDR_W_RADL=7, IDR_N_LP=8, CRA_NUT=9)
    bool isKeyframe = false;
    if (size > 4) {
        uint8_t nalType = (data[4] >> 3) & 0x1F;
        if (nalType >= 7 && nalType <= 9) isKeyframe = true;
    }

    engine->pushAnnexBNalUnit(data, size, getMonotonicMicros(), isKeyframe);
}

}
