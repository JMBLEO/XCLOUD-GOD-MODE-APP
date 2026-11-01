#pragma once
#include <cstdint>
#include <vector>
#include <media/NdkMediaCodec.h>

namespace GodMode::Stream {

class RtpDepacketizer {
public:
    virtual ~RtpDepacketizer() = default;
    virtual void processRtpPayload(const uint8_t* rtpPayload, size_t payloadLen, bool markerBit, AMediaCodec* codec) = 0;
};

}
