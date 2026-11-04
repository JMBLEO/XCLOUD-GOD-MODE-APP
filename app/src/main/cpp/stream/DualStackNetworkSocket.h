#pragma once

#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstring>
#include <string>
#include <android/log.h>

namespace GodMode::Network {

class DualStackNetworkSocket {
private:
    int socketFd = -1;
    const char* TAG = "GodModeSocket";

public:
    DualStackNetworkSocket() = default;
    ~DualStackNetworkSocket() { shutdown(); }

    bool initialize(uint16_t localPort = 0) {
        // 1. Crear socket IPv6 (Dual-Stack)
        socketFd = socket(AF_INET6, SOCK_DGRAM | SOCK_NONBLOCK, IPPROTO_UDP);
        if (socketFd < 0) {
            __android_log_print(ANDROID_LOG_WARN, TAG, "IPv6 socket failed, falling back to IPv4");
            socketFd = socket(AF_INET, SOCK_DGRAM | SOCK_NONBLOCK, IPPROTO_UDP);
            if (socketFd < 0) return false;
        } else {
            // 2. Deshabilitar IPV6_V6ONLY para permitir tráfico mixto IPv4 mapeado en IPv6
            int off = 0;
            if (setsockopt(socketFd, IPPROTO_IPV6, IPV6_V6ONLY, &off, sizeof(off)) < 0) {
                __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to disable IPV6_V6ONLY");
            }
        }

        // 3. Configurar búfer de recepción masivo (8 MB para absorber picos 1440p/50Mbps)
        int rcvBuf = 8 * 1024 * 1024;
        setsockopt(socketFd, SOL_SOCKET, SO_RCVBUF, &rcvBuf, sizeof(rcvBuf));

        // 4. QoS: Marcar con DSCP EF (Expedited Forwarding = 0xB8) para prioridad WMM
        int tos = 0xB8;
        setsockopt(socketFd, IPPROTO_IP, IP_TOS, &tos, sizeof(tos));
        
        // Lo mismo para IPv6 (Traffic Class)
        int tclass = 0xB8;
        setsockopt(socketFd, IPPROTO_IPV6, IPV6_TCLASS, &tclass, sizeof(tclass));

        // 5. PMTU: Desactivar fragmentación IP local para evitar pérdida de tramas en tránsito
        int mtuDiscover = 1; // IP_PMTUDISC_DO
        setsockopt(socketFd, IPPROTO_IP, 10, &mtuDiscover, sizeof(mtuDiscover)); // 10 = IP_MTU_DISCOVER
        
        int mtuDiscoverV6 = 1; // IPV6_PMTUDISC_DO
        setsockopt(socketFd, IPPROTO_IPV6, 23, &mtuDiscoverV6, sizeof(mtuDiscoverV6)); // 23 = IPV6_MTU_DISCOVER

        // 6. Bind al puerto local si se especifica
        if (localPort > 0) {
            struct sockaddr_in6 addr{};
            addr.sin6_family = AF_INET6;
            addr.sin6_addr = in6addr_any;
            addr.sin6_port = htons(localPort);
            if (bind(socketFd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
                __android_log_print(ANDROID_LOG_ERROR, TAG, "Bind failed on port %d", localPort);
                return false;
            }
        }

        return true;
    }

    void shutdown() {
        if (socketFd >= 0) {
            ::shutdown(socketFd, SHUT_RDWR);
            close(socketFd);
            socketFd = -1;
        }
    }

    int getFd() const { return socketFd; }

    /**
     * Parsea candidatos ICE de Azure que contienen direcciones IPv6 o Teredo
     * para su uso inmediato en el reactor nativo.
     */
    static bool parseRemoteCandidateToSockaddr(const char* ip, uint16_t port, struct sockaddr_storage& outAddr, socklen_t& outLen) {
        std::memset(&outAddr, 0, sizeof(outAddr));

        // 1. Intentar parsear como IPv6 nativo o empaquetado Teredo (2001:0:...)
        struct sockaddr_in6* v6 = reinterpret_cast<struct sockaddr_in6*>(&outAddr);
        if (inet_pton(AF_INET6, ip, &(v6->sin6_addr)) == 1) {
            v6->sin6_family = AF_INET6;
            v6->sin6_port = htons(port);
            outLen = sizeof(struct sockaddr_in6);
            return true;
        }

        // 2. Fallback: Intentar parsear como IPv4 estándar
        struct sockaddr_in* v4 = reinterpret_cast<struct sockaddr_in*>(&outAddr);
        if (inet_pton(AF_INET, ip, &(v4->sin_addr)) == 1) {
            v4->sin_family = AF_INET;
            v4->sin_port = htons(port);
            outLen = sizeof(struct sockaddr_in);
            return true;
        }

        return false;
    }

    ssize_t sendTo(const void* data, size_t len, const char* ip, uint16_t port) {
        if (socketFd < 0) return -1;

        struct sockaddr_storage destAddr{};
        socklen_t addrLen = 0;

        if (!parseRemoteCandidateToSockaddr(ip, port, destAddr, addrLen)) {
            return -1;
        }

        return sendto(socketFd, data, len, MSG_DONTWAIT, (struct sockaddr*)&destAddr, addrLen);
    }
};

} // namespace GodMode::Network
