#pragma once
#include "RtpDepacketizer.h"

namespace GodMode::Stream {

class RtpAV1Depacketizer : public RtpDepacketizer {
private:
    std::vector<uint8_t> currentAccessUnit;
    bool hasConfiguredCsd = false;

public:
    RtpAV1Depacketizer();
    void processRtpPayload(const uint8_t* rtpPayload, size_t payloadLen, bool markerBit, IStreamEngine* engine) override;

private:
    void submitAccessUnitToEngine(IStreamEngine* engine, const uint8_t* data, size_t size);
};

}
