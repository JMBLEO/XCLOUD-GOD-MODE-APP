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

namespace GodMode::Stream {

enum class StreamCodecProfile {
    H264_HIGH_SVC,
    HEVC_MAIN10,
    AV1_MAIN,
    VVC_MAIN,
    AV2_EXPERIMENTAL,
    AVS3,
    JPEG_XS,
    PYROWAVE_VULKAN,
    MPEG5_LCEVC,
    MPEG5_EVC,
    APPLE_PRORES_422
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

    uint32_t getWidth() const { return currentWidth; }
    uint32_t getHeight() const { return currentHeight; }

private:
    static uint64_t nowMicros();
};

} // namespace GodMode::Stream
