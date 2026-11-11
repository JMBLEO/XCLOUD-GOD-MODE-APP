#include "SrtpContext.h"
#include <android/log.h>
#include <cstring>

#define TAG "SrtpContext"

namespace GodMode::Stream {

SrtpContext::SrtpContext() : initialized(false) {}

SrtpContext::~SrtpContext() {}

bool SrtpContext::initialize(const SrtpKeys& keys) {
    std::lock_guard<std::mutex> lock(srtpMutex);
    __android_log_print(ANDROID_LOG_INFO, TAG, "Initializing SRTP context...");
    // libsrtp2 initialization logic goes here.
    initialized = true;
    return true;
}

void SrtpContext::decryptIncoming(uint8_t* data, size_t* size) {
    if (!initialized) return;
    
    // Check if it is RTCP (RFC 3711)
    bool isRtcp = (*size >= 2) && (data[1] >= 200 && data[1] <= 204);
    
    if (isRtcp) {
        // srtp_unprotect_rtcp
        if (onRtcp) onRtcp(data, *size);
    } else {
        // srtp_unprotect
        RtpPacket p = parseRtp(data, *size);
        if (onRtp) onRtp(p);
    }
}

void SrtpContext::encryptOutgoingRtp(uint8_t* data, size_t* size) {
    // srtp_protect
}

void SrtpContext::encryptOutgoingRtcp(uint8_t* data, size_t* size) {
    // srtp_protect_rtcp
}

void SrtpContext::setCallbacks(OnRtpPacketCallback rtpCb, OnRtcpPacketCallback rtcpCb) {
    onRtp = rtpCb;
    onRtcp = rtcpCb;
}

RtpPacket SrtpContext::parseRtp(const uint8_t* data, size_t size) {
    RtpPacket p;
    if (size < 12) return p;
    
    p.payloadType = data[1] & 0x7F;
    p.marker = (data[1] & 0x80) != 0;
    p.sequenceNumber = (static_cast<uint16_t>(data[2]) << 8) | data[3];
    p.timestamp = (static_cast<uint32_t>(data[4]) << 24) | (static_cast<uint32_t>(data[5]) << 16) | (static_cast<uint32_t>(data[6]) << 8) | data[7];
    p.ssrc = (static_cast<uint32_t>(data[8]) << 24) | (static_cast<uint32_t>(data[9]) << 16) | (static_cast<uint32_t>(data[10]) << 8) | data[11];
    
    // Simplified: assume no CSRC or extensions for the stub
    p.payload = data + 12;
    p.payloadSize = size - 12;
    
    return p;
}

} // namespace GodMode::Stream
