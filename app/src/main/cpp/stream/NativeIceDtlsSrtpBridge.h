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
#include <string>
#include <vector>
#include <memory>

#include "IceAgent.h"
#include "DtlsTransport.h"
#include "SrtpContext.h"
#include "SctpTransport.h"
#include "IStreamEngine.h"
#include "RtpDepacketizer.h"

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
 * Strictly Zero-WebView, Zero-WebRTC implementation.
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
     * Entry point to start the native transport pipeline.
     */
    void start(const std::string& localUfrag, const std::string& localPwd);
    
    /**
     * Updates remote credentials and candidates from SDP.
     */
    void setRemoteParameters(const std::string& ufrag, const std::string& pwd, const std::vector<IceCandidate>& candidates);

    /**
     * Crafts and sends an RFC 5389 STUN Binding Request.
     */
    bool sendStunBindingPing(int socketFd, const struct sockaddr* destAddr, socklen_t addrLen);

    /**
     * Inspects incoming multiplexed UDP packets (STUN, DTLS, SRTP, SCTP).
     */
    bool processIncomingPacket(const uint8_t* buffer, size_t length, int socketFd, const struct sockaddr* srcAddr, socklen_t addrLen);

    /**
     * Specifically processes STUN packets for RTT measurement and consent.
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

    /**
     * Set the active stream engine for video dispatch.
     */
    void setStreamEngine(IStreamEngine* engine);

    /**
     * Sends a RTCP PLI (Picture Loss Indication) to request a keyframe.
     */
    void requestPli();

    /**
     * Sends a RTCP FIR (Full Intra Request) / Keyframe request.
     */
    void requestKeyframe();

private:
    static uint64_t nowMicros() {
        return static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now().time_since_epoch()
            ).count()
        );
    }

    void onIcePairSelected(int socketFd, const sockaddr* remoteAddr, socklen_t addrLen);
    void onDtlsConnected(const SrtpKeys& keys);
    void onRtpPacketDecrypted(const RtpPacket& packet);
    void onRtcpPacketDecrypted(const uint8_t* data, size_t size);

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

    // Session State
    std::atomic<int> activeSocket{-1};
    sockaddr_storage remoteAddr{};
    socklen_t remoteAddrLen = 0;
    std::atomic<uint32_t> serverVideoSsrc{0};
    std::atomic<uint32_t> clientSsrc{0x12345678};
    
    IStreamEngine* activeEngine = nullptr;

    // Sub-components
    std::unique_ptr<IceAgent> iceAgent;
    std::unique_ptr<DtlsTransport> dtlsTransport;
    std::unique_ptr<SrtpContext> srtpContext;
    std::unique_ptr<SctpTransport> sctpTransport;

    // Depacketizers
    std::unique_ptr<RtpDepacketizer> h264Depacketizer;
    std::unique_ptr<RtpDepacketizer> h265Depacketizer;
    std::unique_ptr<RtpDepacketizer> av1Depacketizer;
};

} // namespace GodMode::Stream
