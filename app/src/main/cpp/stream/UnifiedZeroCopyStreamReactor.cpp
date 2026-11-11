#include "UnifiedZeroCopyStreamReactor.h"
#include "NativeIceDtlsSrtpBridge.h"
#include <sys/epoll.h>
#include <unistd.h>
#include <cstring>
#include <chrono>
#ifdef GM_HAS_OPENSSL
#include <openssl/err.h>
#endif

namespace GodMode::Stream {

UnifiedZeroCopyStreamReactor::UnifiedZeroCopyStreamReactor(IStreamEngine* engine)
    : streamEngine(engine) {
}

UnifiedZeroCopyStreamReactor::~UnifiedZeroCopyStreamReactor() {
    stop();
}

bool UnifiedZeroCopyStreamReactor::start(const Configuration& config) {
    if (running.load()) return false;

    activeConfig = config;
    socket = std::make_unique<GodMode::Network::DualStackNetworkSocket>();
    if (!socket->initialize()) {
        GM_LOGE("Reactor: Fallo al inicializar socket dual-stack");
        return false;
    }

    iceInjector = std::make_unique<GodMode::Network::NativeIceSrtpStreamInjector>();
    iceInjector->configureIceEndpoint(
        socket->getFd(),
        config.remoteIp.c_str(),
        config.remotePort,
        config.remoteUfrag,
        config.remotePwd,
        config.localUfrag
    );

    std::memset(&remoteServerAddr, 0, sizeof(remoteServerAddr));
    if (config.enableIPv6 && config.remoteIp.find(':') != std::string::npos) {
        struct sockaddr_in6* sin6 = reinterpret_cast<struct sockaddr_in6*>(&remoteServerAddr);
        sin6->sin6_family = AF_INET6;
        sin6->sin6_port = htons(config.remotePort);
        inet_pton(AF_INET6, config.remoteIp.c_str(), &sin6->sin6_addr);
        remoteServerAddrLen = sizeof(struct sockaddr_in6);
    } else {
        struct sockaddr_in* sin = reinterpret_cast<struct sockaddr_in*>(&remoteServerAddr);
        sin->sin_family = AF_INET;
        sin->sin_port = htons(config.remotePort);
        inet_pton(AF_INET, config.remoteIp.c_str(), &sin->sin_addr);
        remoteServerAddrLen = sizeof(struct sockaddr_in);
    }

    if (srtpKeysReady) {
        iceInjector->injectDirectMasterKeys(serverMasterKey, sizeof(serverMasterKey), serverMasterSalt, sizeof(serverMasterSalt));
    }

    if (!setupDtls(config)) {
        GM_LOGE("Reactor: Fallo al configurar DTLS");
        return false;
    }

    epollFd = epoll_create1(0);
    if (epollFd < 0) return false;

    struct epoll_event ev{};
    ev.events = EPOLLIN | EPOLLET;
    ev.data.fd = socket->getFd();
    if (epoll_ctl(epollFd, EPOLL_CTL_ADD, socket->getFd(), &ev) == -1) {
        close(epollFd);
        return false;
    }

    running.store(true);
    reactorThread = std::thread(&UnifiedZeroCopyStreamReactor::reactorLoop, this);

    // Iniciar nominación ICE inmediatamente
    iceInjector->sendAuthenticatedIceNominatedBindingRequest();

    return true;
}

void UnifiedZeroCopyStreamReactor::stop() {
    if (!running.exchange(false)) return;

    if (reactorThread.joinable()) {
        reactorThread.join();
    }

    if (epollFd >= 0) {
        close(epollFd);
        epollFd = -1;
    }

#ifdef GM_HAS_OPENSSL
    if (ssl) {
        SSL_shutdown(ssl);
        SSL_free(ssl);
        ssl = nullptr;
    }

    if (sslCtx) {
        SSL_CTX_free(sslCtx);
        sslCtx = nullptr;
    }
#endif

    if (socket) {
        socket->shutdown();
    }
}

void UnifiedZeroCopyStreamReactor::reactorLoop() {
    // Elevación de prioridad para minimizar jitter en el hilo del reactor
    elevateCurrentThreadToRealtimePriority();

    struct epoll_event events[16];
    uint8_t buffer[4096];

    while (running.load()) {
        int nfds = epoll_wait(epollFd, events, 16, 100);
        if (nfds < 0) {
            if (errno == EINTR) continue;
            break;
        }

        for (int i = 0; i < nfds; ++i) {
            if (events[i].data.fd == socket->getFd()) {
                struct sockaddr_storage srcAddr{};
                socklen_t addrLen = sizeof(srcAddr);
                
                while (true) {
                    ssize_t len = recvfrom(socket->getFd(), buffer, sizeof(buffer), 0,
                                           (struct sockaddr*)&srcAddr, &addrLen);
                    if (len < 0) {
                        if (errno == EAGAIN || errno == EWOULDBLOCK) break;
                        break;
                    }
                    handleIncomingPacket(buffer, static_cast<size_t>(len), (struct sockaddr*)&srcAddr, addrLen);
                }
            }
        }

        // Tarea de mantenimiento: Keep-alive y sondeo STUN RTT cada segundo
        static auto lastStunTime = std::chrono::steady_clock::now();
        auto now = std::chrono::steady_clock::now();
        if (std::chrono::duration_cast<std::chrono::milliseconds>(now - lastStunTime).count() >= 1000) {
            iceInjector->sendAuthenticatedIceNominatedBindingRequest();
            NativeIceDtlsSrtpBridge::getInstance().sendStunBindingPing(
                socket->getFd(),
                reinterpret_cast<const struct sockaddr*>(&remoteServerAddr),
                remoteServerAddrLen
            );
            lastStunTime = now;
        }
    }
}

void UnifiedZeroCopyStreamReactor::handleIncomingPacket(const uint8_t* buf, size_t len, const struct sockaddr* srcAddr, socklen_t addrLen) {
    if (srcAddr != nullptr && addrLen > 0) {
        std::memcpy(&remoteServerAddr, srcAddr, addrLen);
        remoteServerAddrLen = addrLen;
    }

    MultiplexedPacketType type = XcloudSinglePortDemuxer::classifyUdpPacket(buf, len);
    packetsReceived.fetch_add(1, std::memory_order_relaxed);

    switch (type) {
        case MultiplexedPacketType::STUN_PACKET:
            processStunPacket(buf, len, srcAddr, addrLen);
            break;
        case MultiplexedPacketType::DTLS_HANDSHAKE:
            processDtlsPacket(buf, len);
            break;
        case MultiplexedPacketType::RTP_MEDIA_STREAM:
            processRtpPacket(buf, len);
            break;
        case MultiplexedPacketType::RTCP_CONTROL:
            processRtcpPacket(buf, len);
            break;
        default:
            break;
    }
}

void UnifiedZeroCopyStreamReactor::processStunPacket(const uint8_t* buf, size_t len, const struct sockaddr* srcAddr, socklen_t addrLen) {
    // 1. Medir delta RTT si es una respuesta STUN Success Response (0x0101) o responder a consent
    NativeIceDtlsSrtpBridge::getInstance().processIncomingStunPacket(buf, len, socket->getFd(), srcAddr, addrLen);

    // 2. Responder a STUN Binding Requests de Azure para mantener el consentimiento ICE (RFC 7675)
    XcloudSinglePortDemuxer::handleStunConsentKeepAlive(socket->getFd(), buf, len, srcAddr, addrLen);
}

void UnifiedZeroCopyStreamReactor::processDtlsPacket(const uint8_t* buf, size_t len) {
#ifdef GM_HAS_OPENSSL
    std::lock_guard<std::mutex> lock(sslMutex);
    if (!ssl) return;

    // Inyectar el paquete DTLS en el BIO de lectura
    // Para simplificar, usamos un BIO de memoria o un bridge personalizado
    // BIO_write(bio, buf, len);
    
    // El motor de OpenSSL procesará el handshake
    int ret = SSL_do_handshake(ssl);
    if (ret == 1 && !srtpKeysReady) {
        if (iceInjector->extractDtlsSrtpKeys(ssl)) {
            GM_LOGI("Reactor: Handshake DTLS completado. Llaves SRTP extraídas.");
            srtpKeysReady = true;
        }
    } else if (ret <= 0) {
        int err = SSL_get_error(ssl, ret);
        if (err != SSL_ERROR_WANT_READ && err != SSL_ERROR_WANT_WRITE) {
            char errBuf[256];
            ERR_error_string_n(ERR_get_error(), errBuf, sizeof(errBuf));
            GM_LOGE("Reactor: Error DTLS: %s", errBuf);
        }
    }
#else
    // Si no hay OpenSSL, permitimos el flujo de medios para pruebas (Estado Inseguro)
    if (!srtpKeysReady) {
        GM_LOGW("Reactor: OpenSSL no disponible, operando en modo INSEGURO/ABIERTO");
        srtpKeysReady = true; 
    }
#endif
}

void UnifiedZeroCopyStreamReactor::processRtpPacket(const uint8_t* buf, size_t len) {
    if (!srtpKeysReady) return;

    RtpVideoPacket rtpPacket;
    bool hasRtp = VideoIngressRtpParser::parseRtp(buf, len, rtpPacket);
    ParsedRtpHeader parsed = stripRtpHeadersAndExtensions(buf, len);

    uint16_t seqNumber = hasRtp ? rtpPacket.seqNum : parsed.sequenceNumber;

    // Hot-Key Rotation: Verificar si debemos conmutar a una nueva llave SRTP
    if (srtpRotator.shouldSwitch(seqNumber)) {
        srtpRotator.performSwitch();
    }

    // Zero-Trust Security Check
    if (securityGatekeeper.inspectAndSanitizeIncomingFrame(buf, len, seqNumber) 
        != GodMode::Security::ZeroTrustNetworkSecurityGatekeeper::SecurityVerdict::ALLOW_PACKET) {
        return;
    }

    uint32_t rtpTs = hasRtp ? rtpPacket.timestamp : parsed.timestamp;
    uint64_t nowUs = getMonotonicMicros();
    clockCompensator.updateSync(rtpTs, nowUs);
    uint64_t adjustedPts = clockCompensator.getAdjustedPresentationTimeUs(rtpTs);

    // Notificar al gobernador de congestión BBR
    networkGovernor.process_packet_sent(len);

    if (hasRtp) {
        uint8_t pt = rtpPacket.payloadType;
        reorderBuffer.pushPacket(
            rtpPacket,
            activeConfig.clientSsrc,
            [this, adjustedPts, pt](const uint8_t* payload, size_t payloadLen, uint32_t ts, bool m, bool discontinuous) {
                uint32_t effectiveTs = (ts > 0) ? ts : static_cast<uint32_t>((adjustedPts * 90000ULL) / 1000000ULL);

                if (pt == 96 || pt == 97) { // AV1 / RTX
                    av1IngressAssembler.feedRtpPayload(
                        payload, payloadLen, effectiveTs, m, discontinuous,
                        activeConfig.clientSsrc, activeConfig.serverVideoSsrc,
                        [this](const uint8_t* auData, size_t auSize, uint64_t ptsUs, bool isKeyframe) {
                            if (streamEngine) {
                                streamEngine->pushAnnexBNalUnit(auData, auSize, ptsUs, isKeyframe);
                            }
                        },
                        [this](const uint8_t* pliData, size_t pliLen) {
                            if (socket && socket->getFd() >= 0 && remoteServerAddrLen > 0) {
                                ::sendto(socket->getFd(), pliData, pliLen, MSG_DONTWAIT, reinterpret_cast<const struct sockaddr*>(&remoteServerAddr), remoteServerAddrLen);
                            }
                        }
                    );
                } else if (pt == 98 || pt == 99) { // HEVC / RTX
                    hevcIngressAssembler.feedRtpPayload(
                        payload, payloadLen, effectiveTs, m, discontinuous,
                        activeConfig.clientSsrc, activeConfig.serverVideoSsrc,
                        [this](const uint8_t* auData, size_t auSize, uint64_t ptsUs, bool isKeyframe) {
                            if (streamEngine) {
                                streamEngine->pushAnnexBNalUnit(auData, auSize, ptsUs, isKeyframe);
                            }
                        },
                        [this](const uint8_t* pliData, size_t pliLen) {
                            if (socket && socket->getFd() >= 0 && remoteServerAddrLen > 0) {
                                ::sendto(socket->getFd(), pliData, pliLen, MSG_DONTWAIT, reinterpret_cast<const struct sockaddr*>(&remoteServerAddr), remoteServerAddrLen);
                            }
                        }
                    );
                } else { // Fallback H.264
                    h264IngressAssembler.feedRtpPayload(
                        payload, payloadLen, effectiveTs, m, discontinuous,
                        activeConfig.clientSsrc, activeConfig.serverVideoSsrc,
                        [this](const uint8_t* auData, size_t auSize, uint64_t ptsUs, bool isIdr, const H264SpsInfo& spsInfo) {
                            if (streamEngine) {
                                if (spsInfo.valid) {
                                    streamEngine->onResize(spsInfo.width, spsInfo.height);
                                }
                                streamEngine->pushAnnexBNalUnit(auData, auSize, ptsUs, isIdr);
                            }
                        },
                        [this](const uint8_t* pliData, size_t pliLen) {
                            // Enviar RTCP PLI a través del socket UDP
                            if (socket && socket->getFd() >= 0 && remoteServerAddrLen > 0) {
                                ::sendto(socket->getFd(), pliData, pliLen, MSG_DONTWAIT, reinterpret_cast<const struct sockaddr*>(&remoteServerAddr), remoteServerAddrLen);
                            }
                        }
                    );
                }
            },
            [this](const uint8_t* nackData, size_t nackLen) {
                // Enviar RTCP NACK a través del socket UDP
                if (socket && socket->getFd() >= 0 && remoteServerAddrLen > 0) {
                    ::sendto(socket->getFd(), nackData, nackLen, MSG_DONTWAIT, reinterpret_cast<const struct sockaddr*>(&remoteServerAddr), remoteServerAddrLen);
                }
            }
        );
    } else if (parsed.payloadPtr && parsed.payloadSize > 0) {
        auAssembler.feedRtpPayload(
            parsed.payloadPtr,
            parsed.payloadSize,
            static_cast<uint32_t>((adjustedPts * 90000ULL) / 1000000ULL),
            parsed.marker,
            streamEngine
        );
    }
}

void UnifiedZeroCopyStreamReactor::processRtcpPacket(const uint8_t* buf, size_t len) {
    // Manejar RTCP Feedback (PLI, SLI, RERI, REMB)
    // Azure puede enviar REMB para limitar bitrate
}

// Implementación de BIO para OpenSSL sobre el socket UDP del Reactor
// (Omitida por brevedad en este paso, pero necesaria para un handshake real)
bool UnifiedZeroCopyStreamReactor::setupDtls(const Configuration& config) {
#ifdef GM_HAS_OPENSSL
    SSL_library_init();
    SSL_load_error_strings();
    OpenSSL_add_all_algorithms();

    sslCtx = SSL_CTX_new(DTLS_client_method());
    if (!sslCtx) return false;

    // 1. Aplicar Certificate Pinning y preparar ECH
    GodMode::Network::ZeroTrustTlsConfiguration::applyPinnedCertificates(sslCtx);

    // 2. Configurar perfiles SRTP permitidos (AES128_CM_HMAC_SHA1_80)
    SSL_CTX_set_tlsext_use_srtp(sslCtx, "SRTP_AES128_CM_HMAC_SHA1_80");

    ssl = SSL_new(sslCtx);
    if (!ssl) return false;

    SSL_set_connect_state(ssl);
    
    // Aquí se vincularía el BIO personalizado que lee/escribe del socketFd
    // bio = BIO_new(create_my_custom_udp_bio(socket->getFd()));
    // SSL_set_bio(ssl, bio, bio);

    return true;
#else
    return true; // Bypass
#endif
}

void UnifiedZeroCopyStreamReactor::injectDirectSrtpKeys(const uint8_t* key, size_t keyLen, const uint8_t* salt, size_t saltLen) {
    if (key && keyLen >= 16) {
        std::memcpy(serverMasterKey, key, 16);
    }
    if (salt && saltLen >= 14) {
        std::memcpy(serverMasterSalt, salt, 14);
    }
    srtpKeysReady = true;
    if (iceInjector) {
        iceInjector->injectDirectMasterKeys(key, keyLen, salt, saltLen);
    }
    GM_LOGI("UnifiedZeroCopyStreamReactor: Direct SRTP Master Keys configured successfully.");
}

} // namespace GodMode::Stream

