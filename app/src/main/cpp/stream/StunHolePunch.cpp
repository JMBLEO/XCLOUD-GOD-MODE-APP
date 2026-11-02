#include <jni.h>
#include <string>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <cstdlib>
#include <android/log.h>

#define LOG_TAG "StunHolePunch"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#pragma pack(push, 1)
struct StunHeader {
    uint16_t msgType;
    uint16_t msgLength;
    uint32_t magicCookie;
    uint8_t transactionId[12];
};
#pragma pack(pop)

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xcloud_godmode_network_StunHolePunch_nativeSendStunBindingRequest(
        JNIEnv* env, jobject thiz, jstring host, jint port) {
    const char* nativeHost = env->GetStringUTFChars(host, 0);
    
    int sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock < 0) {
        LOGE("Failed to create UDP socket for STUN");
        env->ReleaseStringUTFChars(host, nativeHost);
        return JNI_FALSE;
    }

    sockaddr_in destAddr{};
    destAddr.sin_family = AF_INET;
    destAddr.sin_port = htons(port);
    inet_pton(AF_INET, nativeHost, &destAddr.sin_addr);

    StunHeader req{};
    req.msgType = htons(0x0001); // Binding Request
    req.msgLength = htons(0);
    req.magicCookie = htonl(0x2112A442);
    for (int i = 0; i < 12; i++) {
        req.transactionId[i] = (uint8_t)(rand() % 256);
    }

    ssize_t sent = sendto(sock, &req, sizeof(req), 0, (struct sockaddr*)&destAddr, sizeof(destAddr));
    close(sock);
    env->ReleaseStringUTFChars(host, nativeHost);

    if (sent == sizeof(req)) {
        LOGI("STUN Binding Request successfully sent to %s:%d", nativeHost, port);
        return JNI_TRUE;
    } else {
        LOGE("Failed to send STUN Binding Request");
        return JNI_FALSE;
    }
}
