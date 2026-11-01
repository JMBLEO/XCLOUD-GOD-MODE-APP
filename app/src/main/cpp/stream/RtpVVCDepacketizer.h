#pragma once
#include "RtpDepacketizer.h"

namespace GodMode::Stream {

class RtpVVCDepacketizer : public RtpDepacketizer {
private:
    std::vector<uint8_t> currentAccessUnit;
    static constexpr uint8_t ANNEXB_START_CODE[4] = {0x00, 0x00, 0x00, 0x01};

public:
    RtpVVCDepacketizer();
    void processRtpPayload(const uint8_t* rtpPayload, size_t payloadLen, bool markerBit, AMediaCodec* codec) override;

private:
    void submitAccessUnitToMediaCodec(AMediaCodec* codec, const uint8_t* data, size_t size);
};

}
