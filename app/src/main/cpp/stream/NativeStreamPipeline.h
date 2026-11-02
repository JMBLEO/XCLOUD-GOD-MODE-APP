#pragma once

#include "RtpDepacketizer.h"
#include "AdaptiveGeoJitterBuffer.h"
#include <android/native_window.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <memory>
#include <string>
#include <atomic>
#include <chrono>
#include <vector>
#include <array>
#include <utility>
#include <EGL/egl.h>
#include <GLES3/gl32.h>

namespace GodMode::Stream {

enum class StreamCodecProfile {
    H264_HIGH_SVC = 0,
    HEVC_MAIN10 = 1,
    AV1_MAIN = 2,
    VVC_MAIN = 3,
    AV2_EXPERIMENTAL = 4,
    AVS3 = 5,
    JPEG_XS = 6,
    PYROWAVE_VULKAN = 7,
    MPEG5_LCEVC = 8,
    MPEG5_EVC = 9,
    APPLE_PRORES_422 = 10
};

struct CodecCapability {
    StreamCodecProfile profile;
    bool hardwareSupported;
    std::string mimeType;
};

class CodecCapabilityNegotiator {
public:
    static std::vector<CodecCapability> detectSupportedCodecs();
    static bool isCodecSupported(const std::string& mime);
};

struct FrameTelemetry {
    uint32_t serverDataKey;
    uint64_t firstPacketArrivalUs;
    uint64_t frameSubmittedUs;
    uint64_t frameDecodedUs;
    uint64_t frameRenderedUs;
};

// 1D Linear Kalman Filter for smooth mouse aiming and telemetry estimation
class LinearKalmanFilter1D {
private:
    float state = 0.0f;
    float p = 1.0f;    // estimation error covariance
    float q = 0.005f;  // process noise covariance
    float r = 0.035f;  // measurement noise covariance
    float k = 0.0f;    // kalman gain

public:
    LinearKalmanFilter1D(float processNoise = 0.005f, float measurementNoise = 0.035f)
        : q(processNoise), r(measurementNoise) {}

    float update(float measurement) {
        // Prediction step
        p = p + q;
        // Update step
        k = p / (p + r);
        state = state + k * (measurement - state);
        p = (1.0f - k) * p;
        return state;
    }

    void reset(float initialValue = 0.0f) {
        state = initialValue;
        p = 1.0f;
    }
};

// Lock-Free SPSC (Single Producer, Single Consumer) Ring Buffer for 250Hz input polling
template<typename T, size_t Capacity>
class LockFreeSpscRingBuffer {
    static_assert((Capacity & (Capacity - 1)) == 0, "Capacity must be a power of two");
private:
    alignas(64) std::atomic<size_t> head{0}; // Producer writes
    alignas(64) std::atomic<size_t> tail{0}; // Consumer reads
    std::array<T, Capacity> buffer{};

public:
    bool push(const T& item) {
        const size_t currentHead = head.load(std::memory_order_relaxed);
        const size_t currentTail = tail.load(std::memory_order_acquire);
        if ((currentHead - currentTail) >= Capacity) {
            return false; // Buffer full
        }
        buffer[currentHead & (Capacity - 1)] = item;
        head.store(currentHead + 1, std::memory_order_release);
        return true;
    }

    bool pop(T& item) {
        const size_t currentTail = tail.load(std::memory_order_relaxed);
        const size_t currentHead = head.load(std::memory_order_acquire);
        if (currentTail == currentHead) {
            return false; // Buffer empty
        }
        item = buffer[currentTail & (Capacity - 1)];
        tail.store(currentTail + 1, std::memory_order_release);
        return true;
    }

    size_t size() const {
        const size_t currentTail = tail.load(std::memory_order_relaxed);
        const size_t currentHead = head.load(std::memory_order_relaxed);
        return (currentHead >= currentTail) ? (currentHead - currentTail) : 0;
    }

    void clear() {
        head.store(0, std::memory_order_relaxed);
        tail.store(0, std::memory_order_relaxed);
    }
};

struct NativeRawInputPacket {
    uint8_t data[64];
    size_t size;
    uint64_t timestampUs;
};

class NativeZeroCopyPipeline {
private:
    AMediaCodec* mediaCodec = nullptr;
    ANativeWindow* outputWindow = nullptr;
    StreamCodecProfile activeCodec = StreamCodecProfile::H264_HIGH_SVC;
    uint32_t currentWidth = 1920;
    uint32_t currentHeight = 1080;
    std::atomic<bool> isRunning{false};
    std::chrono::steady_clock::time_point lastPliSentTime;

    std::unique_ptr<RtpDepacketizer> depacketizer;
    std::unique_ptr<AdaptiveGeoJitterBuffer> jitterBuffer;

    LinearKalmanFilter1D mouseKalmanX{0.008f, 0.035f};
    LinearKalmanFilter1D mouseKalmanY{0.008f, 0.035f};
    LockFreeSpscRingBuffer<NativeRawInputPacket, 256> inputRingBuffer;

    // GLES 3.2 Compute Pipeline for FSR 4.1 Upscaling
    EGLDisplay eglDisplay = EGL_NO_DISPLAY;
    EGLContext eglContext = EGL_NO_CONTEXT;
    EGLSurface eglSurface = EGL_NO_SURFACE;
    GLuint computeProgram = 0;
    GLuint inputTexture = 0;
    GLuint outputTexture = 0;
    GLuint paramsUbo = 0;
    bool isGlesInitialized = false;

    void initGlesPipeline();
    void dispatchUpscale(uint32_t inW, uint32_t inH, uint32_t outW, uint32_t outH);
    GLuint loadComputeShader(const char* source);

public:
    NativeZeroCopyPipeline();
    ~NativeZeroCopyPipeline();

    static std::string rewriteSdpForUltraQuality(const std::string& rawSdp, uint32_t maxBitrateKbps);

    bool initializeHardwareDecoder(ANativeWindow* window, StreamCodecProfile profile, uint32_t width, uint32_t height);
    
    bool injectAccessUnitZeroCopy(const uint8_t* auBuffer, size_t auSize, uint32_t serverDataKey,
                                  float packetLossPct, FrameTelemetry& outTelemetry);

    bool shouldDropScalableEnhancementLayer(const uint8_t* nalData, size_t nalSize, float currentPacketLossPct);

    bool canRequestEmergencyKeyframe();

    void shutdown();

    std::atomic<int64_t> ptsOffsetUs{0};

    // Mouse aiming Kalman filter
    std::pair<float, float> applyKalmanSmoothing(float rawDeltaX, float rawDeltaY);

    // Lock-free input ring buffer methods
    bool pushRawInputPacket(const uint8_t* data, size_t size);
    bool popRawInputPacket(uint8_t* outData, size_t& outSize);

    // Bluetooth A/V sync compensation
    void setPtsOffsetUs(int64_t offsetUs) { ptsOffsetUs.store(offsetUs, std::memory_order_release); }

    // EGL fence sync & lifecycle helper
    static void cleanupEglSurfaceBinding();

    // Extensión 10: Zero-Trace Execution Audit & Memory Sanitization
    void zeroTraceShutdown();

    uint32_t getWidth() const { return currentWidth; }
    uint32_t getHeight() const { return currentHeight; }

    // VSYNC AChoreographer Integration
    void startVsyncPacing();
    void stopVsyncPacing();
    void onVsyncTick(long frameTimeNanos);
    void drainAndPresentReadyBuffers();

private:
    std::atomic<bool> isVsyncActive{false};
    static uint64_t nowMicros();
};

} // namespace GodMode::Stream
