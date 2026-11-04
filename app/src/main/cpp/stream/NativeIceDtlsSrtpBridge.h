#pragma once

#include <cstdint>
#include <cstddef>
#include <atomic>
#include <mutex>
#include <array>
#include <chrono>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <android/log.h>
#include <cstring>

#define STUN_LOG_TAG "NativeIceBridge"
#define STUN_LOGI(...) __android_log_print(ANDROID_LOG_INFO, STUN_LOG_TAG, __VA_ARGS__)
#define STUN_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, STUN_LOG_TAG, __VA_ARGS__)
#define STUN_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, STUN_LOG_TAG, __VA_ARGS__)

namespace GodMode::Stream {

/**
 * Structure holding high-resolution STUN RTT telemetry metrics.
 */
struct StunLatencyReport {
    float currentRttMs = 0.0f;
    float minRttMs = 999.0f;
    float maxRttMs = 0.0f;
    float smoothedRttMs = 0.0f;
    float jitterMs = 0.0f;
    uint64_t totalRequestsSent = 0;
    uint64_t totalSuccessReceived = 0;
    int64_t lastDeltaUs = 0;
};

/**
 * NativeIceDtlsSrtpBridge:
 * Orchestrates multiplexed ICE, STUN, DTLS, and SRTP transport.
 * Specifically measures exact real-time network Round-Trip Time (RTT) by tracking
 * outgoing STUN Binding Requests (0x0001) and calculating the delta microseconds
 * upon arrival of corresponding STUN Success Responses (0x0101).
 */
class NativeIceDtlsSrtpBridge {
public:
    static constexpr uint16_t STUN_BINDING_REQUEST = 0x0001;
    static constexpr uint16_t STUN_BINDING_SUCCESS_RESPONSE = 0x0101;
    static constexpr uint16_t STUN_BINDING_ERROR_RESPONSE = 0x0111;
    static constexpr uint32_t STUN_MAGIC_COOKIE = 0x2112A442;
    static constexpr size_t MAX_PENDING_TRANSACTIONS = 64;

    struct StunTransaction {
        uint8_t transactionId[12];
        uint64_t sentTimestampUs = 0;
        bool inUse = false;
    };

    static NativeIceDtlsSrtpBridge& getInstance() {
        static NativeIceDtlsSrtpBridge instance;
        return instance;
    }

    NativeIceDtlsSrtpBridge();
    ~NativeIceDtlsSrtpBridge() = default;

    /**
     * Crafts and sends an RFC 5389 STUN Binding Request, recording the 96-bit
     * transaction ID and microsecond timestamp to calculate RTT when Azure replies.
     */
    bool sendStunBindingPing(int socketFd, const struct sockaddr* destAddr, socklen_t addrLen);

    /**
     * Inspects incoming multiplexed UDP packets.
     * If the packet is a STUN Success Response (0x0101), calculates the RTT delta.
     * If the packet is an incoming STUN Request from Azure, sends a Consent Response (0x0101).
     */
    bool processIncomingStunPacket(const uint8_t* buffer, size_t length, int socketFd, const struct sockaddr* srcAddr, socklen_t addrLen);

    /**
     * Retrieves the latest STUN latency metrics.
     */
    StunLatencyReport getLatencyReport();

    /**
     * Resets telemetry metrics.
     */
    void resetMetrics();

private:
    static uint64_t nowMicros() {
        return static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now().time_since_epoch()
            ).count()
        );
    }

    std::mutex transactionMutex;
    std::array<StunTransaction, MAX_PENDING_TRANSACTIONS> pendingTransactions{};
    size_t nextSlotIndex = 0;

    // Atomic telemetry values
    std::atomic<int64_t> lastRttUs{0};
    std::atomic<int64_t> minRttUs{999000};
    std::atomic<int64_t> maxRttUs{0};
    std::atomic<int64_t> smoothedRttUs{0};
    std::atomic<int64_t> rfcJitterUs{0};
    std::atomic<uint64_t> totalRequestsSent{0};
    std::atomic<uint64_t> totalSuccessReceived{0};
};

} // namespace GodMode::Stream
