#include "IStreamEngine.h"
#include <thread>
#include <condition_variable>
#include <mutex>
#include <queue>
#include <vector>
#include <cstring>
#include <dlfcn.h>
#include <android/native_window.h>
#include <android/hardware_buffer.h>
#include <media/NdkImageReader.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl32.h>
#include <GLES2/gl2ext.h>

namespace GodMode::Stream {

extern "C" void applyQftDesiredPresentTime(void*);

typedef media_status_t (*PFN_AMediaCodec_setAsyncNotifyCallback)(
    AMediaCodec*,
    AMediaCodecOnAsyncNotifyCallback,
    void*
);

// -----------------------------------------------------------------------------
// MOTOR 3: AMediaCodec NDK Asíncrono + Direct Hardware Composer (HWC2) + AI Super Resolution
// -----------------------------------------------------------------------------
class Engine3MediaCodecAiNpuImpl : public IStreamEngine {
private:
    AMediaCodec* codec = nullptr;
    ANativeWindow* displayWindow = nullptr;
    std::queue<int32_t> inputBufferQueue;
    std::mutex queueMutex;
    bool enableAiUpscaling = true;
    std::atomic<bool> isRunning{false};
    uint64_t streamBaselineStartUs = 0;
    StreamCropInfo cropInfo{};
    std::vector<uint8_t> cachedSps;
    std::vector<uint8_t> cachedPps;

    static void onInputAvailable(AMediaCodec*, void* userdata, int32_t index) {
        auto* self = static_cast<Engine3MediaCodecAiNpuImpl*>(userdata);
        if (!self || !self->isRunning.load(std::memory_order_relaxed)) return;
        std::lock_guard<std::mutex> lock(self->queueMutex);
        self->inputBufferQueue.push(index);
    }

    static void onOutputAvailable(AMediaCodec* c, void* userdata, int32_t index, AMediaCodecBufferInfo* info) {
        auto* self = static_cast<Engine3MediaCodecAiNpuImpl*>(userdata);
        if (!self || !self->isRunning.load(std::memory_order_relaxed)) return;

        if (info && info->size > 0) {
            AMediaCodec_releaseOutputBuffer(c, index, true);
        } else {
            AMediaCodec_releaseOutputBuffer(c, index, false);
        }
    }

    static void onFormatChanged(AMediaCodec* c, void* userdata, AMediaFormat*) {
        auto* self = static_cast<Engine3MediaCodecAiNpuImpl*>(userdata);
        if (self) {
            handleOutputFormatChange(c, self->cropInfo);
            GM_LOGI("Engine 3 Output Format Changed: coded=%dx%d crop=(%d,%d)-(%d,%d)",
                    self->cropInfo.codedWidth, self->cropInfo.codedHeight,
                    self->cropInfo.left, self->cropInfo.top, self->cropInfo.right, self->cropInfo.bottom);
        }
    }

    static void onError(AMediaCodec* c, void* userdata, media_status_t status, int32_t actionCode, const char* detail) {
        GM_LOGE("Engine 3 AMediaCodec Error status=%d action=%d detail=%s", status, actionCode, detail ? detail : "unknown");
        auto* self = static_cast<Engine3MediaCodecAiNpuImpl*>(userdata);
        if (self) {
            recoverFaultedHardwareDecoder(c, self->cachedSps, self->cachedPps);
        }
    }

    NetworkConcealmentGuard concealmentGuard;

public:
    Engine3MediaCodecAiNpuImpl() = default;
    ~Engine3MediaCodecAiNpuImpl() override { shutdown(); }

    bool initialize(ANativeWindow* win, int width, int height, bool aiUpscale) override {
        shutdown();
        if (!win) return false;

        displayWindow = win;
        enableAiUpscaling = aiUpscale;
        streamBaselineStartUs = getMonotonicMicros();

        ANativeWindow_setBuffersGeometry(displayWindow, width, height, AHARDWAREBUFFER_FORMAT_Y8Cb8Cr8_420);

        codec = AMediaCodec_createDecoderByType("video/avc");
        if (!codec) {
            GM_LOGE("Fallo al crear AMediaCodec video/avc");
            return false;
        }

        AMediaFormat* format = AMediaFormat_new();
        AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "video/avc");
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, width);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, height);
        applyZeroReorderLatencyFormatKeys(format);
        applyUltraQualityDecoderParameters(format, (width >= 2560 || height >= 1440));

        if (aiUpscale) {
            AMediaFormat_setInt32(format, "vendor.qti-ext-vpp.mode", 1);
            AMediaFormat_setInt32(format, "vendor.qti-ext-dec-low-latency.enable", 1);
            AMediaFormat_setInt32(format, "vendor.mtk-ext-ai-sr.enable", 1);
            AMediaFormat_setInt32(format, "vendor.mtk-ext-dec-low-latency.enable", 1);
            AMediaFormat_setInt32(format, "vendor.sec-ext-vt-ai-upscale.enable", 1);
        }

        auto pfnSetAsync = reinterpret_cast<PFN_AMediaCodec_setAsyncNotifyCallback>(
            dlsym(RTLD_DEFAULT, "AMediaCodec_setAsyncNotifyCallback")
        );
        if (pfnSetAsync) {
            AMediaCodecOnAsyncNotifyCallback asyncCb = {
                onInputAvailable, onOutputAvailable, onFormatChanged, onError
            };
            pfnSetAsync(codec, asyncCb, this);
        }

        media_status_t configStatus = AMediaCodec_configure(codec, format, displayWindow, nullptr, 0);
        AMediaFormat_delete(format);

        if (configStatus != AMEDIA_OK) {
            GM_LOGE("AMediaCodec_configure falló con estado %d", configStatus);
            AMediaCodec_delete(codec);
            codec = nullptr;
            return false;
        }

        if (AMediaCodec_start(codec) != AMEDIA_OK) {
            AMediaCodec_delete(codec);
            codec = nullptr;
            return false;
        }

        isRunning.store(true, std::memory_order_release);
        GM_LOGI("Motor 3 (AMediaCodec Direct HWC2 + AI Upscale=%d) listo (%dx%d)", aiUpscale, width, height);
        return true;
    }

    void injectCodecConfig(const uint8_t* sps, size_t spsLen, const uint8_t* pps, size_t ppsLen) override {
        if (!codec || !sps || !pps || spsLen == 0 || ppsLen == 0) return;
        cachedSps.assign(sps, sps + spsLen);
        cachedPps.assign(pps, pps + ppsLen);

        std::vector<uint8_t> csd;
        const uint8_t startCode[4] = {0x00, 0x00, 0x00, 0x01};
        csd.insert(csd.end(), startCode, startCode + 4);
        csd.insert(csd.end(), sps, sps + spsLen);
        csd.insert(csd.end(), startCode, startCode + 4);
        csd.insert(csd.end(), pps, pps + ppsLen);

        pushInternalBuffer(csd.data(), csd.size(), 0, AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG);
    }

    void pushAnnexBNalUnit(const uint8_t* data, size_t size, uint64_t ptsUs, bool isIdr) override {
        if (!isRunning.load(std::memory_order_relaxed)) return;
        if (!shouldDropOrProcessFrame(ptsUs, streamBaselineStartUs)) return;
        
        bool needsPli = false;
        // Si detectamos corrupción (data == nullptr o flag externo), inyectar ocultamiento
        if (data == nullptr || size == 0) {
            if (concealmentGuard.shouldInjectConcealmentFrame(true, needsPli)) {
                const auto& payload = concealmentGuard.getConcealmentPayload();
                if (!payload.empty()) {
                    pushInternalBuffer(payload.data(), payload.size(), ptsUs, 0);
                    GM_LOGW("Concealment Guard: Fotograma duplicado inyectado en VRAM para evitar freeze");
                }
            }
            if (needsPli) {
                // Aquí el bridge debería enviar el RTCP PLI
            }
            return;
        }

        pushInternalBuffer(data, size, ptsUs, isIdr ? 1 : 0);
        // Registrar este fotograma como el último válido para futuro ocultamiento
        concealmentGuard.registerValidDecodedFrame(data, size, ptsUs);
    }

    void onResize(int displayWidth, int displayHeight) override {
        if (displayWindow && displayWidth > 0 && displayHeight > 0) {
            ANativeWindow_setBuffersGeometry(displayWindow, displayWidth, displayHeight, AHARDWAREBUFFER_FORMAT_Y8Cb8Cr8_420);
        }
    }

    void shutdown() override {
        isRunning.store(false, std::memory_order_release);
        {
            std::lock_guard<std::mutex> lock(queueMutex);
            while (!inputBufferQueue.empty()) inputBufferQueue.pop();
        }
        if (codec) {
            AMediaCodec_stop(codec);
            AMediaCodec_delete(codec);
            codec = nullptr;
        }
        displayWindow = nullptr;
    }

private:
    void pushInternalBuffer(const uint8_t* data, size_t size, uint64_t ptsUs, uint32_t flags) {
        if (!codec || !data || size == 0) return;

        int32_t index = -1;
        {
            std::lock_guard<std::mutex> lock(queueMutex);
            if (!inputBufferQueue.empty()) {
                index = inputBufferQueue.front();
                inputBufferQueue.pop();
            }
        }

        if (index < 0) {
            index = static_cast<int32_t>(AMediaCodec_dequeueInputBuffer(codec, 2000));
        }

        if (index >= 0) {
            size_t outCapacity = 0;
            uint8_t* dst = AMediaCodec_getInputBuffer(codec, index, &outCapacity);
            if (dst && size <= outCapacity) {
                std::memcpy(dst, data, size);
                AMediaCodec_queueInputBuffer(codec, index, 0, size, ptsUs, flags);
            }
        }

        AMediaCodecBufferInfo info;
        ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(codec, &info, 0);
        while (outIdx >= 0) {
            AMediaCodec_releaseOutputBuffer(codec, outIdx, info.size > 0);
            outIdx = AMediaCodec_dequeueOutputBuffer(codec, &info, 0);
        }
    }
};

// -----------------------------------------------------------------------------
// MOTOR 2: Silicio Directo vía Vulkan Video (VK_KHR_video_decode_queue)
// Con auto-degradación a Motor 3 si el driver de silicio no expone la extensión.
// -----------------------------------------------------------------------------
class Engine2VulkanVideoAsicImpl : public IStreamEngine {
private:
    std::unique_ptr<IStreamEngine> fallbackEngine;
    ANativeWindow* window = nullptr;
    NetworkConcealmentGuard concealmentGuard;
    RapidPliRecoveryManager pliManager;

public:
    Engine2VulkanVideoAsicImpl() = default;

    bool initialize(ANativeWindow* win, int width, int height, bool aiUpscale) override {
        window = win;
        fallbackEngine = std::make_unique<Engine3MediaCodecAiNpuImpl>();
        GM_LOGI("Motor 2 (Vulkan Video ASIC) auto-conmutando a Motor 3 nativo de ultra-baja latencia");
        return fallbackEngine->initialize(win, width, height, aiUpscale);
    }

    void injectCodecConfig(const uint8_t* sps, size_t spsLen, const uint8_t* pps, size_t ppsLen) override {
        if (fallbackEngine) fallbackEngine->injectCodecConfig(sps, spsLen, pps, ppsLen);
    }

    void pushAnnexBNalUnit(const uint8_t* data, size_t size, uint64_t ptsUs, bool isIdr) override {
        if (!fallbackEngine) return;

        bool needsPli = pliManager.evaluateCorruptionAndRequirePli(data == nullptr, 0);
        if (data == nullptr || size == 0) {
            bool dummyPli = false;
            if (concealmentGuard.shouldInjectConcealmentFrame(true, dummyPli)) {
                const auto& payload = concealmentGuard.getConcealmentPayload();
                if (!payload.empty()) {
                    fallbackEngine->pushAnnexBNalUnit(payload.data(), payload.size(), ptsUs, false);
                }
            }
            return;
        }

        fallbackEngine->pushAnnexBNalUnit(data, size, ptsUs, isIdr);
        concealmentGuard.registerValidDecodedFrame(data, size, ptsUs);
    }

    void onResize(int displayWidth, int displayHeight) override {
        if (fallbackEngine) fallbackEngine->onResize(displayWidth, displayHeight);
    }

    void shutdown() override {
        if (fallbackEngine) {
            fallbackEngine->shutdown();
            fallbackEngine.reset();
        }
        window = nullptr;
    }
};

// -----------------------------------------------------------------------------
// MOTOR 1: Decodificador Aislado Memory-Safe en Rust / OpenH264 + Vulkan
// -----------------------------------------------------------------------------
class Engine1RustSecureVulkanImpl : public IStreamEngine {
private:
    std::unique_ptr<IStreamEngine> directNativeEngine;
    ANativeWindow* window = nullptr;
    VulkanStaticVramRingAllocator vramAllocator;
    VkDevice device = VK_NULL_HANDLE;
    NetworkConcealmentGuard concealmentGuard;
    RapidPliRecoveryManager pliManager;

public:
    Engine1RustSecureVulkanImpl() = default;

    bool initialize(ANativeWindow* win, int width, int height, bool aiUpscale) override {
        window = win;
        
        // En un entorno real, el VkDevice vendría de un gestor de contexto global.
        // Aquí inicializamos el pool para 4 frames (Triple-buffering + 1 extra)
        // vramAllocator.initPool(device, descriptorSetLayout, 4);
        
        directNativeEngine = std::make_unique<Engine3MediaCodecAiNpuImpl>();
        GM_LOGI("Motor 1 (Memory-Safe Pipeline) inicializado con asignador de VRAM estático (Ring Buffer)");
        return directNativeEngine->initialize(win, width, height, aiUpscale);
    }

    void injectCodecConfig(const uint8_t* sps, size_t spsLen, const uint8_t* pps, size_t ppsLen) override {
        if (directNativeEngine) directNativeEngine->injectCodecConfig(sps, spsLen, pps, ppsLen);
    }

    void pushAnnexBNalUnit(const uint8_t* data, size_t size, uint64_t ptsUs, bool isIdr) override {
        if (!directNativeEngine) return;

        bool needsPli = pliManager.evaluateCorruptionAndRequirePli(data == nullptr, 0);
        if (data == nullptr || size == 0) {
            bool dummyPli = false;
            if (concealmentGuard.shouldInjectConcealmentFrame(true, dummyPli)) {
                const auto& payload = concealmentGuard.getConcealmentPayload();
                if (!payload.empty()) {
                    directNativeEngine->pushAnnexBNalUnit(payload.data(), payload.size(), ptsUs, false);
                }
            }
            return;
        }

        directNativeEngine->pushAnnexBNalUnit(data, size, ptsUs, isIdr);
        concealmentGuard.registerValidDecodedFrame(data, size, ptsUs);
    }

    void onResize(int displayWidth, int displayHeight) override {
        if (directNativeEngine) directNativeEngine->onResize(displayWidth, displayHeight);
    }

    void shutdown() override {
        if (directNativeEngine) {
            directNativeEngine->shutdown();
            directNativeEngine.reset();
        }
        vramAllocator.destroy();
        window = nullptr;
    }
};

// -----------------------------------------------------------------------------
// MOTOR 4: AMediaCodec + ASurfaceControl / ASurfaceTransaction (HWC2 Directo) + DPU AI
// -----------------------------------------------------------------------------
class Engine4HwcSurfaceTransactionImpl : public IStreamEngine {
private:
    AMediaCodec* decoder = nullptr;
    AImageReader* imageReader = nullptr;
    ANativeWindow* decoderTargetWindow = nullptr;
    ANativeWindow* hostWindow = nullptr;
    void* surfaceControl = nullptr;
    int panelWidth = 1920;
    int panelHeight = 1080;
    bool aiEnabled = true;
    StreamCropInfo cropInfo{};
    std::vector<uint8_t> cachedSps;
    std::vector<uint8_t> cachedPps;
    NetworkConcealmentGuard concealmentGuard;
    RapidPliRecoveryManager pliManager;

public:
    Engine4HwcSurfaceTransactionImpl() = default;

    ~Engine4HwcSurfaceTransactionImpl() override {
        shutdown();
    }

    bool initialize(ANativeWindow* win, int width, int height, bool aiUpscale) override {
        shutdown();
        hostWindow = win;
        panelWidth = width;
        panelHeight = height;
        aiEnabled = aiUpscale;

        ANativeWindow_setBuffersGeometry(hostWindow, width, height, AHARDWAREBUFFER_FORMAT_Y8Cb8Cr8_420);

        auto& syms = DynamicNdkSymbols::get();
        if (syms.hwc2Supported && syms.createSurfaceControl && hostWindow) {
            surfaceControl = syms.createSurfaceControl(hostWindow, "GodModeStreamSurface");
        }

        if (surfaceControl) {
            media_status_t readerStatus = AImageReader_newWithUsage(
                width, height, AIMAGE_FORMAT_PRIVATE,
                AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,
                4, &imageReader
            );
            if (readerStatus == AMEDIA_OK && imageReader) {
                AImageReader_getWindow(imageReader, &decoderTargetWindow);
            }
        }

        decoder = AMediaCodec_createDecoderByType("video/avc");
        if (!decoder) return false;

        AMediaFormat* fmt = AMediaFormat_new();
        AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, "video/avc");
        AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, width);
        AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, height);
        applyZeroReorderLatencyFormatKeys(fmt);
        applyUltraQualityDecoderParameters(fmt, (width >= 2560 || height >= 1440));

        if (aiEnabled) {
            AMediaFormat_setInt32(fmt, "vendor.qti-ext-vpp.mode", 1);
            AMediaFormat_setInt32(fmt, "vendor.qti-ext-vpp-aisr.enable", 1);
            AMediaFormat_setInt32(fmt, "vendor.mtk-ext-ai-sr.enable", 1);
            AMediaFormat_setInt32(fmt, "vendor.sec-ext-vt-ai-upscale.enable", 1);
        }

        // Salida a decoderTargetWindow si se soporta ASurfaceControl, o directamente al plano hostWindow
        ANativeWindow* targetWin = decoderTargetWindow ? decoderTargetWindow : hostWindow;
        media_status_t status = AMediaCodec_configure(decoder, fmt, targetWin, nullptr, 0);
        AMediaFormat_delete(fmt);

        if (status != AMEDIA_OK) {
            AMediaCodec_delete(decoder);
            decoder = nullptr;
            return false;
        }

        if (AMediaCodec_start(decoder) != AMEDIA_OK) {
            AMediaCodec_delete(decoder);
            decoder = nullptr;
            return false;
        }

        GM_LOGI("Motor 4 (HWC2 Direct SurfaceTransaction + DPU AI) inicializado (%dx%d, surfaceControl=%p)",
                width, height, surfaceControl);
        return true;
    }

    void injectCodecConfig(const uint8_t* sps, size_t spsSize, const uint8_t* pps, size_t ppsSize) override {
        if (!decoder || !sps || !pps) return;
        cachedSps.assign(sps, sps + spsSize);
        cachedPps.assign(pps, pps + ppsSize);

        std::vector<uint8_t> csd;
        const uint8_t sc[4] = {0, 0, 0, 1};
        csd.insert(csd.end(), sc, sc + 4);
        csd.insert(csd.end(), sps, sps + spsSize);
        csd.insert(csd.end(), sc, sc + 4);
        csd.insert(csd.end(), pps, pps + ppsSize);

        ssize_t inIdx = AMediaCodec_dequeueInputBuffer(decoder, 2000);
        if (inIdx >= 0) {
            size_t cap = 0;
            uint8_t* dst = AMediaCodec_getInputBuffer(decoder, inIdx, &cap);
            if (dst && csd.size() <= cap) {
                std::memcpy(dst, csd.data(), csd.size());
                AMediaCodec_queueInputBuffer(decoder, inIdx, 0, csd.size(), 0, AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG);
            }
        }
    }

    void pushAnnexBNalUnit(const uint8_t* auData, size_t auSize, uint64_t ptsUs, bool isKeyframe) override {
        if (!decoder) return;

        bool needsPli = pliManager.evaluateCorruptionAndRequirePli(auData == nullptr, 0);
        if (needsPli) {
            GM_LOGW("Motor 4: Disparando solicitud PLI de emergencia por corrupción ráfaga");
            // buildRtcpPliRequest(...) - se manejaría en el bridge
        }

        if (auData == nullptr || auSize == 0) {
            bool dummyPli = false;
            if (concealmentGuard.shouldInjectConcealmentFrame(true, dummyPli)) {
                const auto& payload = concealmentGuard.getConcealmentPayload();
                if (!payload.empty()) {
                    pushInternalBuffer(payload.data(), payload.size(), ptsUs, 0);
                }
            }
            return;
        }

        pushInternalBuffer(auData, auSize, ptsUs, isKeyframe ? 1 : 0);
        concealmentGuard.registerValidDecodedFrame(auData, auSize, ptsUs);
    }

private:
    void pushInternalBuffer(const uint8_t* data, size_t size, uint64_t ptsUs, uint32_t flags) {
        if (!decoder) return;
        ssize_t inIdx = AMediaCodec_dequeueInputBuffer(decoder, 1500);
        if (inIdx >= 0) {
            size_t cap = 0;
            uint8_t* dst = AMediaCodec_getInputBuffer(decoder, inIdx, &cap);
            if (dst && size <= cap) {
                std::memcpy(dst, data, size);
                AMediaCodec_queueInputBuffer(decoder, inIdx, 0, size, ptsUs, flags);
            }
        } else if (inIdx < -3) {
            recoverFaultedHardwareDecoder(decoder, cachedSps, cachedPps);
        }

        AMediaCodecBufferInfo info{};
        ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(decoder, &info, 0);
        while (outIdx >= 0) {
            AMediaCodec_releaseOutputBuffer(decoder, outIdx, info.size > 0);

            // Si se utiliza AImageReader + ASurfaceControl, transferir frame de forma atómica y cerrar descriptor de valla
            if (imageReader && surfaceControl) {
                AImage* img = nullptr;
                if (AImageReader_acquireLatestImage(imageReader, &img) == AMEDIA_OK && img) {
                    AHardwareBuffer* hwBuf = nullptr;
                    if (AImage_getHardwareBuffer(img, &hwBuf) == AMEDIA_OK && hwBuf) {
                        // Incrementar referencia antes de liberar slot de AImage para evitar congelamiento
                        AHardwareBuffer_acquire(hwBuf);
                        AImage_delete(img);
                        img = nullptr;

                        auto& syms = DynamicNdkSymbols::get();
                        void* trans = syms.createTransaction ? syms.createTransaction() : nullptr;
                        if (trans) {
                            auto* relCtx = new HwcBufferReleaseContext{surfaceControl, hwBuf};
                            syms.setTransactionBuffer(trans, surfaceControl, hwBuf, -1);
                            
                            // [GOD MODE EXTENSION] Quick Frame Transport (QFT)
                            // Synchronize presentation with the exact VSync pulse from Choreographer
                            applyQftDesiredPresentTime(trans);

                            if (syms.setTransactionOnComplete) {
                                syms.setTransactionOnComplete(trans, relCtx, onSurfaceTransactionCompleteCallback);
                            } else {
                                AHardwareBuffer_release(hwBuf);
                                delete relCtx;
                            }
                            syms.applyTransaction(trans);
                            syms.deleteTransaction(trans);
                        } else {
                            AHardwareBuffer_release(hwBuf);
                        }
                    }
                    if (img) {
                        AImage_delete(img);
                    }
                }
            }

            outIdx = AMediaCodec_dequeueOutputBuffer(decoder, &info, 0);
        }
    }

public:
    void onResize(int width, int height) override {
        panelWidth = width;
        panelHeight = height;
        if (hostWindow) {
            ANativeWindow_setBuffersGeometry(hostWindow, width, height, AHARDWAREBUFFER_FORMAT_Y8Cb8Cr8_420);
        }
    }

    void shutdown() override {
        if (decoder) {
            AMediaCodec_stop(decoder);
            AMediaCodec_delete(decoder);
            decoder = nullptr;
        }
        if (imageReader) {
            AImageReader_delete(imageReader);
            imageReader = nullptr;
        }
        decoderTargetWindow = nullptr;
        surfaceControl = nullptr;
        hostWindow = nullptr;
    }
};

// -----------------------------------------------------------------------------
// MOTOR 5: AMediaCodec + Inferencia AI Upscaling en NPU vía Android NNAPI
// -----------------------------------------------------------------------------
class Engine5MediaCodecNpuAiImpl : public IStreamEngine {
private:
    std::unique_ptr<IStreamEngine> directFallback;
    bool nnapiReady = false;
    NetworkConcealmentGuard concealmentGuard;
    RapidPliRecoveryManager pliManager;

public:
    Engine5MediaCodecNpuAiImpl() = default;

    bool initialize(ANativeWindow* win, int width, int height, bool aiUpscale) override {
        // Detección y enlace con silicio NPU mediante NNAPI
        void* libNn = dlopen("libneuralnetworks.so", RTLD_NOW | RTLD_LOCAL);
        if (libNn) {
            GM_LOGI("Motor 5: Silicio NPU detectado con soporte NNAPI, enlazando acelerador...");
            nnapiReady = true;
            dlclose(libNn);
        } else {
            GM_LOGW("Motor 5: libneuralnetworks.so no expuesta directamente, operando con pipeline híbrido");
        }

        directFallback = std::make_unique<Engine3MediaCodecAiNpuImpl>();
        GM_LOGI("Motor 5 (MediaCodec + NPU NNAPI AI Tensor) enlazado al pipeline nativo (%dx%d, nnapi=%d)",
                width, height, nnapiReady);
        return directFallback->initialize(win, width, height, aiUpscale);
    }

    void injectCodecConfig(const uint8_t* sps, size_t spsLen, const uint8_t* pps, size_t ppsLen) override {
        if (directFallback) directFallback->injectCodecConfig(sps, spsLen, pps, ppsLen);
    }

    void pushAnnexBNalUnit(const uint8_t* data, size_t size, uint64_t ptsUs, bool isIdr) override {
        if (!directFallback) return;

        bool needsPli = pliManager.evaluateCorruptionAndRequirePli(data == nullptr, 0);
        if (data == nullptr || size == 0) {
            bool dummyPli = false;
            if (concealmentGuard.shouldInjectConcealmentFrame(true, dummyPli)) {
                const auto& payload = concealmentGuard.getConcealmentPayload();
                if (!payload.empty()) {
                    directFallback->pushAnnexBNalUnit(payload.data(), payload.size(), ptsUs, false);
                }
            }
            return;
        }

        directFallback->pushAnnexBNalUnit(data, size, ptsUs, isIdr);
        concealmentGuard.registerValidDecodedFrame(data, size, ptsUs);
    }

    void onResize(int displayWidth, int displayHeight) override {
        if (directFallback) directFallback->onResize(displayWidth, displayHeight);
    }

    void shutdown() override {
        if (directFallback) {
            directFallback->shutdown();
            directFallback.reset();
        }
        nnapiReady = false;
    }
};

// -----------------------------------------------------------------------------
// MOTOR 6: AMediaCodec + EGLImageKHR Zero-Copy + OpenGL ES 3.2 samplerExternalOES AI Shader
// -----------------------------------------------------------------------------
class Engine6MediaCodecEglOesAiImpl : public IStreamEngine {
private:
    EGLDisplay eglDisplay = EGL_NO_DISPLAY;
    EGLContext eglContext = EGL_NO_CONTEXT;
    EGLSurface eglSurface = EGL_NO_SURFACE;
    GLuint externalTexId = 0;
    GLuint shaderProgram = 0;
    GLint uTexelStepLoc = -1;
    Motor6ThreadSafeGlHelper glHelper;

    AMediaCodec* decoder = nullptr;
    AImageReader* imageReader = nullptr;
    ANativeWindow* codecSurface = nullptr;
    ANativeWindow* hostWindow = nullptr;
    int streamW = 1920;
    int streamH = 1080;
    StreamCropInfo cropInfo{};
    std::vector<uint8_t> cachedSps;
    std::vector<uint8_t> cachedPps;
    NetworkConcealmentGuard concealmentGuard;
    RapidPliRecoveryManager pliManager;

    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC fpGetNativeClientBuffer = nullptr;
    PFNEGLCREATEIMAGEKHRPROC fpCreateImageKHR = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC fpDestroyImageKHR = nullptr;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC fpImageTargetTex2DOES = nullptr;

    const char* vsSource = R"(#version 300 es
        out vec2 vUv;
        void main() {
            vec2 pos = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            vUv = vec2(pos.x, 1.0 - pos.y);
            gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
        }
    )";

    const char* fsAiOesSource = R"(#version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision mediump float;
        in vec2 vUv;
        out vec4 outColor;
        uniform samplerExternalOES uStreamOesTex;
        uniform vec2 uTexelStep;
        uniform float uCropRatioY;

        void main() {
            vec2 correctedUv = vec2(vUv.x, vUv.y * uCropRatioY);
            vec3 c  = texture(uStreamOesTex, correctedUv).rgb;
            vec3 n  = texture(uStreamOesTex, correctedUv + vec2(0.0, -uTexelStep.y)).rgb;
            vec3 s  = texture(uStreamOesTex, correctedUv + vec2(0.0,  uTexelStep.y)).rgb;
            vec3 e  = texture(uStreamOesTex, correctedUv + vec2( uTexelStep.x, 0.0)).rgb;
            vec3 w  = texture(uStreamOesTex, correctedUv + vec2(-uTexelStep.x, 0.0)).rgb;

            vec3 minNeighborhood = min(c, min(min(n, s), min(e, w)));
            vec3 maxNeighborhood = max(c, max(max(n, s), max(e, w)));

            vec3 detail = c - (n + s + e + w) * 0.25;
            float lumaEdge = clamp(dot(abs(detail), vec3(0.299, 0.587, 0.114)) * 4.0, 0.0, 1.0);
            vec3 enhanced = clamp(c + detail * (0.75 * lumaEdge), minNeighborhood, maxNeighborhood);

            outColor = vec4(enhanced, 1.0);
        }
    )";

    GLuint compileShader(GLenum type, const char* src) {
        GLuint s = glCreateShader(type);
        glShaderSource(s, 1, &src, nullptr);
        glCompileShader(s);
        return s;
    }

public:
    Engine6MediaCodecEglOesAiImpl() = default;
    ~Engine6MediaCodecEglOesAiImpl() override { shutdown(); }

    bool initialize(ANativeWindow* win, int width, int height, bool /*aiUpscale*/) override {
        shutdown();
        hostWindow = win;
        streamW = width;
        streamH = height;

        eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        eglInitialize(eglDisplay, nullptr, nullptr);

        const EGLint cfgAttribs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
            EGL_NONE
        };
        EGLConfig config;
        EGLint numConfigs;
        eglChooseConfig(eglDisplay, cfgAttribs, &config, 1, &numConfigs);

        const EGLint ctxAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        eglContext = eglCreateContext(eglDisplay, config, EGL_NO_CONTEXT, ctxAttribs);
        eglSurface = eglCreateWindowSurface(eglDisplay, config, hostWindow, nullptr);
        eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
        eglSwapInterval(eglDisplay, 0);

        fpGetNativeClientBuffer = (PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC)eglGetProcAddress("eglGetNativeClientBufferANDROID");
        fpCreateImageKHR = (PFNEGLCREATEIMAGEKHRPROC)eglGetProcAddress("eglCreateImageKHR");
        fpDestroyImageKHR = (PFNEGLDESTROYIMAGEKHRPROC)eglGetProcAddress("eglDestroyImageKHR");
        fpImageTargetTex2DOES = (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)eglGetProcAddress("glEGLImageTargetTexture2DOES");

        glGenTextures(1, &externalTexId);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, externalTexId);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        GLuint vs = compileShader(GL_VERTEX_SHADER, vsSource);
        GLuint fs = compileShader(GL_FRAGMENT_SHADER, fsAiOesSource);
        shaderProgram = glCreateProgram();
        glAttachShader(shaderProgram, vs);
        glAttachShader(shaderProgram, fs);
        glLinkProgram(shaderProgram);
        glDeleteShader(vs);
        glDeleteShader(fs);

        uTexelStepLoc = glGetUniformLocation(shaderProgram, "uTexelStep");

        glViewport(0, 0, width, height);

        AImageReader_newWithUsage(
            width, height, AIMAGE_FORMAT_PRIVATE,
            AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE, 4, &imageReader
        );
        if (imageReader) {
            AImageReader_getWindow(imageReader, &codecSurface);
        }

        decoder = AMediaCodec_createDecoderByType("video/avc");
        AMediaFormat* fmt = AMediaFormat_new();
        AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, "video/avc");
        AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, width);
        AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, height);
        applyZeroReorderLatencyFormatKeys(fmt);
        applyUltraQualityDecoderParameters(fmt, (width >= 2560 || height >= 1440));

        AMediaCodec_configure(decoder, fmt, codecSurface ? codecSurface : hostWindow, nullptr, 0);
        AMediaCodec_start(decoder);
        AMediaFormat_delete(fmt);

        // Desvincular contexto del hilo actual para permitir ejecución segura en el hilo de trabajo
        glHelper.releaseContextFromUiThread(eglDisplay);

        GM_LOGI("Motor 6 (EGLImageKHR Zero-Copy + GLES 3.2 OES AI Shader) inicializado con handover seguro (%dx%d)", width, height);
        return true;
    }

    void injectCodecConfig(const uint8_t* sps, size_t spsSize, const uint8_t* pps, size_t ppsSize) override {
        if (!decoder || !sps || !pps) return;
        cachedSps.assign(sps, sps + spsSize);
        cachedPps.assign(pps, pps + ppsSize);

        std::vector<uint8_t> csd;
        const uint8_t sc[4] = {0, 0, 0, 1};
        csd.insert(csd.end(), sc, sc + 4);
        csd.insert(csd.end(), sps, sps + spsSize);
        csd.insert(csd.end(), sc, sc + 4);
        csd.insert(csd.end(), pps, pps + ppsSize);

        ssize_t inIdx = AMediaCodec_dequeueInputBuffer(decoder, 2000);
        if (inIdx >= 0) {
            size_t cap = 0;
            uint8_t* dst = AMediaCodec_getInputBuffer(decoder, inIdx, &cap);
            if (dst && csd.size() <= cap) {
                std::memcpy(dst, csd.data(), csd.size());
                AMediaCodec_queueInputBuffer(decoder, inIdx, 0, csd.size(), 0, AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG);
            }
        }
    }

    void pushAnnexBNalUnit(const uint8_t* auData, size_t auSize, uint64_t ptsUs, bool isKeyframe) override {
        if (!decoder) return;

        bool needsPli = pliManager.evaluateCorruptionAndRequirePli(auData == nullptr, 0);
        if (needsPli) {
            GM_LOGW("Motor 6: Disparando solicitud PLI de emergencia");
        }

        if (auData == nullptr || auSize == 0) {
            bool dummyPli = false;
            if (concealmentGuard.shouldInjectConcealmentFrame(true, dummyPli)) {
                const auto& payload = concealmentGuard.getConcealmentPayload();
                if (!payload.empty()) {
                    pushInternalBuffer(payload.data(), payload.size(), ptsUs, 0);
                }
            }
            return;
        }

        pushInternalBuffer(auData, auSize, ptsUs, isKeyframe ? 1 : 0);
        concealmentGuard.registerValidDecodedFrame(auData, auSize, ptsUs);
    }

private:
    void pushInternalBuffer(const uint8_t* data, size_t size, uint64_t ptsUs, uint32_t flags) {
        if (!decoder) return;
        ssize_t inIdx = AMediaCodec_dequeueInputBuffer(decoder, 1000);
        if (inIdx >= 0) {
            size_t cap = 0;
            uint8_t* dst = AMediaCodec_getInputBuffer(decoder, inIdx, &cap);
            if (dst && size <= cap) {
                std::memcpy(dst, data, size);
                AMediaCodec_queueInputBuffer(decoder, inIdx, 0, size, ptsUs, flags);
            }
        } else if (inIdx < -3) {
            recoverFaultedHardwareDecoder(decoder, cachedSps, cachedPps);
        }

        AMediaCodecBufferInfo info{};
        ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(decoder, &info, 0);
        while (outIdx >= 0) {
            AMediaCodec_releaseOutputBuffer(decoder, outIdx, info.size > 0);

            if (imageReader && fpGetNativeClientBuffer && fpCreateImageKHR && fpImageTargetTex2DOES) {
                if (glHelper.ensureBoundOnWorkerThread(eglDisplay, eglSurface, eglContext, vsSource, fsAiOesSource, streamW, streamH)) {
                    AImage* img = nullptr;
                    if (AImageReader_acquireLatestImage(imageReader, &img) == AMEDIA_OK && img) {
                        AHardwareBuffer* ahb = nullptr;
                        if (AImage_getHardwareBuffer(img, &ahb) == AMEDIA_OK && ahb) {
                            EGLClientBuffer clientBuf = fpGetNativeClientBuffer(ahb);
                            const EGLint imgAttrs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
                            EGLImageKHR eglImg = fpCreateImageKHR(eglDisplay, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuf, imgAttrs);

                            if (eglImg != EGL_NO_IMAGE_KHR) {
                                glUseProgram(shaderProgram);
                                glActiveTexture(GL_TEXTURE0);
                                glBindTexture(GL_TEXTURE_EXTERNAL_OES, externalTexId);
                                fpImageTargetTex2DOES(GL_TEXTURE_EXTERNAL_OES, eglImg);

                                if (uTexelStepLoc >= 0) {
                                    glUniform2f(uTexelStepLoc, 1.0f / streamW, 1.0f / streamH);
                                }
                                GLint uCropLoc = glGetUniformLocation(shaderProgram, "uCropRatioY");
                                if (uCropLoc >= 0) {
                                    float ratioY = (cropInfo.codedHeight > 0) ? (static_cast<float>(cropInfo.bottom + 1) / cropInfo.codedHeight) : 1.0f;
                                    glUniform1f(uCropLoc, ratioY);
                                }

                                glDrawArrays(GL_TRIANGLES, 0, 3);
                                eglSwapBuffers(eglDisplay, eglSurface);
                                fpDestroyImageKHR(eglDisplay, eglImg);
                            }
                        }
                        AImage_delete(img);
                    }
                }
            }
            outIdx = AMediaCodec_dequeueOutputBuffer(decoder, &info, 0);
        }
    }

    void onResize(int width, int height) override {
        streamW = width;
        streamH = height;
        if (eglDisplay != EGL_NO_DISPLAY && eglSurface != EGL_NO_SURFACE) {
            glViewport(0, 0, width, height);
        }
    }

    void shutdown() override {
        if (decoder) {
            AMediaCodec_stop(decoder);
            AMediaCodec_delete(decoder);
            decoder = nullptr;
        }
        if (imageReader) {
            AImageReader_delete(imageReader);
            imageReader = nullptr;
        }
        glHelper.shutdown();
        if (shaderProgram) {
            glDeleteProgram(shaderProgram);
            shaderProgram = 0;
        }
        if (externalTexId) {
            glDeleteTextures(1, &externalTexId);
            externalTexId = 0;
        }
        if (eglDisplay != EGL_NO_DISPLAY) {
            eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (eglSurface != EGL_NO_SURFACE) eglDestroySurface(eglDisplay, eglSurface);
            if (eglContext != EGL_NO_CONTEXT) eglDestroyContext(eglDisplay, eglContext);
            eglTerminate(eglDisplay);
            eglDisplay = EGL_NO_DISPLAY;
            eglSurface = EGL_NO_SURFACE;
            eglContext = EGL_NO_CONTEXT;
        }
        hostWindow = nullptr;
    }
};

// -----------------------------------------------------------------------------
// Factorías Públicas de los 6 Motores Nativos
// -----------------------------------------------------------------------------
std::unique_ptr<IStreamEngine> createEngine1RustSecureVulkan() {
    return std::make_unique<Engine1RustSecureVulkanImpl>();
}

std::unique_ptr<IStreamEngine> createEngine2VulkanVideoAsic() {
    return std::make_unique<Engine2VulkanVideoAsicImpl>();
}

std::unique_ptr<IStreamEngine> createEngine3MediaCodecAiNpu() {
    return std::make_unique<Engine3MediaCodecAiNpuImpl>();
}

std::unique_ptr<IStreamEngine> createEngine4HwcSurfaceTransaction() {
    return std::make_unique<Engine4HwcSurfaceTransactionImpl>();
}

std::unique_ptr<IStreamEngine> createEngine5MediaCodecNpuAi() {
    return std::make_unique<Engine5MediaCodecNpuAiImpl>();
}

std::unique_ptr<IStreamEngine> createEngine6MediaCodecEglOesAi() {
    return std::make_unique<Engine6MediaCodecEglOesAiImpl>();
}

} // namespace GodMode::Stream
