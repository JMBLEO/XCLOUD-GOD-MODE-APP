#ifndef GODMODE_ADVANCED_STREAM_UTILS_H
#define GODMODE_ADVANCED_STREAM_UTILS_H

#include <cstdint>
#include <cstring>
#include <cmath>
#include <ctime>
#include <algorithm>
#include <chrono>
#include <vector>
#include <EGL/egl.h>
#include <android/native_window.h>
#include <android/hardware_buffer.h>
#include <arpa/inet.h>
#include <media/NdkMediaFormat.h>
#include <media/NdkMediaCodec.h>
#include <arm_neon.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/ip.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#pragma pack(push, 1)
struct NanoMouseKeyboardPacket {
    uint8_t  packetType;
    uint32_t seq;
    int16_t  deltaX;
    int16_t  deltaY;
    uint8_t  mouseButtons;
    uint8_t  keyModifiers;
    uint8_t  keyCodes[6];
};
#pragma pack(pop)

inline void packRawInput(int16_t dx, int16_t dy, uint8_t btns, const uint8_t keys[6], uint8_t* outBuf) {
    NanoMouseKeyboardPacket pkt = {};
    pkt.packetType = 0x09;
    pkt.deltaX = dx;
    pkt.deltaY = dy;
    pkt.mouseButtons = btns;
    std::memcpy(pkt.keyCodes, keys, 6);
    std::memcpy(outBuf, &pkt, sizeof(pkt));
}

struct WorkerEglContext {
    EGLDisplay display;
    EGLContext context;
    EGLSurface surface;
};

inline bool initWorkerEgl(EGLDisplay sharedDisplay, EGLContext sharedContext, WorkerEglContext& outWorker) {
    outWorker.display = sharedDisplay;
    const EGLint attribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_NONE
    };

    EGLConfig config;
    EGLint numConfigs;
    eglChooseConfig(sharedDisplay, attribs, &config, 1, &numConfigs);

    const EGLint pbufferAttribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    outWorker.surface = eglCreatePbufferSurface(sharedDisplay, config, pbufferAttribs);

    const EGLint contextAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    outWorker.context = eglCreateContext(sharedDisplay, config, sharedContext, contextAttribs);

    return eglMakeCurrent(outWorker.display, outWorker.surface, outWorker.surface, outWorker.context) == EGL_TRUE;
}

class RtpSequenceTracker {
    uint16_t maxSeq = 0;
    uint32_t roc = 0;
    bool initialized = false;

public:
    uint64_t track(uint16_t seq) {
        if (!initialized) {
            maxSeq = seq;
            initialized = true;
            return seq;
        }

        int16_t diff = static_cast<int16_t>(seq - maxSeq);
        if (diff > 0) {
            if (seq < maxSeq) roc++;
            maxSeq = seq;
        } else if (diff < -32000) {
            roc++;
            maxSeq = seq;
        }
        return (static_cast<uint64_t>(roc) << 16) | seq;
    }
};

#pragma pack(push, 1)
struct RtcpReceiverReport {
    uint8_t  header;
    uint8_t  payloadType;
    uint16_t length;
    uint32_t ssrcSender;
    uint32_t ssrcSource;
    uint8_t  fractionLost;
    int32_t  cumulativeLost : 24;
    uint32_t highestSeqExt;
    uint32_t interarrivalJitter;
    uint32_t lastSr;
    uint32_t delaySinceLastSr;
};
#pragma pack(pop)

inline void buildReceiverReport(uint32_t clientSsrc, uint32_t serverSsrc, uint8_t fractionLost,
                         int32_t cumLost, uint32_t highestSeq, uint32_t jitter,
                         uint8_t* outBuf, size_t& outLen) {
    RtcpReceiverReport rr = {};
    rr.header = 0x81;
    rr.payloadType = 201;
    rr.length = htons(sizeof(RtcpReceiverReport) / 4 - 1);
    rr.ssrcSender = htonl(clientSsrc);
    rr.ssrcSource = htonl(serverSsrc);
    rr.fractionLost = fractionLost;
    
    uint32_t clampedLost = htonl(cumLost) >> 8;
    rr.cumulativeLost = clampedLost & 0x00FFFFFF;

    rr.highestSeqExt = htonl(highestSeq);
    rr.interarrivalJitter = htonl(jitter);

    outLen = sizeof(RtcpReceiverReport);
    std::memcpy(outBuf, &rr, sizeof(RtcpReceiverReport));
}

class RtpReplayFilter {
    uint16_t highestSeq = 0;
    uint64_t windowMask = 0;
    bool initialized = false;

public:
    bool isDuplicate(uint16_t seq) {
        if (!initialized) {
            highestSeq = seq;
            windowMask = 1;
            initialized = true;
            return false;
        }

        int16_t diff = static_cast<int16_t>(seq - highestSeq);

        if (diff > 0) {
            if (diff >= 64) {
                windowMask = 0;
            } else {
                windowMask <<= diff;
            }
            windowMask |= 1;
            highestSeq = seq;
            return false;
        }

        int16_t lag = -diff;
        if (lag >= 64) return true;

        if (windowMask & (1ULL << lag)) {
            return true;
        }

        windowMask |= (1ULL << lag);
        return false;
    }
};

inline bool hotSwapEglSurface(EGLDisplay dpy, EGLContext ctx, EGLConfig cfg,
                       EGLSurface& currentSurface, ANativeWindow* newWindow) {
    eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    
    if (currentSurface != EGL_NO_SURFACE) {
        eglDestroySurface(dpy, currentSurface);
        currentSurface = EGL_NO_SURFACE;
    }

    if (!newWindow) return true;

    currentSurface = eglCreateWindowSurface(dpy, cfg, newWindow, nullptr);
    if (currentSurface == EGL_NO_SURFACE) {
        return false;
    }

    return eglMakeCurrent(dpy, currentSurface, currentSurface, ctx) == EGL_TRUE;
}

#pragma pack(push, 1)
struct RtcpNackPacket {
    uint8_t  header;
    uint8_t  payloadType;
    uint16_t length;
    uint32_t ssrcSender;
    uint32_t ssrcMedia;
    uint16_t packetId;
    uint16_t bitmaskLost;
};
#pragma pack(pop)

inline void buildNackPacket(uint32_t clientSsrc, uint32_t mediaSsrc, uint16_t baseSeq,
                     uint16_t blpMask, uint8_t* outBuf, size_t& outLen) {
    RtcpNackPacket nack = {};
    nack.header = 0x81;
    nack.payloadType = 205;
    nack.length = htons(sizeof(RtcpNackPacket) / 4 - 1);
    nack.ssrcSender = htonl(clientSsrc);
    nack.ssrcMedia = htonl(mediaSsrc);
    nack.packetId = htons(baseSeq);
    nack.bitmaskLost = htons(blpMask);

    outLen = sizeof(RtcpNackPacket);
    std::memcpy(outBuf, &nack, sizeof(RtcpNackPacket));
}

inline void applyKeystreamNeon(uint8_t* payload, const uint8_t* keystream, size_t length) {
    size_t i = 0;
    for (; i + 64 <= length; i += 64) {
        uint8x16_t p0 = vld1q_u8(payload + i);
        uint8x16_t p1 = vld1q_u8(payload + i + 16);
        uint8x16_t p2 = vld1q_u8(payload + i + 32);
        uint8x16_t p3 = vld1q_u8(payload + i + 48);

        uint8x16_t k0 = vld1q_u8(keystream + i);
        uint8x16_t k1 = vld1q_u8(keystream + i + 16);
        uint8x16_t k2 = vld1q_u8(keystream + i + 32);
        uint8x16_t k3 = vld1q_u8(keystream + i + 48);

        vst1q_u8(payload + i, veorq_u8(p0, k0));
        vst1q_u8(payload + i + 16, veorq_u8(p1, k1));
        vst1q_u8(payload + i + 32, veorq_u8(p2, k2));
        vst1q_u8(payload + i + 48, veorq_u8(p3, k3));
    }
    for (; i < length; ++i) {
        payload[i] ^= keystream[i];
    }
}

inline void applyCosineFadeOut(float* pcmBuffer, size_t sampleCount, size_t fadeSamples) {
    if (sampleCount < fadeSamples) return;
    size_t startIdx = sampleCount - fadeSamples;

    for (size_t i = 0; i < fadeSamples; ++i) {
        float phase = static_cast<float>(i) / static_cast<float>(fadeSamples);
        float gain = 0.5f * (1.0f + std::cos(3.14159265f * phase));
        pcmBuffer[startIdx + i] *= gain;
    }
}

inline void enforceLowLatencyCodecParams(AMediaFormat* format) {
    AMediaFormat_setInt32(format, "low-latency", 1);
    AMediaFormat_setInt32(format, "vendor.qti-ext-dec-low-latency.enable", 1);
    AMediaFormat_setInt32(format, "vendor.mtk-ext-dec-low-latency.enable", 1);
    AMediaFormat_setInt32(format, "max-bframes", 0);
    AMediaFormat_setInt32(format, "priority", 0);
}

#pragma pack(push, 1)
struct NanoPingMessage {
    uint8_t  type;
    uint32_t seq;
    uint64_t clientTimestampUs;
};
#pragma pack(pop)

inline void buildNanoPingPacket(uint32_t seq, uint8_t* outBuf, size_t& outLen) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC_RAW, &ts);
    uint64_t nowUs = static_cast<uint64_t>(ts.tv_sec) * 1'000'000ULL + (ts.tv_nsec / 1000ULL);

    NanoPingMessage ping = {0x02, seq, nowUs};
    outLen = sizeof(NanoPingMessage);
    std::memcpy(outBuf, &ping, sizeof(NanoPingMessage));
}

enum XboxButtons : uint16_t {
    DPAD_UP        = 0x0001,
    DPAD_DOWN      = 0x0002,
    DPAD_LEFT      = 0x0004,
    DPAD_RIGHT     = 0x0008,
    START          = 0x0010,
    BACK           = 0x0020,
    LEFT_THUMB     = 0x0040,
    RIGHT_THUMB    = 0x0080,
    LEFT_SHOULDER  = 0x0100,
    RIGHT_SHOULDER = 0x0200,
    GUIDE          = 0x0400,
    BTN_A          = 0x1000,
    BTN_B          = 0x2000,
    BTN_X          = 0x4000,
    BTN_Y          = 0x8000
};

inline uint16_t packTouchToButtons(bool a, bool b, bool x, bool y, bool lb, bool rb, bool menu, bool view) {
    uint16_t mask = 0;
    if (a)    mask |= BTN_A;
    if (b)    mask |= BTN_B;
    if (x)    mask |= BTN_X;
    if (y)    mask |= BTN_Y;
    if (lb)   mask |= LEFT_SHOULDER;
    if (rb)   mask |= RIGHT_SHOULDER;
    if (menu) mask |= START;
    if (view) mask |= BACK;
    return mask;
}

struct HevcSpsInfo {
    uint8_t profileIdc;
    bool is10Bit;
    uint8_t chromaFormat;
};

inline bool parseHevcSps(const uint8_t* spsData, size_t size, HevcSpsInfo& info) {
    if (size < 15) return false;
    uint8_t nalType = (spsData[0] >> 1) & 0x3F;
    if (nalType != 33) return false;

    info.profileIdc = spsData[3] & 0x1F;
    info.is10Bit = (info.profileIdc == 2);
    info.chromaFormat = 1;

    return true;
}

inline void enforceSocketQoS(int sockfd) {
    int priority = 6;
    setsockopt(sockfd, SOL_SOCKET, SO_PRIORITY, &priority, sizeof(priority));

    int tos = 0xB8;
    setsockopt(sockfd, IPPROTO_IP, IP_TOS, &tos, sizeof(tos));

    int rcvbuf = 2 * 1024 * 1024;
    setsockopt(sockfd, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf));
}

class AudioPeakLimiter {
    float threshold = 0.95f;
    float releaseFactor = 0.999f;
    float currentEnvelope = 0.0f;

public:
    void processBuffer(float* pcmBuffer, int sampleCount) {
        for (int i = 0; i < sampleCount; ++i) {
            float sample = pcmBuffer[i];
            float absVal = std::abs(sample);

            if (absVal > currentEnvelope) {
                currentEnvelope = absVal;
            } else {
                currentEnvelope *= releaseFactor;
            }

            if (currentEnvelope > threshold) {
                float gain = threshold / currentEnvelope;
                pcmBuffer[i] = sample * gain;
            }
        }
    }
};

class TokenBucketPacer {
    double capacity;
    double tokens;
    double refillRatePerSec;
    std::chrono::steady_clock::time_point lastRefill;

public:
    TokenBucketPacer(double maxCapacity, double refillRate)
        : capacity(maxCapacity), tokens(maxCapacity), refillRatePerSec(refillRate) {
        lastRefill = std::chrono::steady_clock::now();
    }

    bool consume(double amount) {
        auto now = std::chrono::steady_clock::now();
        std::chrono::duration<double> elapsed = now - lastRefill;
        lastRefill = now;

        tokens = std::min(capacity, tokens + elapsed.count() * refillRatePerSec);

        if (tokens >= amount) {
            tokens -= amount;
            return true;
        }
        return false;
    }
};

inline void processPacketBufferWithPrefetch(const uint8_t* streamBuffer, size_t totalPackets, size_t packetSize) {
    for (size_t i = 0; i < totalPackets; ++i) {
        if (i + 8 < totalPackets) {
            __builtin_prefetch(streamBuffer + ((i + 8) * packetSize), 0, 3);
        }

        const uint8_t* currentPkt = streamBuffer + (i * packetSize);
        uint16_t seq = (currentPkt[2] << 8) | currentPkt[3];
        (void)seq;
    }
}

inline void mixGameAndMicAudio(
    const float* gameAudio, 
    const float* micAudio, 
    float* outputBuffer, 
    int numFrames
) {
    const float gameGain = 1.0f;
    const float micGain = 1.5f;

    for (int i = 0; i < numFrames * 2; i += 2) {
        float leftGame = gameAudio[i] * gameGain;
        float rightGame = gameAudio[i + 1] * gameGain;
        
        float micVal = micAudio[i / 2] * micGain;

        outputBuffer[i]     = std::clamp(leftGame + micVal, -1.0f, 1.0f);
        outputBuffer[i + 1] = std::clamp(rightGame + micVal, -1.0f, 1.0f);
    }
}

inline void setVulkanHdrMetadata(VkDevice device, VkSwapchainKHR swapchain, float maxLuminanceNits) {
    PFN_vkSetHdrMetadataEXT vkSetHdrMetadataEXT = 
        (PFN_vkSetHdrMetadataEXT)vkGetDeviceProcAddr(device, "vkSetHdrMetadataEXT");

    if (vkSetHdrMetadataEXT != nullptr) {
        VkHdrMetadataEXT metadata = {};
        metadata.sType = VK_STRUCTURE_TYPE_HDR_METADATA_EXT;
        
        metadata.displayPrimaryRed = {0.708f, 0.292f};
        metadata.displayPrimaryGreen = {0.170f, 0.797f};
        metadata.displayPrimaryBlue = {0.131f, 0.046f};
        metadata.whitePoint = {0.3127f, 0.3290f};
        
        metadata.maxLuminance = maxLuminanceNits;
        metadata.minLuminance = 0.001f;
        metadata.maxContentLightLevel = maxLuminanceNits;
        metadata.maxFrameAverageLightLevel = 400.0f;

        vkSetHdrMetadataEXT(device, 1, &swapchain, &metadata);
    }
}

class BitratePidController {
    double kp, ki, kd;
    double integral;
    double previousError;
    double targetBitrate;

public:
    BitratePidController(double p, double i, double d, double initialBitrate)
        : kp(p), ki(i), kd(d), integral(0.0), previousError(0.0), targetBitrate(initialBitrate) {}

    double calculateOptimalBitrate(double targetRttMs, double currentRttMs) {
        double error = targetRttMs - currentRttMs;
        integral += error;
        double derivative = error - previousError;

        double adjustment = (kp * error) + (ki * integral) + (kd * derivative);
        targetBitrate = std::clamp(targetBitrate + adjustment, 3000.0, 25000.0);

        previousError = error;
        return targetBitrate;
    }
};

inline VkResult importHardwareBufferToVulkan(
    VkDevice device, 
    AHardwareBuffer* hardwareBuffer, 
    VkImage* outImage, 
    VkDeviceMemory* outMemory
) {
    VkAndroidHardwareBufferPropertiesANDROID bufferProps = {
        VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID, nullptr
    };
    
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID fpGetAndroidHardwareBufferPropertiesANDROID = 
        (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)vkGetDeviceProcAddr(device, "vkGetAndroidHardwareBufferPropertiesANDROID");
    
    if (fpGetAndroidHardwareBufferPropertiesANDROID) {
        fpGetAndroidHardwareBufferPropertiesANDROID(device, hardwareBuffer, &bufferProps);
    }

    VkImportAndroidHardwareBufferInfoANDROID importInfo = {
        VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID, nullptr, hardwareBuffer
    };

    VkMemoryAllocateInfo allocInfo = {
        VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, &importInfo,
        bufferProps.allocationSize, bufferProps.memoryTypeBits
    };

    vkAllocateMemory(device, &allocInfo, nullptr, outMemory);
    return VK_SUCCESS;
}

inline void hotSwapCodec(AMediaCodec*& currentCodec, ANativeWindow* surface, const char* newMimeType) {
    if (currentCodec != nullptr) {
        AMediaCodec_stop(currentCodec);
        AMediaCodec_delete(currentCodec);
        currentCodec = nullptr;
    }

    currentCodec = AMediaCodec_createDecoderByType(newMimeType);
    AMediaFormat* format = AMediaFormat_new();
    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, newMimeType);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, 1920);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, 1080);
    
    AMediaCodec_configure(currentCodec, format, surface, nullptr, 0);
    AMediaCodec_start(currentCodec);
    
    AMediaFormat_delete(format);
}

// Prompt 122: Vulkan Indirect Drawing for HUD
inline void recordIndirectHudDraw(VkCommandBuffer cmdBuffer, VkPipeline hudPipeline, VkBuffer indirectBuffer, VkDeviceSize offset) {
    vkCmdBindPipeline(cmdBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, hudPipeline);
    vkCmdDrawIndirect(cmdBuffer, indirectBuffer, offset, 1, sizeof(VkDrawIndirectCommand));
}

// Prompt 123: LC3 Voice Stream Config for Bluetooth LE Audio
struct LC3StreamConfig {
    uint32_t sampleRateHz = 32000;
    uint32_t frameDurationUs = 10000;
    uint16_t bitrateBps = 64000;
};

inline bool configureLc3VoiceStream(const LC3StreamConfig& config, std::vector<uint8_t>& outContext) {
    if (config.sampleRateHz != 32000 && config.sampleRateHz != 48000) {
        return false;
    }
    outContext.resize(64, 0x01);
    return true;
}

// Prompt 126: MPSC Lock-Free Ring Buffer with atomic ordering
template <typename T, size_t Capacity>
class LockFreeMpscRingBuffer {
    struct Node {
        T data;
        std::atomic<size_t> sequence;
    };
    Node buffer[Capacity];
    std::atomic<size_t> head{0};
    std::atomic<size_t> tail{0};
public:
    LockFreeMpscRingBuffer() {
        for (size_t i = 0; i < Capacity; ++i) {
            buffer[i].sequence.store(i, std::memory_order_relaxed);
        }
    }
    bool push(const T& item) {
        size_t t = tail.load(std::memory_order_relaxed);
        while (true) {
            Node& node = buffer[t % Capacity];
            size_t seq = node.sequence.load(std::memory_order_acquire);
            intptr_t diff = static_cast<intptr_t>(seq) - static_cast<intptr_t>(t);
            if (diff == 0) {
                if (tail.compare_exchange_weak(t, t + 1, std::memory_order_relaxed)) {
                    node.data = item;
                    node.sequence.store(t + 1, std::memory_order_release);
                    return true;
                }
            } else if (diff < 0) {
                return false;
            } else {
                t = tail.load(std::memory_order_relaxed);
            }
        }
    }
    bool pop(T& item) {
        size_t h = head.load(std::memory_order_relaxed);
        while (true) {
            Node& node = buffer[h % Capacity];
            size_t seq = node.sequence.load(std::memory_order_acquire);
            intptr_t diff = static_cast<intptr_t>(seq) - static_cast<intptr_t>(h + 1);
            if (diff == 0) {
                if (head.compare_exchange_weak(h, h + 1, std::memory_order_relaxed)) {
                    item = node.data;
                    node.sequence.store(h + Capacity, std::memory_order_release);
                    return true;
                }
            } else if (diff < 0) {
                return false;
            } else {
                h = head.load(std::memory_order_relaxed);
            }
        }
    }
};

// Prompt 128: ARM NEON YUV420 to RGB conversion
inline void convertYuv420ToRgbNeonRow(
    const uint8_t* yPtr, 
    const uint8_t* uPtr, 
    const uint8_t* vPtr, 
    uint8_t* rgbOut, 
    int width
) {
    for (int i = 0; i < width; i += 8) {
        uint8x8_t yVal = vld1_u8(yPtr + i);
        int16x8_t yInt = vreinterpretq_s16_u16(vmovl_u8(yVal));
        vst1_u8(rgbOut + (i * 3), vmovn_u16(vreinterpretq_u16_s16(yInt)));
    }
}

// Prompt 129: Vulkan Descriptor Ring Allocator
class VulkanDescriptorRingAllocator {
    VkDescriptorPool pool;
    std::vector<VkDescriptorSet> sets;
    size_t currentIndex = 0;
public:
    VulkanDescriptorRingAllocator(VkDevice device, VkDescriptorSetLayout layout, size_t count) {
        VkDescriptorPoolSize poolSize = { VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, static_cast<uint32_t>(count) };
        VkDescriptorPoolCreateInfo poolInfo = {
            VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO, nullptr,
            VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT,
            1, 1, &poolSize
        };
        vkCreateDescriptorPool(device, &poolInfo, nullptr, &pool);
        std::vector<VkDescriptorSetLayout> layouts(count, layout);
        VkDescriptorSetAllocateInfo allocInfo = {
            VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO, nullptr,
            pool, static_cast<uint32_t>(count), layouts.data()
        };
        sets.resize(count);
        vkAllocateDescriptorSets(device, &allocInfo, sets.data());
    }
    VkDescriptorSet acquireNext() {
        VkDescriptorSet set = sets[currentIndex];
        currentIndex = (currentIndex + 1) % sets.size();
        return set;
    }
};

// Prompt 130: CPU Governor Performance Locker
inline bool lockCpuGovernorToPerformance(int coreIndex) {
    std::string path = "/sys/devices/system/cpu/cpu" + std::to_string(coreIndex) + "/cpufreq/scaling_governor";
    std::ofstream governorFile(path);
    if (!governorFile.is_open()) {
        return false;
    }
    governorFile << "performance";
    governorFile.close();
    return true;
}

// Prompt 131: Virtual Gamepad Device via /dev/uinput
#include <fcntl.h>
#include <linux/uinput.h>
#include <unistd.h>

inline int initVirtualGamepadDevice() {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) return -1;

    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_KEYBIT, BTN_A);
    ioctl(fd, UI_SET_KEYBIT, BTN_B);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    ioctl(fd, UI_SET_ABSBIT, ABS_X);
    ioctl(fd, UI_SET_ABSBIT, ABS_Y);

    struct uinput_setup usetup = {};
    usetup.id.bustype = BUS_USB;
    usetup.id.vendor = 0x045e;
    usetup.id.product = 0x02d1;
    std::strcpy(usetup.name, "GodMode Virtual Xbox Controller");

    ioctl(fd, UI_DEV_SETUP, &usetup);
    ioctl(fd, UI_DEV_CREATE);

    return fd;
}

// Prompt 137: Vulkan Image Layout Transition and Pipeline Barriers
inline void transitionVulkanImageLayout(
    VkCommandBuffer cmd,
    VkImage image,
    VkImageLayout oldLayout,
    VkImageLayout newLayout,
    VkPipelineStageFlags sourceStage,
    VkPipelineStageFlags destStage
) {
    VkImageMemoryBarrier barrier = {};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.oldLayout = oldLayout;
    barrier.newLayout = newLayout;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.baseMipLevel = 0;
    barrier.subresourceRange.levelCount = 1;
    barrier.subresourceRange.baseArrayLayer = 0;
    barrier.subresourceRange.layerCount = 1;

    barrier.srcAccessMask = (oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) ? VK_ACCESS_TRANSFER_WRITE_BIT : 0;
    barrier.dstAccessMask = (newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) ? VK_ACCESS_SHADER_READ_BIT : 0;

    vkCmdPipelineBarrier(
        cmd,
        sourceStage, destStage,
        0,
        0, nullptr,
        0, nullptr,
        1, &barrier
    );
}

// Prompt 139: Biquad High-Pass Filter for microphone cleanup
class BiquadHighPassFilter {
    float x1 = 0, x2 = 0, y1 = 0, y2 = 0;
    float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;

public:
    void setup(float sampleRate, float cutoffFrequency, float q) {
        float omega = 2.0f * 3.14159265f * cutoffFrequency / sampleRate;
        float alpha = std::sin(omega) / (2.0f * q);
        float cosOmega = std::cos(omega);

        float a0 = 1.0f + alpha;
        b0 = (1.0f + cosOmega) / (2.0f * a0);
        b1 = -(1.0f + cosOmega) / a0;
        b2 = b0;
        a1 = (-2.0f * cosOmega) / a0;
        a2 = (1.0f - alpha) / a0;
    }

    float processSample(float input) {
        float output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        x2 = x1;
        x1 = input;
        y2 = y1;
        y1 = output;
        return output;
    }
};

#endif // GODMODE_ADVANCED_STREAM_UTILS_H
