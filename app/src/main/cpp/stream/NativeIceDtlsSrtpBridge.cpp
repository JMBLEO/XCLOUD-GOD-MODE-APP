#include "NativeIceDtlsSrtpBridge.h"
#include <random>
#include <algorithm>
#include <cmath>

namespace GodMode::Stream {

NativeIceDtlsSrtpBridge::NativeIceDtlsSrtpBridge() {
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

bool NativeIceDtlsSrtpBridge::sendStunBindingPing(int socketFd, const struct sockaddr* destAddr, socklen_t addrLen) {
    if (socketFd < 0 || !destAddr) return false;

    uint8_t packet[20];
    // 1. Message Type: 0x0001 (Binding Request)
    packet[0] = 0x00;
    packet[1] = 0x01;
    // 2. Message Length: 0x0000 (No additional attributes for lightweight ping)
    packet[2] = 0x00;
    packet[3] = 0x00;
    // 3. Magic Cookie: 0x2112A442 (RFC 5389)
    packet[4] = 0x21;
    packet[5] = 0x12;
    packet[6] = 0xA4;
    packet[7] = 0x42;

    // 4. Random 96-bit Transaction ID (12 bytes)
    static std::mt19937 rng(1337);
    for (int i = 8; i < 20; ++i) {
        packet[i] = static_cast<uint8_t>(rng() & 0xFF);
    }

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

bool NativeIceDtlsSrtpBridge::processIncomingStunPacket(
    const uint8_t* buffer,
    size_t length,
    int socketFd,
    const struct sockaddr* srcAddr,
    socklen_t addrLen
) {
    if (!buffer || length < 20) return false;

    uint16_t msgType = static_cast<uint16_t>((buffer[0] << 8) | buffer[1]);
    uint32_t magicCookie = (static_cast<uint32_t>(buffer[4]) << 24) |
                           (static_cast<uint32_t>(buffer[5]) << 16) |
                           (static_cast<uint32_t>(buffer[6]) << 8)  |
                           static_cast<uint32_t>(buffer[7]);

    if (magicCookie != STUN_MAGIC_COOKIE) {
        return false; // Not RFC 5389 compliant STUN
    }

    const uint8_t* txId = buffer + 8;

    // CASE A: STUN Binding Success Response (0x0101) received from Azure
    if (msgType == STUN_BINDING_SUCCESS_RESPONSE) {
        uint64_t recvTime = nowMicros();
        int64_t deltaUs = -1;

        {
            std::lock_guard<std::mutex> lock(transactionMutex);
            for (auto& tx : pendingTransactions) {
                if (tx.inUse && std::memcmp(tx.transactionId, txId, 12) == 0) {
                    deltaUs = static_cast<int64_t>(recvTime - tx.sentTimestampUs);
                    tx.inUse = false; // Mark slot consumed
                    break;
                }
            }
        }

        if (deltaUs >= 0) {
            // Update RTT delta metrics
            int64_t prevRtt = lastRttUs.load(std::memory_order_relaxed);
            lastRttUs.store(deltaUs, std::memory_order_relaxed);

            // Min / Max RTT tracking
            int64_t currentMin = minRttUs.load(std::memory_order_relaxed);
            if (deltaUs < currentMin) minRttUs.store(deltaUs, std::memory_order_relaxed);

            int64_t currentMax = maxRttUs.load(std::memory_order_relaxed);
            if (deltaUs > currentMax) maxRttUs.store(deltaUs, std::memory_order_relaxed);

            // Smoothed RTT (EWMA alpha = 0.125)
            int64_t currentSmoothed = smoothedRttUs.load(std::memory_order_relaxed);
            int64_t newSmoothed = (currentSmoothed == 0) ? deltaUs : ((currentSmoothed * 7) + deltaUs) / 8;
            smoothedRttUs.store(newSmoothed, std::memory_order_relaxed);

            // RFC 3550 / RFC 5389 Jitter: J = J + (|D| - J) / 16
            if (prevRtt > 0) {
                int64_t d = std::abs(deltaUs - prevRtt);
                int64_t prevJitter = rfcJitterUs.load(std::memory_order_relaxed);
                int64_t newJitter = prevJitter + (d - prevJitter) / 16;
                rfcJitterUs.store(newJitter, std::memory_order_relaxed);
            }

            totalSuccessReceived.fetch_add(1, std::memory_order_relaxed);
            return true;
        }
    }
    // CASE B: Incoming STUN Binding Request (0x0001) from Azure blade (ICE Consent Check)
    else if (msgType == STUN_BINDING_REQUEST && socketFd >= 0 && srcAddr) {
        // Construct and send Binding Success Response (0x0101) echoing the 12-byte Transaction ID
        uint8_t resp[20];
        resp[0] = 0x01; // Success Response (0x0101)
        resp[1] = 0x01;
        resp[2] = 0x00; // Length = 0
        resp[3] = 0x00;
        resp[4] = 0x21; // Magic Cookie
        resp[5] = 0x12;
        resp[6] = 0xA4;
        resp[7] = 0x42;
        std::memcpy(resp + 8, txId, 12); // Echo Transaction ID

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
