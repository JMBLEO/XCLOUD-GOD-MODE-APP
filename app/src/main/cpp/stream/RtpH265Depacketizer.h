#pragma once
#include "RtpDepacketizer.h"

namespace GodMode::Stream {

class RtpH265Depacketizer : public RtpDepacketizer {
private:
    std::vector<uint8_t> currentAccessUnit;
    std::vector<uint8_t> cachedVps;
    std::vector<uint8_t> cachedSps;
    std::vector<uint8_t> cachedPps;
    bool hasConfiguredCsd = false;

    static constexpr uint8_t ANNEXB_START_CODE[4] = {0x00, 0x00, 0x00, 0x01};

public:
    RtpH265Depacketizer();
    void processRtpPayload(const uint8_t* rtpPayload, size_t payloadLen, bool markerBit, AMediaCodec* codec) override;

private:
    void appendAnnexBNal(const uint8_t* data, size_t len);
    void submitCodecConfigToMediaCodec(AMediaCodec* codec);
    void submitAccessUnitToMediaCodec(AMediaCodec* codec, const uint8_t* data, size_t size);
};

}
