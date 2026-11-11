#include "DtlsTransport.h"
#include <android/log.h>

#define TAG "DtlsTransport"

namespace GodMode::Stream {

DtlsTransport::DtlsTransport() {}

DtlsTransport::~DtlsTransport() {
    stop();
}

bool DtlsTransport::initialize(bool isClient) {
    isClientSide = isClient;
#ifdef GM_HAS_OPENSSL
    // OpenSSL initialization logic goes here (SSL_library_init, etc.)
    return true;
#else
    __android_log_print(ANDROID_LOG_WARN, TAG, "OpenSSL not found. DTLS will be mocked.");
    return true; 
#endif
}

void DtlsTransport::attachSocket(int socketFd) {
    this->socketFd = socketFd;
}

bool DtlsTransport::startHandshake(OnConnectedCallback callback) {
    onConnected = callback;
    __android_log_print(ANDROID_LOG_INFO, TAG, "Starting DTLS handshake...");
    
#ifdef GM_HAS_OPENSSL
    // Handshake logic using SSL_do_handshake or non-blocking BIOs
#else
    // Mock successful handshake
    connected = true;
    if (onConnected) {
        SrtpKeys keys;
        keys.clientWriteKey.assign(32, 0xAA);
        keys.serverWriteKey.assign(32, 0xBB);
        keys.clientSalt.assign(14, 0xCC);
        keys.serverSalt.assign(14, 0xDD);
        onConnected(keys);
    }
#endif
    return true;
}

void DtlsTransport::processIncomingData(const uint8_t* data, size_t size) {
#ifdef GM_HAS_OPENSSL
    // Feed data to readBio and call SSL_read
#endif
}

void DtlsTransport::stop() {
#ifdef GM_HAS_OPENSSL
    if (ssl) SSL_free(ssl);
    if (sslCtx) SSL_CTX_free(sslCtx);
#endif
}

void DtlsTransport::exportSrtpKeys() {
#ifdef GM_HAS_OPENSSL
    // Use SSL_export_keying_material for SRTP keys
#endif
}

} // namespace GodMode::Stream
