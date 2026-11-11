#pragma once
#include "IStreamEngine.h"
#include <media/NdkMediaCodec.h>

namespace GodMode::Stream {

class RtpDepacketizer {
public:
    virtual ~RtpDepacketizer() = default;
    virtual void processRtpPayload(const uint8_t* rtpPayload, size_t payloadLen, bool markerBit, IStreamEngine* engine) = 0;
};

}
