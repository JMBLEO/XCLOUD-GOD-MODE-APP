#pragma once

#include <vector>
#include <functional>
#include <memory>
#include <mutex>
#include "DtlsTransport.h"

namespace GodMode::Stream {

struct RtpPacket {
    uint8_t payloadType;
    uint16_t sequenceNumber;
    uint32_t timestamp;
    uint32_t ssrc;
    bool marker;
    const uint8_t* payload;
    size_t payloadSize;
};

/**
 * SrtpContext: Handles encryption/decryption of RTP and RTCP packets.
 * Alignment: libsrtp2 AES-CM/HMAC-SHA1 stack.
 */
class SrtpContext {
public:
    using OnRtpPacketCallback = std::function<void(const RtpPacket& packet)>;
    using OnRtcpPacketCallback = std::function<void(const uint8_t* data, size_t size)>;

    SrtpContext();
    ~SrtpContext();

    bool initialize(const SrtpKeys& keys);
    
    void decryptIncoming(uint8_t* data, size_t* size);
    void encryptOutgoingRtp(uint8_t* data, size_t* size);
    void encryptOutgoingRtcp(uint8_t* data, size_t* size);

    void setCallbacks(OnRtpPacketCallback rtpCb, OnRtcpPacketCallback rtcpCb);

private:
    RtpPacket parseRtp(const uint8_t* data, size_t size);

    OnRtpPacketCallback onRtp;
    OnRtcpPacketCallback onRtcp;
    std::mutex srtpMutex;
    bool initialized = false;
};

} // namespace GodMode::Stream
