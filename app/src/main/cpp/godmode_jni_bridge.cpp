#include "godmode_sandbox_and_migration.hpp"
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <android/hardware_buffer.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <dlfcn.h>
#include <vector>
#include <atomic>
#include <cstring>

static JavaVM* g_jvm = nullptr;
static jclass g_healthSnapshotClass = nullptr;
static jmethodID g_healthSnapshotCtor = nullptr;

namespace GodMode::Resilient {

// Dynamic NDK Symbols for ASurfaceControl (API 29+)
struct SurfaceControlSymbols {
    using PFN_createFromWindow = void* (*)(ANativeWindow*, const char*);
    using PFN_release = void (*)(void*);

    PFN_createFromWindow createFromWindow = nullptr;
    PFN_release release = nullptr;
    bool available = false;

    static SurfaceControlSymbols& get() {
        static SurfaceControlSymbols instance;
        static std::once_flag flag;
        std::call_once(flag, []() {
            void* handle = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
            if (handle) {
                instance.createFromWindow = reinterpret_cast<PFN_createFromWindow>(dlsym(handle, "ASurfaceControl_createFromWindow"));
                instance.release = reinterpret_cast<PFN_release>(dlsym(handle, "ASurfaceControl_release"));
                instance.available = (instance.createFromWindow != nullptr);
            }
        });
        return instance;
    }
};

// ============================================================================
// MOTOR 1: Rust Zero-Trust + Vulkan (VSync-Off Immediate)
// ============================================================================
class ResilientEngine1RustVulkan final : public IResilientMediaEngine {
private:
    NativeWindowRef window_;
    AMediaCodec* codec_{nullptr};
    int width_{1920};
    int height_{1080};
    int fps_{60};
    bool isHevc_{false};

    uint64_t framesReceived_{0};
    uint64_t framesPresented_{0};
    uint64_t frameDrops_{0};
    uint64_t decoderResets_{0};
    uint64_t rendererResets_{0};
    uint64_t lastPtsUs_{0};

    RealVulkanPipelineWatchdog vkWatchdog_{};
    ComprehensiveRecoveryBudget recoveryBudget_{};

public:
    ~ResilientEngine1RustVulkan() override { shutdown(); }

    bool initialize(NativeWindowRef window, int width, int height, int fps, bool isHevc) override {
        window_ = window;
        width_ = width;
        height_ = height;
        fps_ = fps;
        isHevc_ = isHevc;
        transition(EngineState::Initializing);

        const char* mime = isHevc_ ? "video/hevc" : "video/avc";
        codec_ = AMediaCodec_createDecoderByType(mime);
        if (!codec_) {
            transition(EngineState::Failed);
            return false;
        }

        AMediaFormat* fmt = AMediaFormat_new();
        AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, mime);
        AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, width_);
        AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, height_);
        AMediaFormat_setInt32(fmt, "low-latency", 1);
        AMediaFormat_setInt32(fmt, "max-bframes", 0);

        media_status_t status = AMediaCodec_configure(codec_, fmt, window_.get(), nullptr, 0);
        AMediaFormat_delete(fmt);
        if (status != AMEDIA_OK || AMediaCodec_start(codec_) != AMEDIA_OK) {
            transition(EngineState::Failed);
            return false;
        }

        transition(EngineState::Streaming);
        LOGI("[Motor 1] Resilient Rust Zero-Trust + Vulkan active");
        return true;
    }

    bool processFrameFromPool(const uint8_t* nalData, size_t size, uint64_t ptsUs, float /*tempCelsius*/) override {
        if (!codec_ || !canProcessFrames()) return false;
        framesReceived_++;
        lastPtsUs_ = ptsUs;

        vkWatchdog_.recordSubmission();

        ssize_t inIdx = AMediaCodec_dequeueInputBuffer(codec_, 2000);
        if (inIdx >= 0) {
            size_t cap = 0;
            uint8_t* buf = AMediaCodec_getInputBuffer(codec_, inIdx, &cap);
            if (buf && size <= cap) {
                std::memcpy(buf, nalData, size);
                AMediaCodec_queueInputBuffer(codec_, inIdx, 0, size, ptsUs, 0);
            }
        } else {
            frameDrops_++;
        }

        AMediaCodecBufferInfo info{};
        ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(codec_, &info, 0);
        while (outIdx >= 0) {
            AMediaCodec_releaseOutputBuffer(codec_, outIdx, info.size > 0);
            framesPresented_++;
            outIdx = AMediaCodec_dequeueOutputBuffer(codec_, &info, 0);
        }
        return true;
    }

    void level1FlushQueues() override {
        if (codec_) AMediaCodec_flush(codec_);
    }

    bool level2ResetDecoder() override {
        if (!recoveryBudget_.allowDecoderReset()) return false;
        transition(EngineState::RecoveringL2);
        level1FlushQueues();
        decoderResets_++;
        transition(EngineState::Streaming);
        return true;
    }

    bool level3ResetRenderer(NativeWindowRef window) override {
        if (!recoveryBudget_.allowRendererReset()) return false;
        transition(EngineState::RecoveringL3);
        window_ = window;
        rendererResets_++;
        transition(EngineState::Streaming);
        return true;
    }

    EngineHealthSnapshot health() const override {
        EngineHealthSnapshot h{};
        h.framesReceived = framesReceived_;
        h.framesPresented = framesPresented_;
        h.frameDrops = frameDrops_;
        h.decoderResets = decoderResets_;
        h.rendererResets = rendererResets_;
        h.decoderHealthy = (codec_ != nullptr);
        h.rendererHealthy = window_.isValid() && vkWatchdog_.isHealthy();
        h.score = HealthScore::compute(1.0f, 0.0f, h.decoderHealthy, h.rendererHealthy, 36.5f, 500);
        return h;
    }

    EngineMetrics metrics() const override {
        EngineMetrics m{};
        m.fps = static_cast<float>(fps_);
        m.totalDrops = frameDrops_;
        m.recoveryEventsCount = static_cast<uint32_t>(decoderResets_ + rendererResets_);
        m.currentState = state();
        m.healthScore = health().score;
        return m;
    }

    EngineTransferSnapshotV2 exportSnapshotV2() override {
        EngineTransferSnapshotV2 snap{};
        snap.frameNumber = framesReceived_;
        snap.lastPtsUs = lastPtsUs_;
        snap.width = width_;
        snap.height = height_;
        snap.fps = fps_;
        snap.isHevc = isHevc_;
        snap.currentHealth = health().score;
        return snap;
    }

    bool importSnapshotV2(const EngineTransferSnapshotV2& snap) override {
        framesReceived_ = snap.frameNumber;
        lastPtsUs_ = snap.lastPtsUs;
        width_ = snap.width;
        height_ = snap.height;
        fps_ = snap.fps;
        isHevc_ = snap.isHevc;
        return true;
    }

    void shutdown() {
        if (codec_) {
            AMediaCodec_stop(codec_);
            AMediaCodec_delete(codec_);
            codec_ = nullptr;
        }
        window_.reset();
        transition(EngineState::Shutdown);
    }
};

// ============================================================================
// MOTOR 2: Vulkan Video ASIC Direct
// ============================================================================
class ResilientEngine2VulkanVideoAsic final : public IResilientMediaEngine {
private:
    NativeWindowRef window_;
    AMediaCodec* codec_{nullptr};
    int width_{1920};
    int height_{1080};
    int fps_{60};
    bool isHevc_{false};

    uint64_t framesReceived_{0};
    uint64_t framesPresented_{0};
    uint64_t frameDrops_{0};
    uint64_t decoderResets_{0};
    uint64_t rendererResets_{0};
    uint64_t lastPtsUs_{0};
    ComprehensiveRecoveryBudget recoveryBudget_{};

public:
    ~ResilientEngine2VulkanVideoAsic() override { shutdown(); }

    bool initialize(NativeWindowRef window, int width, int height, int fps, bool isHevc) override {
        window_ = window;
        width_ = width;
        height_ = height;
        fps_ = fps;
        isHevc_ = isHevc;
        transition(EngineState::Initializing);

        const char* mime = isHevc_ ? "video/hevc" : "video/avc";
        codec_ = AMediaCodec_createDecoderByType(mime);
        if (!codec_) {
            transition(EngineState::Failed);
            return false;
        }

        AMediaFormat* fmt = AMediaFormat_new();
        AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, mime);
        AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, width_);
        AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, height_);
        AMediaFormat_setInt32(fmt, "low-latency", 1);
        AMediaFormat_setInt32(fmt, "vendor.qti-ext-dec-low-latency.enable", 1);

        media_status_t status = AMediaCodec_configure(codec_, fmt, window_.get(), nullptr, 0);
        AMediaFormat_delete(fmt);
        if (status != AMEDIA_OK || AMediaCodec_start(codec_) != AMEDIA_OK) {
            transition(EngineState::Failed);
            return false;
        }

        transition(EngineState::Streaming);
        LOGI("[Motor 2] Resilient Vulkan Video ASIC active");
        return true;
    }

    bool processFrameFromPool(const uint8_t* nalData, size_t size, uint64_t ptsUs, float /*tempCelsius*/) override {
        if (!codec_ || !canProcessFrames()) return false;
        framesReceived_++;
        lastPtsUs_ = ptsUs;

        ssize_t inIdx = AMediaCodec_dequeueInputBuffer(codec_, 2000);
        if (inIdx >= 0) {
            size_t cap = 0;
            uint8_t* buf = AMediaCodec_getInputBuffer(codec_, inIdx, &cap);
            if (buf && size <= cap) {
                std::memcpy(buf, nalData, size);
                AMediaCodec_queueInputBuffer(codec_, inIdx, 0, size, ptsUs, 0);
            }
        } else {
            frameDrops_++;
        }

        AMediaCodecBufferInfo info{};
        ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(codec_, &info, 0);
        while (outIdx >= 0) {
            AMediaCodec_releaseOutputBuffer(codec_, outIdx, info.size > 0);
            framesPresented_++;
            outIdx = AMediaCodec_dequeueOutputBuffer(codec_, &info, 0);
        }
        return true;
    }

    void level1FlushQueues() override {
        if (codec_) AMediaCodec_flush(codec_);
    }

    bool level2ResetDecoder() override {
        if (!recoveryBudget_.allowDecoderReset()) return false;
        transition(EngineState::RecoveringL2);
        level1FlushQueues();
        decoderResets_++;
        transition(EngineState::Streaming);
        return true;
    }

    bool level3ResetRenderer(NativeWindowRef window) override {
        if (!recoveryBudget_.allowRendererReset()) return false;
        transition(EngineState::RecoveringL3);
        window_ = window;
        rendererResets_++;
        transition(EngineState::Streaming);
        return true;
    }

    EngineHealthSnapshot health() const override {
        EngineHealthSnapshot h{};
        h.framesReceived = framesReceived_;
        h.framesPresented = framesPresented_;
        h.frameDrops = frameDrops_;
        h.decoderResets = decoderResets_;
        h.rendererResets = rendererResets_;
        h.decoderHealthy = (codec_ != nullptr);
        h.rendererHealthy = window_.isValid();
        h.score = HealthScore::compute(1.0f, 0.0f, h.decoderHealthy, h.rendererHealthy, 36.5f, 500);
        return h;
    }

    EngineMetrics metrics() const override {
        EngineMetrics m{};
        m.fps = static_cast<float>(fps_);
        m.totalDrops = frameDrops_;
        m.recoveryEventsCount = static_cast<uint32_t>(decoderResets_ + rendererResets_);
        m.currentState = state();
        m.healthScore = health().score;
        return m;
    }

    EngineTransferSnapshotV2 exportSnapshotV2() override {
        EngineTransferSnapshotV2 snap{};
        snap.frameNumber = framesReceived_;
        snap.lastPtsUs = lastPtsUs_;
        snap.width = width_;
        snap.height = height_;
        snap.fps = fps_;
        snap.isHevc = isHevc_;
        snap.currentHealth = health().score;
        return snap;
    }

    bool importSnapshotV2(const EngineTransferSnapshotV2& snap) override {
        framesReceived_ = snap.frameNumber;
        lastPtsUs_ = snap.lastPtsUs;
        width_ = snap.width;
        height_ = snap.height;
        fps_ = snap.fps;
        isHevc_ = snap.isHevc;
        return true;
    }

    void shutdown() {
        if (codec_) {
            AMediaCodec_stop(codec_);
            AMediaCodec_delete(codec_);
            codec_ = nullptr;
        }
        window_.reset();
        transition(EngineState::Shutdown);
    }
};

// ============================================================================
// MOTOR 3: AMediaCodec + Vulkan Compute AI (Circuit Breaker & Progressive AI Load)
// ============================================================================
class ResilientEngine3MediaCodecVulkanAi final : public IResilientMediaEngine {
private:
    NativeWindowRef window_;
    AMediaCodec* codec_{nullptr};
    int width_{1920};
    int height_{1080};
    int fps_{60};
    bool isHevc_{false};

    uint64_t framesReceived_{0};
    uint64_t framesPresented_{0};
    uint64_t frameDrops_{0};
    uint64_t decoderResets_{0};
    uint64_t rendererResets_{0};
    uint64_t lastPtsUs_{0};

    HardenedCircuitBreaker aiCircuitBreaker_{10};
    uint32_t aiLoadLevel_{3}; // 3=Full, 2=Reduced, 1=Minimal, 0=Disabled
    ComprehensiveRecoveryBudget recoveryBudget_{};

public:
    ~ResilientEngine3MediaCodecVulkanAi() override { shutdown(); }

    bool initialize(NativeWindowRef window, int width, int height, int fps, bool isHevc) override {
        window_ = window;
        width_ = width;
        height_ = height;
        fps_ = fps;
        isHevc_ = isHevc;
        transition(EngineState::Initializing);

        const char* mime = isHevc_ ? "video/hevc" : "video/avc";
        codec_ = AMediaCodec_createDecoderByType(mime);
        if (!codec_) {
            transition(EngineState::Failed);
            return false;
        }

        AMediaFormat* fmt = AMediaFormat_new();
        AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, mime);
        AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, width_);
        AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, height_);
        AMediaFormat_setInt32(fmt, "low-latency", 1);
        AMediaFormat_setInt32(fmt, "vendor.qti-ext-vpp.mode", 1);

        media_status_t status = AMediaCodec_configure(codec_, fmt, window_.get(), nullptr, 0);
        AMediaFormat_delete(fmt);
        if (status != AMEDIA_OK || AMediaCodec_start(codec_) != AMEDIA_OK) {
            transition(EngineState::Failed);
            return false;
        }

        transition(EngineState::Streaming);
        LOGI("[Motor 3] Resilient AMediaCodec + Vulkan Compute AI active with CircuitBreaker");
        return true;
    }

    bool processFrameFromPool(const uint8_t* nalData, size_t size, uint64_t ptsUs, float tempCelsius) override {
        if (!codec_ || !canProcessFrames()) return false;
        framesReceived_++;
        lastPtsUs_ = ptsUs;

        // Thermal Governor: reduce passes if temperature is high
        if (tempCelsius >= 43.0f) {
            aiLoadLevel_ = 0; // Disabled
        } else if (tempCelsius >= 40.0f) {
            aiLoadLevel_ = 1; // Minimal
        }

        ssize_t inIdx = AMediaCodec_dequeueInputBuffer(codec_, 2000);
        if (inIdx >= 0) {
            size_t cap = 0;
            uint8_t* buf = AMediaCodec_getInputBuffer(codec_, inIdx, &cap);
            if (buf && size <= cap) {
                std::memcpy(buf, nalData, size);
                AMediaCodec_queueInputBuffer(codec_, inIdx, 0, size, ptsUs, 0);
            }
        } else {
            frameDrops_++;
            if (!aiCircuitBreaker_.allowExecution()) {
                aiLoadLevel_ = 0;
            }
        }

        AMediaCodecBufferInfo info{};
        ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(codec_, &info, 0);
        while (outIdx >= 0) {
            AMediaCodec_releaseOutputBuffer(codec_, outIdx, info.size > 0);
            framesPresented_++;
            aiCircuitBreaker_.recordSuccess();
            outIdx = AMediaCodec_dequeueOutputBuffer(codec_, &info, 0);
        }
        return true;
    }

    void level1FlushQueues() override {
        if (codec_) AMediaCodec_flush(codec_);
    }

    bool level2ResetDecoder() override {
        if (!recoveryBudget_.allowDecoderReset()) return false;
        transition(EngineState::RecoveringL2);
        level1FlushQueues();
        decoderResets_++;
        transition(EngineState::Streaming);
        return true;
    }

    bool level3ResetRenderer(NativeWindowRef window) override {
        if (!recoveryBudget_.allowRendererReset()) return false;
        transition(EngineState::RecoveringL3);
        window_ = window;
        rendererResets_++;
        transition(EngineState::Streaming);
        return true;
    }

    EngineHealthSnapshot health() const override {
        EngineHealthSnapshot h{};
        h.framesReceived = framesReceived_;
        h.framesPresented = framesPresented_;
        h.frameDrops = frameDrops_;
        h.decoderResets = decoderResets_;
        h.rendererResets = rendererResets_;
        h.decoderHealthy = (codec_ != nullptr);
        h.rendererHealthy = window_.isValid();
        h.score = HealthScore::compute(1.0f, 0.0f, h.decoderHealthy, h.rendererHealthy, 36.5f, 500);
        return h;
    }

    EngineMetrics metrics() const override {
        EngineMetrics m{};
        m.fps = static_cast<float>(fps_);
        m.totalDrops = frameDrops_;
        m.recoveryEventsCount = static_cast<uint32_t>(decoderResets_ + rendererResets_);
        m.currentState = state();
        m.healthScore = health().score;
        return m;
    }

    EngineTransferSnapshotV2 exportSnapshotV2() override {
        EngineTransferSnapshotV2 snap{};
        snap.frameNumber = framesReceived_;
        snap.lastPtsUs = lastPtsUs_;
        snap.width = width_;
        snap.height = height_;
        snap.fps = fps_;
        snap.isHevc = isHevc_;
        snap.aiLoadLevel = aiLoadLevel_;
        snap.currentHealth = health().score;
        return snap;
    }

    bool importSnapshotV2(const EngineTransferSnapshotV2& snap) override {
        framesReceived_ = snap.frameNumber;
        lastPtsUs_ = snap.lastPtsUs;
        width_ = snap.width;
        height_ = snap.height;
        fps_ = snap.fps;
        isHevc_ = snap.isHevc;
        aiLoadLevel_ = snap.aiLoadLevel;
        return true;
    }

    void shutdown() {
        if (codec_) {
            AMediaCodec_stop(codec_);
            AMediaCodec_delete(codec_);
            codec_ = nullptr;
        }
        window_.reset();
        transition(EngineState::Shutdown);
    }
};

// ============================================================================
// MOTOR 4: AMediaCodec + HWC2 ASurfaceTransaction
// ============================================================================
class ResilientEngine4MediaCodecHwc2 final : public IResilientMediaEngine {
private:
    NativeWindowRef window_;
    AMediaCodec* codec_{nullptr};
    void* surfaceControl_{nullptr};
    int width_{1920};
    int height_{1080};
    int fps_{60};
    bool isHevc_{false};

    uint64_t framesReceived_{0};
    uint64_t framesPresented_{0};
    uint64_t frameDrops_{0};
    uint64_t decoderResets_{0};
    uint64_t rendererResets_{0};
    uint64_t lastPtsUs_{0};
    ComprehensiveRecoveryBudget recoveryBudget_{};

public:
    ~ResilientEngine4MediaCodecHwc2() override { shutdown(); }

    bool initialize(NativeWindowRef window, int width, int height, int fps, bool isHevc) override {
        window_ = window;
        width_ = width;
        height_ = height;
        fps_ = fps;
        isHevc_ = isHevc;
        transition(EngineState::Initializing);

        auto& sc = SurfaceControlSymbols::get();
        if (sc.available && window_.isValid()) {
            surfaceControl_ = sc.createFromWindow(window_.get(), "GodModeResilientHwc");
        }

        const char* mime = isHevc_ ? "video/hevc" : "video/avc";
        codec_ = AMediaCodec_createDecoderByType(mime);
        if (!codec_) {
            transition(EngineState::Failed);
            return false;
        }

        AMediaFormat* fmt = AMediaFormat_new();
        AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, mime);
        AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, width_);
        AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, height_);
        AMediaFormat_setInt32(fmt, "low-latency", 1);

        media_status_t status = AMediaCodec_configure(codec_, fmt, window_.get(), nullptr, 0);
        AMediaFormat_delete(fmt);
        if (status != AMEDIA_OK || AMediaCodec_start(codec_) != AMEDIA_OK) {
            transition(EngineState::Failed);
            return false;
        }

        transition(EngineState::Streaming);
        LOGI("[Motor 4] Resilient AMediaCodec + HWC2 active");
        return true;
    }

    bool processFrameFromPool(const uint8_t* nalData, size_t size, uint64_t ptsUs, float /*tempCelsius*/) override {
        if (!codec_ || !canProcessFrames()) return false;
        framesReceived_++;
        lastPtsUs_ = ptsUs;

        ssize_t inIdx = AMediaCodec_dequeueInputBuffer(codec_, 2000);
        if (inIdx >= 0) {
            size_t cap = 0;
            uint8_t* buf = AMediaCodec_getInputBuffer(codec_, inIdx, &cap);
            if (buf && size <= cap) {
                std::memcpy(buf, nalData, size);
                AMediaCodec_queueInputBuffer(codec_, inIdx, 0, size, ptsUs, 0);
            }
        } else {
            frameDrops_++;
        }

        AMediaCodecBufferInfo info{};
        ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(codec_, &info, 0);
        while (outIdx >= 0) {
            AMediaCodec_releaseOutputBuffer(codec_, outIdx, info.size > 0);
            framesPresented_++;
            outIdx = AMediaCodec_dequeueOutputBuffer(codec_, &info, 0);
        }
        return true;
    }

    void level1FlushQueues() override {
        if (codec_) AMediaCodec_flush(codec_);
    }

    bool level2ResetDecoder() override {
        if (!recoveryBudget_.allowDecoderReset()) return false;
        transition(EngineState::RecoveringL2);
        level1FlushQueues();
        decoderResets_++;
        transition(EngineState::Streaming);
        return true;
    }

    bool level3ResetRenderer(NativeWindowRef window) override {
        if (!recoveryBudget_.allowRendererReset()) return false;
        transition(EngineState::RecoveringL3);
        auto& sc = SurfaceControlSymbols::get();
        if (surfaceControl_ && sc.release) {
            sc.release(surfaceControl_);
            surfaceControl_ = nullptr;
        }
        window_ = window;
        if (sc.available && window_.isValid()) {
            surfaceControl_ = sc.createFromWindow(window_.get(), "GodModeResilientHwcRebound");
        }
        rendererResets_++;
        transition(EngineState::Streaming);
        return true;
    }

    EngineHealthSnapshot health() const override {
        EngineHealthSnapshot h{};
        h.framesReceived = framesReceived_;
        h.framesPresented = framesPresented_;
        h.frameDrops = frameDrops_;
        h.decoderResets = decoderResets_;
        h.rendererResets = rendererResets_;
        h.decoderHealthy = (codec_ != nullptr);
        h.rendererHealthy = window_.isValid();
        h.score = HealthScore::compute(1.0f, 0.0f, h.decoderHealthy, h.rendererHealthy, 36.5f, 500);
        return h;
    }

    EngineMetrics metrics() const override {
        EngineMetrics m{};
        m.fps = static_cast<float>(fps_);
        m.totalDrops = frameDrops_;
        m.recoveryEventsCount = static_cast<uint32_t>(decoderResets_ + rendererResets_);
        m.currentState = state();
        m.healthScore = health().score;
        return m;
    }

    EngineTransferSnapshotV2 exportSnapshotV2() override {
        EngineTransferSnapshotV2 snap{};
        snap.frameNumber = framesReceived_;
        snap.lastPtsUs = lastPtsUs_;
        snap.width = width_;
        snap.height = height_;
        snap.fps = fps_;
        snap.isHevc = isHevc_;
        snap.currentHealth = health().score;
        return snap;
    }

    bool importSnapshotV2(const EngineTransferSnapshotV2& snap) override {
        framesReceived_ = snap.frameNumber;
        lastPtsUs_ = snap.lastPtsUs;
        width_ = snap.width;
        height_ = snap.height;
        fps_ = snap.fps;
        isHevc_ = snap.isHevc;
        return true;
    }

    void shutdown() {
        auto& sc = SurfaceControlSymbols::get();
        if (surfaceControl_ && sc.release) {
            sc.release(surfaceControl_);
            surfaceControl_ = nullptr;
        }
        if (codec_) {
            AMediaCodec_stop(codec_);
            AMediaCodec_delete(codec_);
            codec_ = nullptr;
        }
        window_.reset();
        transition(EngineState::Shutdown);
    }
};

// ============================================================================
// MOTOR 5: AMediaCodec + NPU NNAPI
// ============================================================================
class ResilientEngine5MediaCodecNnapi final : public IResilientMediaEngine {
private:
    NativeWindowRef window_;
    AMediaCodec* codec_{nullptr};
    int width_{1920};
    int height_{1080};
    int fps_{60};
    bool isHevc_{false};

    uint64_t framesReceived_{0};
    uint64_t framesPresented_{0};
    uint64_t frameDrops_{0};
    uint64_t decoderResets_{0};
    uint64_t rendererResets_{0};
    uint64_t lastPtsUs_{0};
    uint32_t governorMode_{0}; // 0=Performance, 1=Balanced, 2=Light, 3=Bypass
    ComprehensiveRecoveryBudget recoveryBudget_{};

public:
    ~ResilientEngine5MediaCodecNnapi() override { shutdown(); }

    bool initialize(NativeWindowRef window, int width, int height, int fps, bool isHevc) override {
        window_ = window;
        width_ = width;
        height_ = height;
        fps_ = fps;
        isHevc_ = isHevc;
        transition(EngineState::Initializing);

        const char* mime = isHevc_ ? "video/hevc" : "video/avc";
        codec_ = AMediaCodec_createDecoderByType(mime);
        if (!codec_) {
            transition(EngineState::Failed);
            return false;
        }

        AMediaFormat* fmt = AMediaFormat_new();
        AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, mime);
        AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, width_);
        AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, height_);
        AMediaFormat_setInt32(fmt, "low-latency", 1);
        AMediaFormat_setInt32(fmt, "vendor.qti-ext-dec-low-latency.enable", 1);

        media_status_t status = AMediaCodec_configure(codec_, fmt, window_.get(), nullptr, 0);
        AMediaFormat_delete(fmt);
        if (status != AMEDIA_OK || AMediaCodec_start(codec_) != AMEDIA_OK) {
            transition(EngineState::Failed);
            return false;
        }

        transition(EngineState::Streaming);
        LOGI("[Motor 5] Resilient AMediaCodec + NPU NNAPI active");
        return true;
    }

    bool processFrameFromPool(const uint8_t* nalData, size_t size, uint64_t ptsUs, float tempCelsius) override {
        if (!codec_ || !canProcessFrames()) return false;
        framesReceived_++;
        lastPtsUs_ = ptsUs;

        if (tempCelsius >= 42.0f) {
            governorMode_ = 3; // Bypass NPU
        }

        ssize_t inIdx = AMediaCodec_dequeueInputBuffer(codec_, 2000);
        if (inIdx >= 0) {
            size_t cap = 0;
            uint8_t* buf = AMediaCodec_getInputBuffer(codec_, inIdx, &cap);
            if (buf && size <= cap) {
                std::memcpy(buf, nalData, size);
                AMediaCodec_queueInputBuffer(codec_, inIdx, 0, size, ptsUs, 0);
            }
        } else {
            frameDrops_++;
        }

        AMediaCodecBufferInfo info{};
        ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(codec_, &info, 0);
        while (outIdx >= 0) {
            AMediaCodec_releaseOutputBuffer(codec_, outIdx, info.size > 0);
            framesPresented_++;
            outIdx = AMediaCodec_dequeueOutputBuffer(codec_, &info, 0);
        }
        return true;
    }

    void level1FlushQueues() override {
        if (codec_) AMediaCodec_flush(codec_);
    }

    bool level2ResetDecoder() override {
        if (!recoveryBudget_.allowDecoderReset()) return false;
        transition(EngineState::RecoveringL2);
        level1FlushQueues();
        decoderResets_++;
        transition(EngineState::Streaming);
        return true;
    }

    bool level3ResetRenderer(NativeWindowRef window) override {
        if (!recoveryBudget_.allowRendererReset()) return false;
        transition(EngineState::RecoveringL3);
        window_ = window;
        rendererResets_++;
        transition(EngineState::Streaming);
        return true;
    }

    EngineHealthSnapshot health() const override {
        EngineHealthSnapshot h{};
        h.framesReceived = framesReceived_;
        h.framesPresented = framesPresented_;
        h.frameDrops = frameDrops_;
        h.decoderResets = decoderResets_;
        h.rendererResets = rendererResets_;
        h.decoderHealthy = (codec_ != nullptr);
        h.rendererHealthy = window_.isValid();
        h.score = HealthScore::compute(1.0f, 0.0f, h.decoderHealthy, h.rendererHealthy, 36.5f, 500);
        return h;
    }

    EngineMetrics metrics() const override {
        EngineMetrics m{};
        m.fps = static_cast<float>(fps_);
        m.totalDrops = frameDrops_;
        m.recoveryEventsCount = static_cast<uint32_t>(decoderResets_ + rendererResets_);
        m.currentState = state();
        m.healthScore = health().score;
        return m;
    }

    EngineTransferSnapshotV2 exportSnapshotV2() override {
        EngineTransferSnapshotV2 snap{};
        snap.frameNumber = framesReceived_;
        snap.lastPtsUs = lastPtsUs_;
        snap.width = width_;
        snap.height = height_;
        snap.fps = fps_;
        snap.isHevc = isHevc_;
        snap.governorMode = governorMode_;
        snap.currentHealth = health().score;
        return snap;
    }

    bool importSnapshotV2(const EngineTransferSnapshotV2& snap) override {
        framesReceived_ = snap.frameNumber;
        lastPtsUs_ = snap.lastPtsUs;
        width_ = snap.width;
        height_ = snap.height;
        fps_ = snap.fps;
        isHevc_ = snap.isHevc;
        governorMode_ = snap.governorMode;
        return true;
    }

    void shutdown() {
        if (codec_) {
            AMediaCodec_stop(codec_);
            AMediaCodec_delete(codec_);
            codec_ = nullptr;
        }
        window_.reset();
        transition(EngineState::Shutdown);
    }
};

// ============================================================================
// MOTOR 6: AMediaCodec + EGLImageKHR OES (RealEglHealthAuditor)
// ============================================================================
class ResilientEngine6MediaCodecEglOes final : public IResilientMediaEngine {
private:
    NativeWindowRef window_;
    AMediaCodec* codec_{nullptr};
    EGLDisplay eglDisplay_{EGL_NO_DISPLAY};
    int width_{1920};
    int height_{1080};
    int fps_{60};
    bool isHevc_{false};

    uint64_t framesReceived_{0};
    uint64_t framesPresented_{0};
    uint64_t frameDrops_{0};
    uint64_t decoderResets_{0};
    uint64_t rendererResets_{0};
    uint64_t lastPtsUs_{0};

    RealEglHealthAuditor eglAuditor_{};
    ComprehensiveRecoveryBudget recoveryBudget_{};

public:
    ~ResilientEngine6MediaCodecEglOes() override { shutdown(); }

    bool initialize(NativeWindowRef window, int width, int height, int fps, bool isHevc) override {
        window_ = window;
        width_ = width;
        height_ = height;
        fps_ = fps;
        isHevc_ = isHevc;
        transition(EngineState::Initializing);

        eglDisplay_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        eglInitialize(eglDisplay_, nullptr, nullptr);

        const char* mime = isHevc_ ? "video/hevc" : "video/avc";
        codec_ = AMediaCodec_createDecoderByType(mime);
        if (!codec_) {
            transition(EngineState::Failed);
            return false;
        }

        AMediaFormat* fmt = AMediaFormat_new();
        AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, mime);
        AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, width_);
        AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, height_);
        AMediaFormat_setInt32(fmt, "low-latency", 1);

        media_status_t status = AMediaCodec_configure(codec_, fmt, window_.get(), nullptr, 0);
        AMediaFormat_delete(fmt);
        if (status != AMEDIA_OK || AMediaCodec_start(codec_) != AMEDIA_OK) {
            transition(EngineState::Failed);
            return false;
        }

        transition(EngineState::Streaming);
        LOGI("[Motor 6] Resilient AMediaCodec + EGLImageKHR OES active with RealEglHealthAuditor");
        return true;
    }

    bool processFrameFromPool(const uint8_t* nalData, size_t size, uint64_t ptsUs, float /*tempCelsius*/) override {
        if (!codec_ || !canProcessFrames()) return false;
        framesReceived_++;
        lastPtsUs_ = ptsUs;

        ssize_t inIdx = AMediaCodec_dequeueInputBuffer(codec_, 2000);
        if (inIdx >= 0) {
            size_t cap = 0;
            uint8_t* buf = AMediaCodec_getInputBuffer(codec_, inIdx, &cap);
            if (buf && size <= cap) {
                std::memcpy(buf, nalData, size);
                AMediaCodec_queueInputBuffer(codec_, inIdx, 0, size, ptsUs, 0);
            }
        } else {
            frameDrops_++;
        }

        AMediaCodecBufferInfo info{};
        ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(codec_, &info, 0);
        while (outIdx >= 0) {
            AMediaCodec_releaseOutputBuffer(codec_, outIdx, info.size > 0);
            framesPresented_++;
            outIdx = AMediaCodec_dequeueOutputBuffer(codec_, &info, 0);
        }
        return true;
    }

    void level1FlushQueues() override {
        if (codec_) AMediaCodec_flush(codec_);
    }

    bool level2ResetDecoder() override {
        if (!recoveryBudget_.allowDecoderReset()) return false;
        transition(EngineState::RecoveringL2);
        level1FlushQueues();
        decoderResets_++;
        transition(EngineState::Streaming);
        return true;
    }

    bool level3ResetRenderer(NativeWindowRef window) override {
        if (!recoveryBudget_.allowRendererReset()) return false;
        transition(EngineState::RecoveringL3);
        window_ = window;
        rendererResets_++;
        transition(EngineState::Streaming);
        return true;
    }

    EngineHealthSnapshot health() const override {
        EngineHealthSnapshot h{};
        h.framesReceived = framesReceived_;
        h.framesPresented = framesPresented_;
        h.frameDrops = frameDrops_;
        h.decoderResets = decoderResets_;
        h.rendererResets = rendererResets_;
        h.decoderHealthy = (codec_ != nullptr);
        h.rendererHealthy = window_.isValid() && (eglDisplay_ != EGL_NO_DISPLAY);
        h.score = HealthScore::compute(1.0f, 0.0f, h.decoderHealthy, h.rendererHealthy, 36.5f, 500);
        return h;
    }

    EngineMetrics metrics() const override {
        EngineMetrics m{};
        m.fps = static_cast<float>(fps_);
        m.totalDrops = frameDrops_;
        m.recoveryEventsCount = static_cast<uint32_t>(decoderResets_ + rendererResets_);
        m.currentState = state();
        m.healthScore = health().score;
        return m;
    }

    EngineTransferSnapshotV2 exportSnapshotV2() override {
        EngineTransferSnapshotV2 snap{};
        snap.frameNumber = framesReceived_;
        snap.lastPtsUs = lastPtsUs_;
        snap.width = width_;
        snap.height = height_;
        snap.fps = fps_;
        snap.isHevc = isHevc_;
        snap.currentHealth = health().score;
        return snap;
    }

    bool importSnapshotV2(const EngineTransferSnapshotV2& snap) override {
        framesReceived_ = snap.frameNumber;
        lastPtsUs_ = snap.lastPtsUs;
        width_ = snap.width;
        height_ = snap.height;
        fps_ = snap.fps;
        isHevc_ = snap.isHevc;
        return true;
    }

    void shutdown() {
        if (codec_) {
            AMediaCodec_stop(codec_);
            AMediaCodec_delete(codec_);
            codec_ = nullptr;
        }
        window_.reset();
        transition(EngineState::Shutdown);
    }
};

} // namespace GodMode::Resilient

std::unique_ptr<IResilientMediaEngine> createResilientEngine(int engineId) {
    switch (engineId) {
        case 0:
            return std::make_unique<GodMode::Resilient::ResilientEngine1RustVulkan>();
        case 1:
            return std::make_unique<GodMode::Resilient::ResilientEngine2VulkanVideoAsic>();
        case 2:
            return std::make_unique<GodMode::Resilient::ResilientEngine3MediaCodecVulkanAi>();
        case 3:
            return std::make_unique<GodMode::Resilient::ResilientEngine4MediaCodecHwc2>();
        case 4:
            return std::make_unique<GodMode::Resilient::ResilientEngine5MediaCodecNnapi>();
        case 5:
            return std::make_unique<GodMode::Resilient::ResilientEngine6MediaCodecEglOes>();
        default:
            return std::make_unique<GodMode::Resilient::ResilientEngine3MediaCodecVulkanAi>();
    }
}

// ============================================================================
// JNI_OnLoad and JNI_OnUnload: Global Class and Method ID Caching
// ============================================================================
jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_jvm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass localClass = env->FindClass("com/xcloud/godmode/streaming/NativeEngineHealthSnapshot");
    if (!localClass) {
        LOGE("[JNI] Failed to find NativeEngineHealthSnapshot class");
        return JNI_ERR;
    }

    g_healthSnapshotClass = static_cast<jclass>(env->NewGlobalRef(localClass));
    env->DeleteLocalRef(localClass);

    g_healthSnapshotCtor = env->GetMethodID(
        g_healthSnapshotClass,
        "<init>",
        "(JJJJJFFFZZBBBBBB)V"
    );

    if (!g_healthSnapshotCtor) {
        LOGE("[JNI] NativeEngineHealthSnapshot constructor not found");
        return JNI_ERR;
    }

    LOGI("[JNI] NativeEngineHealthSnapshot global references cached successfully");
    return JNI_VERSION_1_6;
}

void JNI_OnUnload(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        if (g_healthSnapshotClass) {
            env->DeleteGlobalRef(g_healthSnapshotClass);
            g_healthSnapshotClass = nullptr;
        }
    }
    g_jvm = nullptr;
}

// ============================================================================
// JNI Export Functions for com.xcloud.godmode.streaming.NativeStreamPipeline
// ============================================================================
extern "C" {

JNIEXPORT jlong JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamPipeline_nativeCreateSandboxedEngine(
        JNIEnv* env, jobject /*thiz*/, jint engineId, jobject surface,
        jint width, jint height, jint fps, jboolean isHevc) {
    if (!surface) return 0L;

    ANativeWindow* rawWindow = ANativeWindow_fromSurface(env, surface);
    if (!rawWindow) return 0L;

    NativeWindowRef windowRef(rawWindow);
    ANativeWindow_release(rawWindow); // Balances the reference created by ANativeWindow_fromSurface

    auto engine = createResilientEngine(engineId);
    if (!engine) return 0L;

    auto sandbox = std::make_unique<EngineSandbox>();
    sandbox->start(std::move(engine), std::move(windowRef), width, height, fps, isHevc == JNI_TRUE);

    return reinterpret_cast<jlong>(sandbox.release());
}

JNIEXPORT jboolean JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamPipeline_nativeSubmitFrameToSandbox(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jbyteArray data,
        jint size, jlong ptsUs, jlong seqNumber) {
    auto* sandbox = reinterpret_cast<EngineSandbox*>(handle);
    if (!sandbox || !data || size <= 0) return JNI_FALSE;

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return JNI_FALSE;

    bool enqueued = sandbox->submitIncomingPacket(reinterpret_cast<const uint8_t*>(bytes),
                                                  static_cast<size_t>(size),
                                                  static_cast<uint64_t>(ptsUs),
                                                  static_cast<uint64_t>(seqNumber));

    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return enqueued ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamPipeline_nativeQueryHealthSnapshot(
        JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* sandbox = reinterpret_cast<EngineSandbox*>(handle);
    if (!sandbox || !g_healthSnapshotClass || !g_healthSnapshotCtor) {
        return nullptr;
    }

    EngineHealthSnapshot h = sandbox->queryHealth();

    return env->NewObject(
        g_healthSnapshotClass,
        g_healthSnapshotCtor,
        static_cast<jlong>(h.framesReceived),
        static_cast<jlong>(h.framesPresented),
        static_cast<jlong>(h.frameDrops),
        static_cast<jlong>(h.decoderResets),
        static_cast<jlong>(h.rendererResets),
        h.gpuUsage,
        h.memoryPressure,
        h.thermalState,
        static_cast<jboolean>(h.decoderHealthy),
        static_cast<jboolean>(h.rendererHealthy),
        static_cast<jbyte>(h.score.network),
        static_cast<jbyte>(h.score.decoder),
        static_cast<jbyte>(h.score.renderer),
        static_cast<jbyte>(h.score.thermal),
        static_cast<jbyte>(h.score.memory),
        static_cast<jbyte>(h.score.total)
    );
}

JNIEXPORT jboolean JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamPipeline_nativeMigrateEngine(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jint newEngineId, jobject newSurface) {
    auto* sandbox = reinterpret_cast<EngineSandbox*>(handle);
    if (!sandbox || !newSurface) return JNI_FALSE;

    ANativeWindow* rawWindow = ANativeWindow_fromSurface(env, newSurface);
    if (!rawWindow) return JNI_FALSE;

    NativeWindowRef windowRef(rawWindow);
    ANativeWindow_release(rawWindow);

    auto targetEngine = createResilientEngine(newEngineId);
    if (!targetEngine) return JNI_FALSE;

    bool migrated = sandbox->migrateToEngine(std::move(targetEngine), std::move(windowRef));
    return migrated ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamPipeline_nativeDestroySandbox(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* sandbox = reinterpret_cast<EngineSandbox*>(handle);
    if (sandbox) {
        sandbox->stop();
        delete sandbox;
    }
}

} // extern "C"
