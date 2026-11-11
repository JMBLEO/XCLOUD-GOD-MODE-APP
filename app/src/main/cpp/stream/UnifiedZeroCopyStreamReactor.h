#pragma once

#include <memory>
#include <thread>
#include <atomic>
#include <vector>
#include <string>
#include <mutex>
#include <poll.h>
#include <sys/epoll.h>
#ifdef GM_HAS_OPENSSL
#include <openssl/ssl.h>
#endif
#include "DualStackNetworkSocket.h"
#include "IStreamEngine.h"
#include "XcloudNativeTransport.h"
#include "ZeroTrustNetworkSecurityGatekeeper.h"
#include "GodModeVideoIngress.h"

namespace GodMode::Stream {

class UnifiedZeroCopyStreamReactor {
public:
    struct Configuration {
        std::string remoteIp;
        uint16_t remotePort;
        std::string remoteUfrag;
        std::string remotePwd;
        std::string localUfrag;
        bool enableIPv6 = true;
        bool force1440p = false;
        uint32_t clientSsrc = 0x12345678;
        uint32_t serverVideoSsrc = 0;
    };

    UnifiedZeroCopyStreamReactor(IStreamEngine* engine);
    ~UnifiedZeroCopyStreamReactor();

    bool start(const Configuration& config);
    void stop();

    bool isRunning() const { return running.load(); }
    void setStreamEngine(IStreamEngine* engine) { streamEngine = engine; }
    void injectDirectSrtpKeys(const uint8_t* key, size_t keyLen, const uint8_t* salt, size_t saltLen);

private:
    void reactorLoop();
    void handleIncomingPacket(const uint8_t* buf, size_t len, const struct sockaddr* srcAddr, socklen_t addrLen);
    
    void processStunPacket(const uint8_t* buf, size_t len, const struct sockaddr* srcAddr, socklen_t addrLen);
    void processDtlsPacket(const uint8_t* buf, size_t len);
    void processRtpPacket(const uint8_t* buf, size_t len);
    void processRtcpPacket(const uint8_t* buf, size_t len);

#ifdef GM_HAS_OPENSSL
    // DTLS specific
    bool setupDtls(const Configuration& config);
    static int dtlsBioWrite(BIO* b, const char* in, int inl);
    static int dtlsBioRead(BIO* b, char* out, int outl);
    static long dtlsBioCtrl(BIO* b, int cmd, long num, void* ptr);
    static int dtlsBioCreate(BIO* b);
    static int dtlsBioDestroy(BIO* b);
#else
    bool setupDtls(const Configuration& config);
#endif

    IStreamEngine* streamEngine;
    Configuration activeConfig;
    
    std::unique_ptr<GodMode::Network::DualStackNetworkSocket> socket;
    std::unique_ptr<GodMode::Network::NativeIceSrtpStreamInjector> iceInjector;
    GodMode::Network::HotKeySrtpRotator srtpRotator;
    GodMode::Security::ZeroTrustNetworkSecurityGatekeeper securityGatekeeper;
    CompleteAccessUnitAssembler auAssembler;
    RtpSequenceReorderBuffer reorderBuffer;
    CompleteH264AccessUnitAssembler h264IngressAssembler;
    CompleteHevcAccessUnitAssembler hevcIngressAssembler;
    CompleteAv1AccessUnitAssembler av1IngressAssembler;
    XcloudClockDriftCompensator clockCompensator;
    BbrNanoCongestionController networkGovernor;

    std::thread reactorThread;
    std::atomic<bool> running{false};
    int epollFd = -1;
    struct sockaddr_storage remoteServerAddr{};
    socklen_t remoteServerAddrLen = sizeof(struct sockaddr_in);

#ifdef GM_HAS_OPENSSL
    // OpenSSL / DTLS
    SSL_CTX* sslCtx = nullptr;
    SSL* ssl = nullptr;
    BIO* bio = nullptr;
#endif
    std::mutex sslMutex;

    // SRTP Keys
    uint8_t serverMasterKey[16]{};
    uint8_t serverMasterSalt[14]{};
    bool srtpKeysReady = false;

    // Stats
    std::atomic<uint32_t> packetsReceived{0};
    std::atomic<uint32_t> framesDecoded{0};
};

} // namespace GodMode::Stream
