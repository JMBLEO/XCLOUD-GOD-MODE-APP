#pragma once

#include "godmode_primitives_v2.hpp"
#include <vulkan/vulkan.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <algorithm>

// ============================================================================
// 13. COMPACT HEALTH SCORE (Normalizado a rango 0 - 100)
// ============================================================================
struct HealthScore {
    uint8_t network{100};
    uint8_t decoder{100};
    uint8_t renderer{100};
    uint8_t thermal{100};
    uint8_t memory{100};
    uint8_t total{100};

    static HealthScore compute(float jitterMs, float lossRatio,
                               bool decOk, bool rendOk,
                               float tempCelsius, uint64_t ramMb) noexcept {
        HealthScore s;

        // Red: evalúa fluctuación (jitter) y pérdida de paquetes
        float netScore = 100.0f - (jitterMs * 2.0f) - (lossRatio * 300.0f);
        s.network = static_cast<uint8_t>(std::clamp(netScore, 0.0f, 100.0f));

        // Decodificador
        s.decoder = decOk ? 100 : 10;

        // Renderizador
        s.renderer = rendOk ? 100 : 15;

        // Térmico
        if (tempCelsius < 38.0f) s.thermal = 100;
        else if (tempCelsius < 42.0f) s.thermal = 75;
        else if (tempCelsius < 45.0f) s.thermal = 40;
        else s.thermal = 10;

        // Memoria: penaliza si supera 1.5 GB
        s.memory = (ramMb < 1500) ? 100 : static_cast<uint8_t>(std::max(10, 100 - static_cast<int>((ramMb - 1500) / 10)));

        s.total = static_cast<uint8_t>(
            (s.network * 0.25f) +
            (s.decoder * 0.25f) +
            (s.renderer * 0.25f) +
            (s.thermal * 0.15f) +
            (s.memory * 0.10f)
        );
        return s;
    }
};

// ============================================================================
// 14. SNAPSHOT DE MIGRACION V2 (Transferencia de estado atómica y completa)
// ============================================================================
struct EngineTransferSnapshotV2 {
    uint64_t frameNumber{0};
    uint64_t lastPtsUs{0};
    int width{1920};
    int height{1080};
    int fps{60};
    bool isHevc{false};
    HealthScore currentHealth{};
    float thermalCelsius{36.5f};
    uint32_t governorMode{0};    // Estado del regulador NPU (NpuGovernor::Mode)
    uint32_t aiLoadLevel{0};     // Nivel de procesamiento neuronal (AILoad)
    uint32_t bufferedFrames{0};
    uint32_t activeRecoveryLevel{0};
};

struct EngineHealthSnapshot {
    uint64_t framesReceived{0};
    uint64_t framesPresented{0};
    uint64_t frameDrops{0};
    uint64_t decoderResets{0};
    uint64_t rendererResets{0};
    float gpuUsage{0.0f};
    float memoryPressure{0.0f};
    float thermalState{0.0f};
    bool decoderHealthy{true};
    bool rendererHealthy{true};
    HealthScore score{};
};

struct EngineMetrics {
    float fps{0.0f};
    float avgFrameLatencyUs{0.0f};
    uint64_t totalDrops{0};
    uint32_t recoveryEventsCount{0};
    EngineState currentState{EngineState::Idle};
    HealthScore healthScore{};
};

// ============================================================================
// 7. VULKAN PIPELINE WATCHDOG RIGUROSO (Verificación real de bloqueo de colas)
// ============================================================================
class RealVulkanPipelineWatchdog {
private:
    uint64_t lastSubmitTimestampNs_{0};
    uint64_t maxQueueStallDurationNs_{0};
    bool queueResponsive_{true};

public:
    void recordSubmission() noexcept {
        lastSubmitTimestampNs_ = getMonotonicTimeNs();
    }

    void auditQueueStatus(VkDevice device, VkQueue /*queue*/, VkFence presentationFence) noexcept {
        if (presentationFence != VK_NULL_HANDLE && device != VK_NULL_HANDLE) {
            // Sondeo con timeout explícito de 33 ms
            VkResult res = vkWaitForFences(device, 1, &presentationFence, VK_TRUE, 33'000'000ULL);
            if (res == VK_TIMEOUT) {
                uint64_t stall = getMonotonicTimeNs() - lastSubmitTimestampNs_;
                maxQueueStallDurationNs_ = std::max(maxQueueStallDurationNs_, stall);
                queueResponsive_ = (stall < 100'000'000ULL); // Umbral de bloqueo a 100 ms
            } else if (res == VK_SUCCESS) {
                queueResponsive_ = true;
                maxQueueStallDurationNs_ = 0;
            } else {
                queueResponsive_ = false;
            }
        }
    }

    [[nodiscard]] bool isHealthy() const noexcept {
        return queueResponsive_;
    }

    [[nodiscard]] uint64_t getMaxStallUs() const noexcept {
        return maxQueueStallDurationNs_ / 1000ULL;
    }
};

// ============================================================================
// 8. EGL HEALTH AUDITOR RIGUROSO (Verificación con eglGetSyncAttribKHR)
// ============================================================================
class RealEglHealthAuditor {
private:
    PFNEGLGETSYNCATTRIBKHRPROC pfnGetSyncAttribKHR_{nullptr};

public:
    RealEglHealthAuditor() {
        pfnGetSyncAttribKHR_ = reinterpret_cast<PFNEGLGETSYNCATTRIBKHRPROC>(
            eglGetProcAddress("eglGetSyncAttribKHR"));
    }

    bool verifySyncObject(EGLDisplay display, EGLSyncKHR sync) noexcept {
        if (display == EGL_NO_DISPLAY || sync == EGL_NO_SYNC_KHR) {
            return false;
        }
        if (pfnGetSyncAttribKHR_) {
            EGLint status = 0;
            if (pfnGetSyncAttribKHR_(display, sync, EGL_SYNC_STATUS_KHR, &status) == EGL_TRUE) {
                return (status == EGL_SIGNALED_KHR || status == EGL_UNSIGNALED_KHR);
            }
            return false;
        }
        return true;
    }

    bool verifyContextIntegrity(EGLDisplay display, EGLContext context) noexcept {
        if (display == EGL_NO_DISPLAY || context == EGL_NO_CONTEXT) {
            return false;
        }
        return (eglGetCurrentContext() == context);
    }
};
