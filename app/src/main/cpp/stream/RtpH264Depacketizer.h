#pragma once
#include "RtpDepacketizer.h"

namespace GodMode::Stream {

class RtpH264Depacketizer : public RtpDepacketizer {
private:
    std::vector<uint8_t> currentAccessUnit;
    std::vector<uint8_t> cachedSps;
    std::vector<uint8_t> cachedPps;
    bool hasConfiguredSpsPps = false;

    static constexpr uint8_t ANNEXB_START_CODE[4] = {0x00, 0x00, 0x00, 0x01};

public:
    RtpH264Depacketizer();
    void processRtpPayload(const uint8_t* rtpPayload, size_t payloadLen, bool markerBit, IStreamEngine* engine) override;

private:
    void appendAnnexBNal(const uint8_t* data, size_t len);
    void submitCodecConfigToEngine(IStreamEngine* engine);
    void submitAccessUnitToEngine(IStreamEngine* engine, const uint8_t* data, size_t size);
};

}
