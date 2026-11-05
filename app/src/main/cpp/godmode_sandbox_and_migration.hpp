#pragma once

#include "godmode_observability_v2.hpp"
#include <thread>
#include <shared_mutex>
#include <memory>

class IResilientMediaEngine : public EngineStateMachine {
public:
    virtual ~IResilientMediaEngine() = default;
    virtual bool initialize(NativeWindowRef window, int width, int height, int fps, bool isHevc) = 0;
    virtual bool processFrameFromPool(const uint8_t* nalData, size_t size, uint64_t ptsUs, float tempCelsius) = 0;
    virtual void level1FlushQueues() = 0;
    virtual bool level2ResetDecoder() = 0;
    virtual bool level3ResetRenderer(NativeWindowRef window) = 0;
    virtual EngineHealthSnapshot health() const = 0;
    virtual EngineMetrics metrics() const = 0;
    virtual EngineTransferSnapshotV2 exportSnapshotV2() = 0;
    virtual bool importSnapshotV2(const EngineTransferSnapshotV2& snapshot) = 0;
};

// Factory declaration for resilient engines
std::unique_ptr<IResilientMediaEngine> createResilientEngine(int engineId);

// ============================================================================
// 10 & 3. SANDBOX CON SEMÁFORO SEGURO Y MIGRACIÓN LIBRE DE CARRERAS
// ============================================================================
class EngineSandbox {
private:
    mutable std::shared_mutex engineLifecycleMutex_;
    std::unique_ptr<IResilientMediaEngine> activeEngine_{nullptr};

    FrameMemoryPool framePool_{};
    MonotonicRingBuffer<FrameDescriptor, 64> descriptorQueue_{};
    ResilientSemaphore<64> frameAvailableSignal_{0};

    std::thread workerThread_;
    std::atomic_bool isRunning_{false};
    ComprehensiveRecoveryBudget recoveryBudget_{};

public:
    void start(std::unique_ptr<IResilientMediaEngine> engine, NativeWindowRef window,
               int w, int h, int fps, bool isHevc) {
        {
            std::unique_lock<std::shared_mutex> lock(engineLifecycleMutex_);
            activeEngine_ = std::move(engine);
            if (activeEngine_) {
                activeEngine_->initialize(std::move(window), w, h, fps, isHevc);
            }
        }

        isRunning_.store(true, std::memory_order_release);

        workerThread_ = std::thread([this]() {
            while (isRunning_.load(std::memory_order_acquire)) {
                // Bloqueo eficiente de hilo: 0% de uso de CPU mientras no haya paquetes
                if (!frameAvailableSignal_.try_acquire_for(std::chrono::milliseconds(250))) {
                    continue;
                }

                FrameDescriptor desc;
                if (!descriptorQueue_.pop(desc)) {
                    continue;
                }

                const uint8_t* payload = framePool_.getSlotData(desc.poolIndex);
                if (payload) {
                    uint64_t startUs = getMonotonicTimeNs() / 1000ULL;

                    // Acceso concurrente de lectura: seguro frente a cambios de motor
                    {
                        std::shared_lock<std::shared_mutex> readLock(engineLifecycleMutex_);
                        if (activeEngine_) {
                            activeEngine_->processFrameFromPool(payload, desc.size, desc.ptsUs, 36.5f);
                        }
                    }

                    uint64_t elapsedUs = (getMonotonicTimeNs() / 1000ULL) - startUs;
                    DeadlineSeverity severity = DeadlineWatchdog::auditFrameDuration(elapsedUs);
                    if (severity == DeadlineSeverity::RecoveryTrigger_100ms) {
                        LOGW("[Sandbox] Frame excedió 100 ms de latencia. Evaluando intervención.");
                    }
                }

                // Liberación de slot hacia el pool global
                framePool_.releaseSlot(desc.poolIndex);
            }
        });
    }

    bool submitIncomingPacket(const uint8_t* data, size_t size, uint64_t ptsUs, uint64_t seq) {
        if (!isRunning_.load(std::memory_order_acquire)) return false;

        auto slotOpt = framePool_.acquireSlot(data, size);
        if (!slotOpt.has_value()) {
            return false; // Pool saturado: descarte seguro
        }

        FrameDescriptor desc{
            .poolIndex = slotOpt.value(),
            .size = static_cast<uint32_t>(size),
            .ptsUs = ptsUs,
            .sequenceNumber = seq
        };

        if (!descriptorQueue_.push(desc)) {
            framePool_.releaseSlot(desc.poolIndex);
            return false;
        }

        frameAvailableSignal_.release();
        return true;
    }

    // 3. MIGRACIÓN FORMAL DE MOTOR SIN CONDICIÓN DE CARRERA
    bool migrateToEngine(std::unique_ptr<IResilientMediaEngine> targetEngine, NativeWindowRef newWindow) {
        if (!targetEngine) return false;

        if (!recoveryBudget_.allowEngineMigration()) {
            LOGW("[Sandbox] Migración bloqueada: superó el presupuesto (RecoveryBudget).");
            return false;
        }

        // Bloqueo exclusivo: garantiza que el worker thread haya finalizado el procesamiento del frame actual
        std::unique_lock<std::shared_mutex> exclusiveLock(engineLifecycleMutex_);

        if (!activeEngine_) return false;

        LOGI("[Sandbox] Ejecutando protocolo de migración en caliente.");
        activeEngine_->transition(EngineState::Suspended);

        // 1. Exportación de snapshot V2
        EngineTransferSnapshotV2 snapshot = activeEngine_->exportSnapshotV2();
        snapshot.bufferedFrames = static_cast<uint32_t>(descriptorQueue_.size());

        // 2. Inicialización del motor destino
        targetEngine->initialize(std::move(newWindow), snapshot.width, snapshot.height, snapshot.fps, snapshot.isHevc);
        targetEngine->importSnapshotV2(snapshot);

        // 3. Reemplazo atómico del puntero
        std::unique_ptr<IResilientMediaEngine> oldEngine = std::move(activeEngine_);
        activeEngine_ = std::move(targetEngine);

        // 4. Reactivación
        activeEngine_->transition(EngineState::Streaming);
        LOGI("[Sandbox] Migración completada con éxito. Destruyendo motor previo.");

        // El motor antiguo es desasignado fuera del hilo crítico de trabajo
        oldEngine->level1FlushQueues();
        return true;
    }

    [[nodiscard]] EngineHealthSnapshot queryHealth() const {
        std::shared_lock<std::shared_mutex> readLock(engineLifecycleMutex_);
        if (activeEngine_) return activeEngine_->health();
        return EngineHealthSnapshot{};
    }

    void stop() {
        isRunning_.store(false, std::memory_order_release);
        frameAvailableSignal_.release();

        if (workerThread_.joinable()) {
            workerThread_.join();
        }

        std::unique_lock<std::shared_mutex> exclusiveLock(engineLifecycleMutex_);
        if (activeEngine_) {
            activeEngine_->level1FlushQueues();
            activeEngine_.reset();
        }
    }

    ~EngineSandbox() {
        stop();
    }
};
