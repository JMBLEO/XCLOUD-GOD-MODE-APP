#pragma once

#include <string>
#include <vector>
#include <functional>
#include <memory>
#include <atomic>
#include <mutex>

#ifdef GM_HAS_OPENSSL
#include <openssl/ssl.h>
#include <openssl/err.h>
#endif

namespace GodMode::Stream {

struct SrtpKeys {
    std::vector<uint8_t> clientWriteKey;
    std::vector<uint8_t> serverWriteKey;
    std::vector<uint8_t> clientSalt;
    std::vector<uint8_t> serverSalt;
};

/**
 * DtlsTransport: Handles DTLS 1.2 handshake and SRTP key exportation.
 * Alignment: OpenSSL/BoringSSL embedded stack.
 */
class DtlsTransport {
public:
    using OnConnectedCallback = std::function<void(const SrtpKeys& keys)>;

    DtlsTransport();
    ~DtlsTransport();

    bool initialize(bool isClient);
    void attachSocket(int socketFd);
    
    bool startHandshake(OnConnectedCallback callback);
    void processIncomingData(const uint8_t* data, size_t size);
    
    void stop();

    bool isHandshakeComplete() const { return connected.load(); }
    const std::string& getLocalFingerprint() const { return localFingerprint; }

private:
    void exportSrtpKeys();

#ifdef GM_HAS_OPENSSL
    SSL_CTX* sslCtx = nullptr;
    SSL* ssl = nullptr;
    BIO* readBio = nullptr;
    BIO* writeBio = nullptr;
#endif

    std::atomic<bool> connected{false};
    std::atomic<int> socketFd{-1};
    bool isClientSide = true;

    std::string localFingerprint;
    OnConnectedCallback onConnected;
    std::mutex sslMutex;
};

} // namespace GodMode::Stream
