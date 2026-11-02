#include <sys/socket.h>
#include <unistd.h>
#include <cstdint>
#include <cstring>
#include <cstdio>

// Bluetooth L2CAP definitions for Linux/Android
// Note: These may require specific platform headers or manual definitions
#ifndef AF_BLUETOOTH
#define AF_BLUETOOTH 31
#endif

#ifndef BTPROTO_L2CAP
#define BTPROTO_L2CAP 0
#endif

struct sockaddr_l2 {
    uint16_t l2_family;
    uint16_t l2_psm;
    uint8_t  l2_bdaddr[6]; // bdaddr_t
    uint16_t l2_cid;
    uint8_t  l2_bdaddr_type;
};

#define htobs(d) (d) // Byte swap if needed

// Helper to convert MAC string to bdaddr_t bytes
void str2ba_internal(const char* macStr, uint8_t* ba) {
    int m[6];
    sscanf(macStr, "%02x:%02x:%02x:%02x:%02x:%02x", &m[5], &m[4], &m[3], &m[2], &m[1], &m[0]);
    for (int i = 0; i < 6; i++) ba[i] = (uint8_t)m[i];
}

int connectL2capGamepad(const char* macStr, uint16_t psm) {
    int sock = socket(AF_BLUETOOTH, SOCK_SEQPACKET, BTPROTO_L2CAP);
    if (sock < 0) return -1;

    struct sockaddr_l2 addr = {};
    addr.l2_family = AF_BLUETOOTH;
    str2ba_internal(macStr, addr.l2_bdaddr);
    addr.l2_psm = htobs(psm); // PSM 17 (Control) or 19 (Interrupt)

    if (connect(sock, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        close(sock);
        return -1;
    }

    return sock; // Ready for HID report reading
}
