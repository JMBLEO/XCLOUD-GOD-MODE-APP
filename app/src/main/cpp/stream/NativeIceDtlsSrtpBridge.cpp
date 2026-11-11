#include "NativeIceDtlsSrtpBridge.h"
#include <random>
#include <algorithm>
#include <cmath>
#include "RtpH264Depacketizer.h"
#include "RtpH265Depacketizer.h"
#include "RtpAV1Depacketizer.h"

namespace GodMode::Stream {

NativeIceDtlsSrtpBridge::NativeIceDtlsSrtpBridge() {
    iceAgent = std::make_unique<IceAgent>();
    dtlsTransport = std::make_unique<DtlsTransport>();
    srtpContext = std::make_unique<SrtpContext>();
    sctpTransport = std::make_unique<SctpTransport>();
    
    h264Depacketizer = std::make_unique<RtpH264Depacketizer>();
    h265Depacketizer = std::make_unique<RtpH265Depacketizer>();
    av1Depacketizer = std::make_unique<RtpAV1Depacketizer>();

    resetMetrics();
}

void NativeIceDtlsSrtpBridge::resetMetrics() {
    std::lock_guard<std::mutex> lock(transactionMutex);
    for (auto& tx : pendingTransactions) {
        tx.inUse = false;
        tx.sentTimestampUs = 0;
    }
    lastRttUs.store(0, std::memory_order_relaxed);
    minRttUs.store(999000, std::memory_order_relaxed);
    maxRttUs.store(0, std::memory_order_relaxed);
    smoothedRttUs.store(0, std::memory_order_relaxed);
    rfcJitterUs.store(0, std::memory_order_relaxed);
    totalRequestsSent.store(0, std::memory_order_relaxed);
    totalSuccessReceived.store(0, std::memory_order_relaxed);
}

void NativeIceDtlsSrtpBridge::start(const std::string& localUfrag, const std::string& localPwd) {
    STUN_LOGI("Starting Native Transport Pipeline...");
    iceAgent->startGathering([this](const IceCandidate& candidate) {
        STUN_LOGD("Gathered local candidate: %s:%d", candidate.address.c_str(), candidate.port);
    });
}

void NativeIceDtlsSrtpBridge::setRemoteParameters(
    const std::string& ufrag, 
    const std::string& pwd, 
    const std::vector<IceCandidate>& candidates
) {
    iceAgent->setRemoteCredentials(ufrag, pwd);
    for (const auto& c : candidates) {
        iceAgent->addRemoteCandidate(c);
    }
    
    iceAgent->startConnectivityChecks([this](int socketFd, const sockaddr* remoteAddr, socklen_t addrLen) {
        onIcePairSelected(socketFd, remoteAddr, addrLen);
    });
}

void NativeIceDtlsSrtpBridge::onIcePairSelected(int socketFd, const sockaddr* remoteAddr, socklen_t addrLen) {
    STUN_LOGI("ICE Pair Selected. Starting DTLS handshake...");
    activeSocket.store(socketFd);
    std::memcpy(&this->remoteAddr, remoteAddr, addrLen);
    this->remoteAddrLen = addrLen;

    dtlsTransport->attachSocket(socketFd);
    dtlsTransport->initialize(true); 
    dtlsTransport->startHandshake([this](const SrtpKeys& keys) {
        onDtlsConnected(keys);
    });
}

void NativeIceDtlsSrtpBridge::onDtlsConnected(const SrtpKeys& keys) {
    STUN_LOGI("DTLS Handshake Complete. Initializing SRTP and SCTP...");
    srtpContext->initialize(keys);
    srtpContext->setCallbacks(
        [this](const RtpPacket& p) { onRtpPacketDecrypted(p); },
        [this](const uint8_t* data, size_t size) { /* Handle RTCP */ }
    );
    sctpTransport->initialize();
}

void NativeIceDtlsSrtpBridge::onRtpPacketDecrypted(const RtpPacket& packet) {
    // 1. Auto-detect server SSRC
    if (serverVideoSsrc.load() == 0 && packet.payloadType >= 96) {
        serverVideoSsrc.store(packet.ssrc);
    }

    if (!activeEngine) return;

    // 2. Dispatch to the correct depacketizer based on negotiated PT
    // Xbox GSSV typically uses 102 for the main video stream (H264 or HEVC)
    if (packet.payloadType == 102 || packet.payloadType == 121) {
        // H.264
        h264Depacketizer->processRtpPayload(packet.payload, packet.payloadSize, packet.marker, nullptr /* Engine used instead of codec */);
    } else if (packet.payloadType == 98 || packet.payloadType == 99) {
        // H.265 / HEVC
        h265Depacketizer->processRtpPayload(packet.payload, packet.payloadSize, packet.marker, nullptr);
    } else if (packet.payloadType == 96 || packet.payloadType == 97) {
        // AV1
        av1Depacketizer->processRtpPayload(packet.payload, packet.payloadSize, packet.marker, nullptr);
    }
}

void NativeIceDtlsSrtpBridge::onRtcpPacketDecrypted(const uint8_t* data, size_t size) {
    // Handle incoming RTCP feedback from server if needed
}

void NativeIceDtlsSrtpBridge::setStreamEngine(IStreamEngine* engine) {
    activeEngine = engine;
}

void NativeIceDtlsSrtpBridge::requestPli() {
    int fd = activeSocket.load();
    if (fd < 0 || remoteAddrLen == 0) return;

    uint8_t pliBuf[64];
    size_t pliLen = 0;
    buildRtcpPliRequest(clientSsrc.load(), serverVideoSsrc.load(), pliBuf, pliLen);

    srtpContext->encryptOutgoingRtcp(pliBuf, &pliLen);
    sendto(fd, pliBuf, pliLen, MSG_DONTWAIT, reinterpret_cast<const sockaddr*>(&remoteAddr), remoteAddrLen);
    STUN_LOGI("Sent RTCP PLI request for SSRC 0x%08X", serverVideoSsrc.load());
}

void NativeIceDtlsSrtpBridge::requestKeyframe() {
    requestPli(); // In GSSV, PLI is the standard way to request a keyframe
}

bool NativeIceDtlsSrtpBridge::sendStunBindingPing(int socketFd, const struct sockaddr* destAddr, socklen_t addrLen) {
    if (socketFd < 0 || !destAddr) return false;
    uint8_t packet[20];
    packet[0] = 0x00; packet[1] = 0x01; // Binding Request
    packet[2] = 0x00; packet[3] = 0x00; // Length 0
    packet[4] = 0x21; packet[5] = 0x12; packet[6] = 0xA4; packet[7] = 0x42; // Magic Cookie
    static std::mt19937 rng(1337);
    for (int i = 8; i < 20; ++i) packet[i] = static_cast<uint8_t>(rng() & 0xFF);
    uint64_t sentTime = nowMicros();
    {
        std::lock_guard<std::mutex> lock(transactionMutex);
        size_t slot = nextSlotIndex % MAX_PENDING_TRANSACTIONS;
        nextSlotIndex++;
        std::memcpy(pendingTransactions[slot].transactionId, packet + 8, 12);
        pendingTransactions[slot].sentTimestampUs = sentTime;
        pendingTransactions[slot].inUse = true;
    }
    totalRequestsSent.fetch_add(1, std::memory_order_relaxed);
    ssize_t sent = sendto(socketFd, packet, sizeof(packet), MSG_DONTWAIT, destAddr, addrLen);
    return sent == sizeof(packet);
}

bool NativeIceDtlsSrtpBridge::processIncomingPacket(
    const uint8_t* buffer,
    size_t length,
    int socketFd,
    const struct sockaddr* srcAddr,
    socklen_t addrLen
) {
    if (!buffer || length < 2) return false;
    uint8_t firstByte = buffer[0];
    if (firstByte < 2) return processIncomingStunPacket(buffer, length, socketFd, srcAddr, addrLen);
    if (firstByte >= 20 && firstByte <= 63) {
        dtlsTransport->processIncomingData(buffer, length);
        return true;
    }
    if (firstByte >= 128 && firstByte <= 191) {
        uint8_t* mutableBuf = const_cast<uint8_t*>(buffer);
        size_t mutableLen = length;
        srtpContext->decryptIncoming(mutableBuf, &mutableLen);
        return true;
    }
    return false;
}

bool NativeIceDtlsSrtpBridge::processIncomingStunPacket(
    const uint8_t* buffer,
    size_t length,
    int socketFd,
    const struct sockaddr* srcAddr,
    socklen_t addrLen
) {
    if (!buffer || length < 20) return false;
    uint16_t msgType = static_cast<uint16_t>((buffer[0] << 8) | buffer[1]);
    uint32_t magicCookie = (static_cast<uint32_t>(buffer[4]) << 24) | (static_cast<uint32_t>(buffer[5]) << 16) | (static_cast<uint32_t>(buffer[6]) << 8) | static_cast<uint32_t>(buffer[7]);
    if (magicCookie != STUN_MAGIC_COOKIE) return false;
    const uint8_t* txId = buffer + 8;
    if (msgType == STUN_BINDING_SUCCESS_RESPONSE) {
        uint64_t recvTime = nowMicros();
        int64_t deltaUs = -1;
        {
            std::lock_guard<std::mutex> lock(transactionMutex);
            for (auto& tx : pendingTransactions) {
                if (tx.inUse && std::memcmp(tx.transactionId, txId, 12) == 0) {
                    deltaUs = static_cast<int64_t>(recvTime - tx.sentTimestampUs);
                    tx.inUse = false;
                    break;
                }
            }
        }
        if (deltaUs >= 0) {
            int64_t prevRtt = lastRttUs.load(std::memory_order_relaxed);
            lastRttUs.store(deltaUs, std::memory_order_relaxed);
            int64_t currentMin = minRttUs.load(std::memory_order_relaxed);
            if (deltaUs < currentMin) minRttUs.store(deltaUs, std::memory_order_relaxed);
            int64_t currentMax = maxRttUs.load(std::memory_order_relaxed);
            if (deltaUs > currentMax) maxRttUs.store(deltaUs, std::memory_order_relaxed);
            int64_t currentSmoothed = smoothedRttUs.load(std::memory_order_relaxed);
            int64_t newSmoothed = (currentSmoothed == 0) ? deltaUs : ((currentSmoothed * 7) + deltaUs) / 8;
            smoothedRttUs.store(newSmoothed, std::memory_order_relaxed);
            if (prevRtt > 0) {
                int64_t d = std::abs(deltaUs - prevRtt);
                int64_t prevJitter = rfcJitterUs.load(std::memory_order_relaxed);
                int64_t newJitter = prevJitter + (d - prevJitter) / 16;
                rfcJitterUs.store(newJitter, std::memory_order_relaxed);
            }
            totalSuccessReceived.fetch_add(1, std::memory_order_relaxed);
            return true;
        }
    } else if (msgType == STUN_BINDING_REQUEST && socketFd >= 0 && srcAddr) {
        uint8_t resp[20];
        resp[0] = 0x01; resp[1] = 0x01; resp[2] = 0x00; resp[3] = 0x00;
        resp[4] = 0x21; resp[5] = 0x12; resp[6] = 0xA4; resp[7] = 0x42;
        std::memcpy(resp + 8, txId, 12);
        sendto(socketFd, resp, sizeof(resp), MSG_DONTWAIT, srcAddr, addrLen);
        return true;
    }
    return false;
}

StunLatencyReport NativeIceDtlsSrtpBridge::getLatencyReport() {
    StunLatencyReport report;
    int64_t rtt = lastRttUs.load(std::memory_order_relaxed);
    int64_t minR = minRttUs.load(std::memory_order_relaxed);
    int64_t maxR = maxRttUs.load(std::memory_order_relaxed);
    int64_t smoothed = smoothedRttUs.load(std::memory_order_relaxed);
    int64_t jitter = rfcJitterUs.load(std::memory_order_relaxed);
    report.lastDeltaUs = rtt;
    report.currentRttMs = (rtt > 0) ? static_cast<float>(rtt) / 1000.0f : 0.0f;
    report.minRttMs = (minR < 999000) ? static_cast<float>(minR) / 1000.0f : 0.0f;
    report.maxRttMs = static_cast<float>(maxR) / 1000.0f;
    report.smoothedRttMs = static_cast<float>(smoothed) / 1000.0f;
    report.jitterMs = static_cast<float>(jitter) / 1000.0f;
    report.totalRequestsSent = totalRequestsSent.load(std::memory_order_relaxed);
    report.totalSuccessReceived = totalSuccessReceived.load(std::memory_order_relaxed);
    return report;
}

} // namespace GodMode::Stream
