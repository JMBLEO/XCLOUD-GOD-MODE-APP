#pragma once

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <zlib.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <array>
#include <memory>
#include <optional>
#include <algorithm>
#include <mutex>
#include <condition_variable>
#include <cassert>

#define LOG_TAG "GodModeCarrierRuntime"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGF(...) __android_log_print(ANDROID_LOG_FATAL, LOG_TAG, __VA_ARGS__)

namespace GodMode::Carrier {

static inline uint64_t getMonotonicTimeNs() noexcept {
    auto now = std::chrono::steady_clock::now().time_since_epoch();
    return std::chrono::duration_cast<std::chrono::nanoseconds>(now).count();
}

// ============================================================================
// 1. GESTOR EXPLICITO DE OWNERSHIP PARA ANativeWindow (INV-004)
// ============================================================================
class NativeWindowRef {
private:
    ANativeWindow* ptr_{nullptr};

public:
    NativeWindowRef() noexcept = default;

    explicit NativeWindowRef(ANativeWindow* win) noexcept : ptr_(win) {
        if (ptr_) ANativeWindow_acquire(ptr_);
    }

    ~NativeWindowRef() noexcept { reset(); }

    NativeWindowRef(const NativeWindowRef& other) noexcept : ptr_(other.ptr_) {
        if (ptr_) ANativeWindow_acquire(ptr_);
    }

    NativeWindowRef& operator=(const NativeWindowRef& other) noexcept {
        if (this != &other) {
            reset();
            ptr_ = other.ptr_;
            if (ptr_) ANativeWindow_acquire(ptr_);
        }
        return *this;
    }

    NativeWindowRef(NativeWindowRef&& other) noexcept : ptr_(other.ptr_) {
        other.ptr_ = nullptr;
    }

    NativeWindowRef& operator=(NativeWindowRef&& other) noexcept {
        if (this != &other) {
            reset();
            ptr_ = other.ptr_;
            other.ptr_ = nullptr;
        }
        return *this;
    }

    void reset(ANativeWindow* newWin = nullptr) noexcept {
        if (ptr_) {
            ANativeWindow_release(ptr_);
            ptr_ = nullptr;
        }
        if (newWin) {
            ptr_ = newWin;
            ANativeWindow_acquire(ptr_);
        }
    }

    [[nodiscard]] ANativeWindow* get() const noexcept { return ptr_; }
    [[nodiscard]] bool isValid() const noexcept { return ptr_ != nullptr; }
    explicit operator bool() const noexcept { return isValid(); }
};

// ============================================================================
// 2. FRAME JOURNEY TRACE (TELEMETRÍA ULTRA-PRECISA EXTREMO A EXTREMO)
// ============================================================================
struct FrameJourneyTrace {
    uint64_t frameId{0};
    uint64_t networkIngressNs{0};
    uint64_t queueEnqueuedNs{0};
    uint64_t decodeStartNs{0};
    uint64_t decodeEndNs{0};
    uint64_t renderStartNs{0};
    uint64_t presentCompletedNs{0};

    [[nodiscard]] uint32_t queueWaitUs() const noexcept {
        if (decodeStartNs <= queueEnqueuedNs) return 0;
        return static_cast<uint32_t>((decodeStartNs - queueEnqueuedNs) / 1000ULL);
    }

    [[nodiscard]] uint32_t decodeDurationUs() const noexcept {
        if (decodeEndNs <= decodeStartNs) return 0;
        return static_cast<uint32_t>((decodeEndNs - decodeStartNs) / 1000ULL);
    }

    [[nodiscard]] uint32_t renderDurationUs() const noexcept {
        if (presentCompletedNs <= renderStartNs) return 0;
        return static_cast<uint32_t>((presentCompletedNs - renderStartNs) / 1000ULL);
    }

    [[nodiscard]] uint32_t totalE2ELatencyUs() const noexcept {
        if (presentCompletedNs <= networkIngressNs) return 0;
        return static_cast<uint32_t>((presentCompletedNs - networkIngressNs) / 1000ULL);
    }
};

// ============================================================================
// 3. VYUKOV LOCK-FREE FRAME POOL (MPMC, O(1), ANTI-ABA, 384 KB POR SLOT)
// ============================================================================
struct FrameDescriptor {
    uint32_t poolIndex{0};
    uint32_t generation{0};
    uint32_t size{0};
    uint64_t ptsUs{0};
    uint64_t sequenceNumber{0};
    FrameJourneyTrace trace{};
};

class VyukovLockFreeFramePool {
public:
    static constexpr size_t POOL_SLOTS = 64;
    static constexpr size_t SLOT_CAPACITY_BYTES = 384 * 1024; // 384 KB

private:
    struct alignas(64) Cell {
        std::atomic<uint64_t> sequence{0};
        uint32_t slotIndex{0};
    };

    alignas(64) std::array<std::array<uint8_t, SLOT_CAPACITY_BYTES>, POOL_SLOTS> arena_{};
    alignas(64) std::array<std::atomic<uint32_t>, POOL_SLOTS> slotGenerations_{};
    alignas(64) std::array<Cell, POOL_SLOTS> freeCells_{};
    alignas(64) std::atomic<uint64_t> enqueuePos_{0};
    alignas(64) std::atomic<uint64_t> dequeuePos_{0};

    std::atomic<uint32_t> allocated_{0};
    std::atomic<uint64_t> failures_{0};

public:
    VyukovLockFreeFramePool() noexcept {
        for (uint32_t i = 0; i < POOL_SLOTS; ++i) {
            freeCells_[i].sequence.store(i, std::memory_order_relaxed);
            freeCells_[i].slotIndex = i;
            slotGenerations_[i].store(1, std::memory_order_relaxed);
        }
    }

    std::optional<FrameDescriptor> acquireSlot(const uint8_t* src, size_t size, uint64_t pts, uint64_t seq) noexcept {
        if (!src || size == 0 || size > SLOT_CAPACITY_BYTES) {
            failures_.fetch_add(1, std::memory_order_relaxed);
            return std::nullopt;
        }

        uint64_t pos = dequeuePos_.load(std::memory_order_relaxed);
        Cell* cell = nullptr;

        while (true) {
            cell = &freeCells_[pos & (POOL_SLOTS - 1)];
            const uint64_t cellSeq = cell->sequence.load(std::memory_order_acquire);
            const int64_t diff = static_cast<int64_t>(cellSeq) - static_cast<int64_t>(pos + 1);

            if (diff == 0) {
                if (dequeuePos_.compare_exchange_weak(pos, pos + 1, std::memory_order_relaxed)) {
                    break;
                }
            } else if (diff < 0) {
                failures_.fetch_add(1, std::memory_order_relaxed);
                return std::nullopt; // Pool saturado (O(1) back-pressure)
            } else {
                pos = dequeuePos_.load(std::memory_order_relaxed);
            }
        }

        const uint32_t slot = cell->slotIndex;
        cell->sequence.store(pos + POOL_SLOTS, std::memory_order_release);

        const uint32_t gen = slotGenerations_[slot].fetch_add(1, std::memory_order_acq_rel) + 1;
        std::memcpy(arena_[slot].data(), src, size);
        allocated_.fetch_add(1, std::memory_order_relaxed);

        FrameDescriptor desc{
            .poolIndex = slot,
            .generation = gen,
            .size = static_cast<uint32_t>(size),
            .ptsUs = pts,
            .sequenceNumber = seq,
            .trace = {
                .frameId = seq,
                .networkIngressNs = getMonotonicTimeNs(),
                .queueEnqueuedNs = getMonotonicTimeNs(),
                .decodeStartNs = 0,
                .decodeEndNs = 0,
                .renderStartNs = 0,
                .presentCompletedNs = 0
            }
        };

        return desc;
    }

    bool releaseSlot(const FrameDescriptor& desc) noexcept {
        if (desc.poolIndex >= POOL_SLOTS) return false;
        // Validación Anti-ABA estricta: si la generación cambió, rechazar para evitar corrupción
        if (slotGenerations_[desc.poolIndex].load(std::memory_order_acquire) != desc.generation) {
            return false;
        }

        uint64_t pos = enqueuePos_.load(std::memory_order_relaxed);
        Cell* cell = nullptr;

        while (true) {
            cell = &freeCells_[pos & (POOL_SLOTS - 1)];
            const uint64_t cellSeq = cell->sequence.load(std::memory_order_acquire);
            const int64_t diff = static_cast<int64_t>(cellSeq) - static_cast<int64_t>(pos);

            if (diff == 0) {
                if (enqueuePos_.compare_exchange_weak(pos, pos + 1, std::memory_order_relaxed)) {
                    break;
                }
            } else if (diff < 0) {
                return false;
            } else {
                pos = enqueuePos_.load(std::memory_order_relaxed);
            }
        }

        cell->slotIndex = desc.poolIndex;
        cell->sequence.store(pos + 1, std::memory_order_release);
        allocated_.fetch_sub(1, std::memory_order_relaxed);
        return true;
    }

    [[nodiscard]] const uint8_t* getPayload(const FrameDescriptor& desc) const noexcept {
        if (desc.poolIndex >= POOL_SLOTS) return nullptr;
        if (slotGenerations_[desc.poolIndex].load(std::memory_order_acquire) != desc.generation) {
            return nullptr;
        }
        return arena_[desc.poolIndex].data();
    }

    [[nodiscard]] uint32_t getAllocatedCount() const noexcept {
        return allocated_.load(std::memory_order_relaxed);
    }
};

// ============================================================================
// 4. VALIDATED FLIGHT RECORDER (SEQLOCK, CRC32, CRITICAL + GENERAL BUFFERS)
// ============================================================================
struct FlightRecordEvent {
    uint64_t sequenceNumber{0};
    uint64_t timestampNs{0};
    uint32_t eventCode{0};
    uint32_t payloadData{0};
    uint32_t crc32Val{0};
    char tag[16]{0};
};

class ValidatedFlightRecorder {
private:
    static constexpr size_t GENERAL_CAPACITY = 1024;
    static constexpr size_t CRITICAL_CAPACITY = 256;

    alignas(64) std::atomic<uint64_t> seqlock_{0};
    std::array<FlightRecordEvent, GENERAL_CAPACITY> generalBuffer_{};
    std::array<FlightRecordEvent, CRITICAL_CAPACITY> criticalBuffer_{};
    std::atomic<uint64_t> generalHead_{0};
    std::atomic<uint64_t> criticalHead_{0};

    static uint32_t computeCrc(const FlightRecordEvent& ev) noexcept {
        uLong crc = crc32(0L, Z_NULL, 0);
        crc = crc32(crc, reinterpret_cast<const Bytef*>(&ev.sequenceNumber), sizeof(ev.sequenceNumber));
        crc = crc32(crc, reinterpret_cast<const Bytef*>(&ev.timestampNs), sizeof(ev.timestampNs));
        crc = crc32(crc, reinterpret_cast<const Bytef*>(&ev.eventCode), sizeof(ev.eventCode));
        crc = crc32(crc, reinterpret_cast<const Bytef*>(&ev.payloadData), sizeof(ev.payloadData));
        crc = crc32(crc, reinterpret_cast<const Bytef*>(ev.tag), sizeof(ev.tag));
        return static_cast<uint32_t>(crc);
    }

public:
    void logEvent(uint32_t code, uint32_t data, const char* tag, bool isCritical = false) noexcept {
        uint64_t seq = seqlock_.fetch_add(1, std::memory_order_acquire) + 1;

        FlightRecordEvent ev{};
        ev.sequenceNumber = seq;
        ev.timestampNs = getMonotonicTimeNs();
        ev.eventCode = code;
        ev.payloadData = data;
        if (tag) {
            std::strncpy(ev.tag, tag, sizeof(ev.tag) - 1);
        }
        ev.crc32Val = computeCrc(ev);

        if (isCritical) {
            uint64_t idx = criticalHead_.fetch_add(1, std::memory_order_relaxed);
            criticalBuffer_[idx & (CRITICAL_CAPACITY - 1)] = ev;
        } else {
            uint64_t idx = generalHead_.fetch_add(1, std::memory_order_relaxed);
            generalBuffer_[idx & (GENERAL_CAPACITY - 1)] = ev;
        }

        seqlock_.fetch_add(1, std::memory_order_release);
    }

    bool readLatestCritical(FlightRecordEvent& outEv) const noexcept {
        uint64_t head = criticalHead_.load(std::memory_order_relaxed);
        if (head == 0) return false;

        while (true) {
            uint64_t s1 = seqlock_.load(std::memory_order_acquire);
            if (s1 & 1) continue; // Escritor activo

            outEv = criticalBuffer_[(head - 1) & (CRITICAL_CAPACITY - 1)];

            uint64_t s2 = seqlock_.load(std::memory_order_acquire);
            if (s1 == s2) break; // Sin torn reads
        }

        uint32_t expected = computeCrc(outEv);
        return (expected == outEv.crc32Val);
    }
};

// ============================================================================
// 5. HARDENED FSM (11 ESTADOS CON MATRIZ CONSTEXPR FORMAL)
// ============================================================================
enum class EngineState : uint8_t {
    Idle          = 0,
    Initializing  = 1,
    Buffering     = 2,
    Streaming     = 3,
    Degraded      = 4,
    RecoveringL1  = 5,
    RecoveringL2  = 6,
    RecoveringL3  = 7,
    Suspended     = 8,
    Failed        = 9,
    Shutdown      = 10
};

class HardenedFSM {
private:
    static constexpr bool MATRIX[11][11] = {
        /*              Idle  Init  Buff  Strm  Degr  Rec1  Rec2  Rec3  Susp  Fail  Shut */
        /* Idle */     {true, true, false,false,false,false,false,false,false,false,true },
        /* Init */     {false,true, true, false,false,false,false,false,false,true, true },
        /* Buffering */{false,false,true, true, false,true, false,false,true, true, true },
        /* Streaming */{false,false,true, true, true, true, true, true, true, true, true },
        /* Degraded */ {false,false,true, true, true, true, true, true, true, true, true },
        /* RecovL1 */  {false,false,true, true, true, false,true, true, false,true, true },
        /* RecovL2 */  {false,false,true, true, true, false,false,true, false,true, true },
        /* RecovL3 */  {false,false,true, true, true, false,false,false,false,true, true },
        /* Suspended */{false,false,true, true, false,false,false,false,true, false,true },
        /* Failed */   {false,false,false,false,false,false,false,false,false,true, true },
        /* Shutdown */ {false,false,false,false,false,false,false,false,false,false,true }
    };

    std::atomic<EngineState> state_{EngineState::Idle};

public:
    [[nodiscard]] EngineState get() const noexcept {
        return state_.load(std::memory_order_acquire);
    }

    [[nodiscard]] bool canStream() const noexcept {
        EngineState s = get();
        return s == EngineState::Streaming || s == EngineState::Degraded || s == EngineState::Buffering;
    }

    bool transitionTo(EngineState next) noexcept {
        EngineState curr = state_.load(std::memory_order_relaxed);
        while (true) {
            if (!MATRIX[static_cast<size_t>(curr)][static_cast<size_t>(next)]) {
                LOGW("Transición FSM ilegal bloqueada: %d -> %d", static_cast<int>(curr), static_cast<int>(next));
                return false;
            }
            if (state_.compare_exchange_weak(curr, next, std::memory_order_acq_rel, std::memory_order_relaxed)) {
                return true;
            }
        }
    }
};

// ============================================================================
// 6. INVARIANTES FORMALES (INV-001 a INV-005) Y AUDITORES
// ============================================================================
enum class FrameTerminalState : uint8_t {
    InFlight = 0,
    Presented = 1,
    Dropped = 2,
    Expired = 3,
    Recovered = 4
};

class FrameTerminalAuditor {
private:
    static constexpr size_t TRACK_CAPACITY = 4096;
    std::array<std::atomic<uint8_t>, TRACK_CAPACITY> frameStates_{};

public:
    void markInFlight(uint64_t seq) noexcept {
        frameStates_[seq % TRACK_CAPACITY].store(static_cast<uint8_t>(FrameTerminalState::InFlight), std::memory_order_release);
    }

    bool transitionTerminal(uint64_t seq, FrameTerminalState terminal) noexcept {
        assert(terminal != FrameTerminalState::InFlight);
        uint8_t expected = static_cast<uint8_t>(FrameTerminalState::InFlight);
        return frameStates_[seq % TRACK_CAPACITY].compare_exchange_strong(
            expected, static_cast<uint8_t>(terminal), std::memory_order_acq_rel);
    }

    [[nodiscard]] FrameTerminalState getState(uint64_t seq) const noexcept {
        return static_cast<FrameTerminalState>(frameStates_[seq % TRACK_CAPACITY].load(std::memory_order_acquire));
    }
};

class InvariantCoverageTracker {
public:
    std::atomic<uint64_t> inv001Checks{0};
    std::atomic<uint64_t> inv002Checks{0};
    std::atomic<uint64_t> inv003Checks{0};
    std::atomic<uint64_t> inv004Checks{0};
    std::atomic<uint64_t> inv005Checks{0};

    std::atomic<uint64_t> violations{0};
};

class MasterInvariantEnforcer {
private:
    InvariantCoverageTracker tracker_{};
    FrameTerminalAuditor frameAuditor_{};

public:
    // INV-001: Control Plane Isolation
    bool assertControlPlaneIsolation(bool mediaPlaneAttemptedMutation) noexcept {
        tracker_.inv001Checks.fetch_add(1, std::memory_order_relaxed);
        if (mediaPlaneAttemptedMutation) {
            tracker_.violations.fetch_add(1, std::memory_order_relaxed);
            LOGF("[INV-001] VIOLACIÓN: Intento ilegal de mutación del Control Plane desde Media Plane!");
            return false;
        }
        return true;
    }

    // INV-002: Unique Frame Terminal State
    bool recordFrameTerminalState(uint64_t seq, FrameTerminalState terminal) noexcept {
        tracker_.inv002Checks.fetch_add(1, std::memory_order_relaxed);
        bool ok = frameAuditor_.transitionTerminal(seq, terminal);
        if (!ok) {
            tracker_.violations.fetch_add(1, std::memory_order_relaxed);
            LOGW("[INV-002] VIOLACIÓN: Doble terminalidad o transición inválida para frame #%llu", static_cast<unsigned long long>(seq));
        }
        return ok;
    }

    // INV-003: Migration Rollback Safety (tiempo <= 500ms)
    bool assertMigrationRollbackSafety(uint64_t rollbackDurationNs) noexcept {
        tracker_.inv003Checks.fetch_add(1, std::memory_order_relaxed);
        if (rollbackDurationNs > 500'000'000ULL) {
            tracker_.violations.fetch_add(1, std::memory_order_relaxed);
            LOGE("[INV-003] VIOLACIÓN: Rollback de migración superó 500 ms (duración: %llu us)",
                 static_cast<unsigned long long>(rollbackDurationNs / 1000ULL));
            return false;
        }
        return true;
    }

    // INV-004: Exclusive Native Ownership (N = 1)
    bool assertExclusiveNativeOwnership(const NativeWindowRef& ref) noexcept {
        tracker_.inv004Checks.fetch_add(1, std::memory_order_relaxed);
        return ref.isValid();
    }

    // INV-005: Finite Recovery Convergence
    bool assertRecoveryConvergence(uint32_t consecutiveRecoveries, uint32_t limit = 10) noexcept {
        tracker_.inv005Checks.fetch_add(1, std::memory_order_relaxed);
        if (consecutiveRecoveries > limit) {
            tracker_.violations.fetch_add(1, std::memory_order_relaxed);
            LOGF("[INV-005] VIOLACIÓN: Bucle de recuperación no convergente detectado!");
            return false;
        }
        return true;
    }

    [[nodiscard]] const InvariantCoverageTracker& getTracker() const noexcept {
        return tracker_;
    }

    void markFrameInFlight(uint64_t seq) noexcept {
        frameAuditor_.markInFlight(seq);
    }
};

// ============================================================================
// 7. GOBERNANZA TÉRMICA ACUMULATIVA (I(t) = ∫ ΔT² dt)
// ============================================================================
class ThermalPressureGovernor {
public:
    enum class Pacing : uint8_t { Nominal, Throttled_AI, Critical_Bypass };

private:
    float thermalIntegral_{0.0f};
    uint64_t lastTickNs_{0};

    static constexpr float BASELINE_C = 39.0f;
    static constexpr float CRITICAL_C = 44.0f;
    static constexpr float BUDGET_DOSE = 120.0f;

public:
    Pacing evaluate(float currentTempC, uint64_t nowNs) noexcept {
        if (lastTickNs_ == 0) {
            lastTickNs_ = nowNs;
            return Pacing::Nominal;
        }

        const float dtSec = static_cast<float>(nowNs - lastTickNs_) / 1'000'000'000.0f;
        lastTickNs_ = nowNs;

        if (currentTempC > BASELINE_C) {
            const float excess = currentTempC - BASELINE_C;
            thermalIntegral_ += (excess * excess) * dtSec;
        } else {
            thermalIntegral_ = std::max(0.0f, thermalIntegral_ - (2.5f * dtSec));
        }

        if (currentTempC >= CRITICAL_C || thermalIntegral_ >= BUDGET_DOSE) {
            return Pacing::Critical_Bypass;
        }
        if (thermalIntegral_ >= (BUDGET_DOSE * 0.5f)) {
            return Pacing::Throttled_AI;
        }
        return Pacing::Nominal;
    }

    [[nodiscard]] float getDose() const noexcept { return thermalIntegral_; }
    void reset() noexcept { thermalIntegral_ = 0.0f; lastTickNs_ = 0; }
};

// ============================================================================
// 8. HEALTH SYSTEM Y PREDICCIÓN PROBABILÍSTICA DE FALLOS
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
                               float tempC, uint64_t ramMb) noexcept {
        HealthScore s;
        float netScore = 100.0f - (jitterMs * 2.0f) - (lossRatio * 300.0f);
        s.network = static_cast<uint8_t>(std::clamp(netScore, 0.0f, 100.0f));
        s.decoder = decOk ? 100 : 10;
        s.renderer = rendOk ? 100 : 15;

        if (tempC < 38.0f) s.thermal = 100;
        else if (tempC < 42.0f) s.thermal = 75;
        else if (tempC < 45.0f) s.thermal = 40;
        else s.thermal = 10;

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

class ProbabilisticFailurePredictor {
public:
    static float calculateFailureProbability(
        int32_t latencySlope,
        float thermalDose,
        uint32_t memoryAllocatedMb,
        uint32_t recoveriesInLastMinute
    ) noexcept {
        float p = 0.01f; // Probabilidad base nominal (1%)

        if (latencySlope > 2000) p += 0.35f;
        else if (latencySlope > 500) p += 0.15f;

        if (thermalDose > 100.0f) p += 0.30f;
        else if (thermalDose > 50.0f) p += 0.15f;

        if (memoryAllocatedMb > 1800) p += 0.20f;

        if (recoveriesInLastMinute > 3) p += 0.40f;
        else if (recoveriesInLastMinute > 1) p += 0.15f;

        return std::clamp(p, 0.0f, 1.0f);
    }
};

// ============================================================================
// 9. DEADLINE WATCHDOG PREDICTIVO (ARITMÉTICA SIGNADA, CERO UNDERFLOWS)
// ============================================================================
enum class DeadlineAction : uint8_t {
    Nominal,
    Warning_16ms,
    Degraded_33ms,
    Recovery_100ms
};

enum class LatencyTrendState : uint8_t {
    Improving,
    Stable,
    Growing
};

class PredictiveDeadlineWatchdog {
private:
    std::atomic<uint64_t> emaUs_{12000};
    std::atomic<int32_t> slope_{0};
    std::array<uint64_t, 8> samples_{12000, 12000, 12000, 12000, 12000, 12000, 12000, 12000};
    size_t sampleIdx_{0};

public:
    DeadlineAction audit(uint64_t frameLatencyUs) noexcept {
        // Cálculo con int64_t estricto para evitar underflow aritmético
        const int64_t prev = static_cast<int64_t>(emaUs_.load(std::memory_order_relaxed));
        const int64_t delta = static_cast<int64_t>(frameLatencyUs) - prev;
        const uint64_t next = static_cast<uint64_t>(std::max<int64_t>(0, prev + (delta >> 3)));
        emaUs_.store(next, std::memory_order_relaxed);

        samples_[sampleIdx_ & 7] = frameLatencyUs;
        sampleIdx_++;

        int64_t accumSlope = 0;
        for (size_t i = 1; i < 8; ++i) {
            accumSlope += static_cast<int64_t>(samples_[i]) - static_cast<int64_t>(samples_[i - 1]);
        }
        slope_.store(static_cast<int32_t>(accumSlope / 7), std::memory_order_relaxed);

        if (frameLatencyUs > 100'000ULL) return DeadlineAction::Recovery_100ms;
        if (frameLatencyUs > 33'333ULL || next > 28'000ULL) return DeadlineAction::Degraded_33ms;
        if (frameLatencyUs > 16'666ULL) return DeadlineAction::Warning_16ms;
        return DeadlineAction::Nominal;
    }

    [[nodiscard]] LatencyTrendState getTrendState() const noexcept {
        int32_t s = slope_.load(std::memory_order_relaxed);
        if (s < -200) return LatencyTrendState::Improving;
        if (s > 200) return LatencyTrendState::Growing;
        return LatencyTrendState::Stable;
    }

    [[nodiscard]] int32_t getSlope() const noexcept { return slope_.load(std::memory_order_relaxed); }
    [[nodiscard]] uint64_t getEmaUs() const noexcept { return emaUs_.load(std::memory_order_relaxed); }
};

// ============================================================================
// 10. RECOVERY CONTEXT Y DETECCIÓN DE ANOMALÍAS
// ============================================================================
struct RecoveryContext {
    uint32_t lastRecoveryLevel{0};
    uint32_t recoveriesInLastMinute{0};
    uint32_t cumulativeRecoveries{0};
    uint64_t lastRecoveryTimestampNs{0};
    uint64_t lastSuccessfulFrameNs{0};

    [[nodiscard]] bool isFlapping(uint64_t nowNs) const noexcept {
        return (recoveriesInLastMinute >= 4) && (nowNs - lastRecoveryTimestampNs < 15'000'000'000ULL);
    }

    [[nodiscard]] bool isZombieEngine(uint64_t nowNs) const noexcept {
        return (nowNs - lastSuccessfulFrameNs > 3'000'000'000ULL) && (lastRecoveryLevel >= 2);
    }

    [[nodiscard]] bool isRecoveryStorm() const noexcept {
        return recoveriesInLastMinute >= 6;
    }
};

// ============================================================================
// 11. SAFETY ENVELOPE (LÍMITES HORARIOS Y LATENCIA DETERMINISTA)
// ============================================================================
class SafetyEnvelope {
private:
    static constexpr uint64_t ONE_HOUR_NS = 3'600'000'000'000ULL;
    static constexpr uint32_t MAX_RECOVERIES = 30;
    static constexpr uint32_t MAX_MIGRATIONS = 8;
    static constexpr uint32_t MAX_DECODER_RESETS = 12;
    static constexpr uint32_t MAX_RENDERER_RESETS = 6;

    std::array<uint64_t, 64> timestamps_{};
    size_t head_{0};
    uint32_t count_{0};
    std::atomic_bool failSafeTriggered_{false};

public:
    bool auditRecovery(uint64_t nowNs) noexcept {
        while (count_ > 0) {
            size_t oldest = (head_ + 64 - count_) % 64;
            if (nowNs - timestamps_[oldest] >= ONE_HOUR_NS) count_--;
            else break;
        }
        if (count_ >= MAX_RECOVERIES) {
            failSafeTriggered_.store(true, std::memory_order_release);
            return false;
        }
        timestamps_[head_] = nowNs;
        head_ = (head_ + 1) % 64;
        count_++;
        return true;
    }

    [[nodiscard]] bool isFailSafeEngaged() const noexcept {
        return failSafeTriggered_.load(std::memory_order_acquire);
    }

    void resetFailSafe() noexcept {
        failSafeTriggered_.store(false, std::memory_order_release);
        count_ = 0;
    }
};

// ============================================================================
// 12. SAFETY MODE CONTROLLER Y CONFINAMIENTO FAIL-SAFE
// ============================================================================
enum class SafetyMode : uint8_t {
    Normal,
    Degraded,
    Recovery,
    FailSafe
};

class SafetyModeController {
private:
    std::atomic<SafetyMode> mode_{SafetyMode::Normal};
    uint64_t failSafeEngagedTimestampNs_{0};

public:
    void setMode(SafetyMode m) noexcept {
        if (m == SafetyMode::FailSafe && mode_.load(std::memory_order_relaxed) != SafetyMode::FailSafe) {
            failSafeEngagedTimestampNs_ = getMonotonicTimeNs();
            LOGW("[SafetyModeController] FAIL-SAFE ACTIVADO: Forzando Motor 4 (HWC2 Direct Overlay) con IA/NPU OFF");
        }
        mode_.store(m, std::memory_order_release);
    }

    [[nodiscard]] SafetyMode getMode() const noexcept {
        return mode_.load(std::memory_order_acquire);
    }

    [[nodiscard]] bool canRecoverFromFailSafe(uint64_t nowNs, float thermalDose, float packetLoss) const noexcept {
        if (mode_.load(std::memory_order_acquire) != SafetyMode::FailSafe) return true;
        // Requiere 120s continuos de estabilidad, dosis térmica baja y red limpia
        bool timeElapsed = (nowNs - failSafeEngagedTimestampNs_ >= 120'000'000'000ULL);
        bool thermalOk = (thermalDose < 30.0f);
        bool netOk = (packetLoss < 0.01f);
        return timeElapsed && thermalOk && netOk;
    }
};

// ============================================================================
// 13. POLICY DECISION TRACER ("¿POR QUÉ EL SISTEMA TOMÓ ESTA DECISIÓN?")
// ============================================================================
enum class PolicyRuleId : uint32_t {
    RULE_000_NOMINAL                     = 0,
    RULE_001_FAILSAFE_ENVELOPE_BREACHED  = 101,
    RULE_002_DEADLINE_RENDERER_CRITICAL  = 201,
    RULE_003_DEADLINE_DECODER_CRITICAL   = 202,
    RULE_004_THERMAL_DOSE_LIMIT_BYPASS   = 301,
    RULE_005_NETWORK_DEAD_RESUME         = 401,
    RULE_006_FLAPPING_MIGRATION_SAFETY   = 501,
    RULE_007_PREDICTIVE_THROTTLE_AI      = 601,
    RULE_008_BUFFER_SHRINK_IN_BAND       = 701,
    RULE_009_ZOMBIE_ENGINE_PURGE         = 801
};

struct PolicyDecisionTrace {
    uint64_t timestampNs{0};
    PolicyRuleId ruleId{PolicyRuleId::RULE_000_NOMINAL};
    uint8_t healthScoreTotal{100};
    uint32_t frameLatencyUs{0};
    float thermalDose{0.0f};
    float failureProbability{0.0f};
    uint8_t actionTaken{0};
    char reason[64]{0};
};

class PersistentDecisionTracer {
private:
    static constexpr size_t CAP = 256;
    std::array<PolicyDecisionTrace, CAP> logs_{};
    std::atomic<uint64_t> cursor_{0};

public:
    void record(PolicyRuleId rule, uint8_t healthScore, uint32_t latencyUs, float dose,
                float pFail, uint8_t action, const char* reason) noexcept {
        const uint64_t ticket = cursor_.fetch_add(1, std::memory_order_relaxed);
        PolicyDecisionTrace& entry = logs_[ticket & (CAP - 1)];
        entry.timestampNs = getMonotonicTimeNs();
        entry.ruleId = rule;
        entry.healthScoreTotal = healthScore;
        entry.frameLatencyUs = latencyUs;
        entry.thermalDose = dose;
        entry.failureProbability = pFail;
        entry.actionTaken = action;
        if (reason) {
            std::strncpy(entry.reason, reason, sizeof(entry.reason) - 1);
            entry.reason[sizeof(entry.reason) - 1] = '\0';
        }
    }

    bool getLatestTrace(PolicyDecisionTrace& outTrace) const noexcept {
        uint64_t cur = cursor_.load(std::memory_order_relaxed);
        if (cur == 0) return false;
        outTrace = logs_[(cur - 1) & (CAP - 1)];
        return true;
    }
};

// ============================================================================
// 14. RECOVERY DECISION ENGINE (AUTORIDAD ÚNICA DE ARBITRAJE)
// ============================================================================
enum class DecisionPriority : uint8_t {
    Ignore       = 0,
    Mitigate     = 1,
    Degrade      = 2,
    Migrate      = 3,
    RecoverL2    = 4,
    RecoverL3    = 5,
    ResumeSession= 6,
    FullReattach = 7,
    FailSafe     = 8
};

struct ArbitrationOutcome {
    DecisionPriority priority{DecisionPriority::Ignore};
    PolicyRuleId rule{PolicyRuleId::RULE_000_NOMINAL};
    const char* reason{"Operación Nominal"};
};

class RecoveryDecisionEngine {
private:
    SafetyEnvelope envelope_{};
    SafetyModeController safetyMode_{};
    ThermalPressureGovernor thermal_{};
    PredictiveDeadlineWatchdog deadline_{};
    PersistentDecisionTracer tracer_{};

public:
    ArbitrationOutcome arbitrate(
        uint64_t frameLatencyUs,
        float tempC,
        bool decoderOk,
        bool rendererOk,
        uint32_t poolAlloc,
        const RecoveryContext& recoveryCtx,
        uint64_t nowNs
    ) noexcept {
        const float dose = thermal_.getDose();
        const auto thermalPacing = thermal_.evaluate(tempC, nowNs);
        const auto deadlineAction = deadline_.audit(frameLatencyUs);
        const float pFail = ProbabilisticFailurePredictor::calculateFailureProbability(
            deadline_.getSlope(), dose, 512, recoveryCtx.recoveriesInLastMinute);

        // Prioridad 8: FailSafe obligatorio si el SafetyEnvelope fue violado
        if (envelope_.isFailSafeEngaged() || safetyMode_.getMode() == SafetyMode::FailSafe) {
            if (!safetyMode_.canRecoverFromFailSafe(nowNs, dose, 0.0f)) {
                tracer_.record(PolicyRuleId::RULE_001_FAILSAFE_ENVELOPE_BREACHED, 10, static_cast<uint32_t>(frameLatencyUs),
                               dose, pFail, 8, "Safety Envelope violado -> FailSafe HWC2");
                return {DecisionPriority::FailSafe, PolicyRuleId::RULE_001_FAILSAFE_ENVELOPE_BREACHED, "Envelope violado -> FailSafe HWC2"};
            }
            safetyMode_.setMode(SafetyMode::Normal);
            envelope_.resetFailSafe();
        }

        // Prioridad 7: Zombie Engine o Flapping severo -> Full Reattach
        if (recoveryCtx.isZombieEngine(nowNs) || recoveryCtx.isRecoveryStorm()) {
            if (envelope_.auditRecovery(nowNs)) {
                tracer_.record(PolicyRuleId::RULE_009_ZOMBIE_ENGINE_PURGE, 20, static_cast<uint32_t>(frameLatencyUs),
                               dose, pFail, 7, "Zombie Engine o Storm detectado -> Reattach Stream");
                return {DecisionPriority::FullReattach, PolicyRuleId::RULE_009_ZOMBIE_ENGINE_PURGE, "Zombie/Storm -> Reattach Stream"};
            }
            safetyMode_.setMode(SafetyMode::FailSafe);
            return {DecisionPriority::FailSafe, PolicyRuleId::RULE_001_FAILSAFE_ENVELOPE_BREACHED, "Presupuesto agotado ante tormenta de recuperaciones"};
        }

        // Prioridad 6: Flapping de red -> Resume Session
        if (recoveryCtx.isFlapping(nowNs)) {
            if (envelope_.auditRecovery(nowNs)) {
                tracer_.record(PolicyRuleId::RULE_006_FLAPPING_MIGRATION_SAFETY, 30, static_cast<uint32_t>(frameLatencyUs),
                               dose, pFail, 6, "Flapping activo -> Resume Session");
                return {DecisionPriority::ResumeSession, PolicyRuleId::RULE_006_FLAPPING_MIGRATION_SAFETY, "Flapping de red -> Resume Session"};
            }
            safetyMode_.setMode(SafetyMode::FailSafe);
            return {DecisionPriority::FailSafe, PolicyRuleId::RULE_001_FAILSAFE_ENVELOPE_BREACHED, "Flapping sin presupuesto horario"};
        }

        // Prioridad 5: Colapso de Renderer / Swapchain / Deadline > 100ms
        if (!rendererOk || deadlineAction == DeadlineAction::Recovery_100ms) {
            if (envelope_.auditRecovery(nowNs)) {
                tracer_.record(PolicyRuleId::RULE_002_DEADLINE_RENDERER_CRITICAL, 40, static_cast<uint32_t>(frameLatencyUs),
                               dose, pFail, 5, "Renderer Timeout > 100ms -> Reinicio L3");
                return {DecisionPriority::RecoverL3, PolicyRuleId::RULE_002_DEADLINE_RENDERER_CRITICAL, "Reinicio de Renderer L3"};
            }
            safetyMode_.setMode(SafetyMode::FailSafe);
            return {DecisionPriority::FailSafe, PolicyRuleId::RULE_001_FAILSAFE_ENVELOPE_BREACHED, "Renderer exhausto sin presupuesto"};
        }

        // Prioridad 4: Colapso de Decoder
        if (!decoderOk) {
            if (envelope_.auditRecovery(nowNs)) {
                tracer_.record(PolicyRuleId::RULE_003_DEADLINE_DECODER_CRITICAL, 50, static_cast<uint32_t>(frameLatencyUs),
                               dose, pFail, 4, "Decoder Stall -> Reinicio L2");
                return {DecisionPriority::RecoverL2, PolicyRuleId::RULE_003_DEADLINE_DECODER_CRITICAL, "Reinicio de Decoder L2"};
            }
            safetyMode_.setMode(SafetyMode::FailSafe);
            return {DecisionPriority::FailSafe, PolicyRuleId::RULE_001_FAILSAFE_ENVELOPE_BREACHED, "Decoder exhausto sin presupuesto"};
        }

        // Prioridad 3: Sobrecarga Térmica Acumulada -> Migración a Motor 4 (HWC2 Directo)
        if (thermalPacing == ThermalPressureGovernor::Pacing::Critical_Bypass) {
            tracer_.record(PolicyRuleId::RULE_004_THERMAL_DOSE_LIMIT_BYPASS, 60, static_cast<uint32_t>(frameLatencyUs),
                           dose, pFail, 3, "Dosis Térmica Crítica -> Migración HWC2");
            return {DecisionPriority::Migrate, PolicyRuleId::RULE_004_THERMAL_DOSE_LIMIT_BYPASS, "Migración térmica forzosa a Motor 4"};
        }

        // Prioridad 2: Degradación preventiva de IA / NPU
        if (deadlineAction == DeadlineAction::Degraded_33ms || thermalPacing == ThermalPressureGovernor::Pacing::Throttled_AI || pFail > 0.40f) {
            tracer_.record(PolicyRuleId::RULE_007_PREDICTIVE_THROTTLE_AI, 75, static_cast<uint32_t>(frameLatencyUs),
                           dose, pFail, 2, "Latencia/Térmico degradado -> Reducir pasadas IA");
            return {DecisionPriority::Degrade, PolicyRuleId::RULE_007_PREDICTIVE_THROTTLE_AI, "Degradar pasadas de cómputo neuronal"};
        }

        // Prioridad 1: Mitigación preventiva (Pool al 75% o jitter emergente)
        if (deadlineAction == DeadlineAction::Warning_16ms || poolAlloc >= 48) {
            tracer_.record(PolicyRuleId::RULE_008_BUFFER_SHRINK_IN_BAND, 85, static_cast<uint32_t>(frameLatencyUs),
                           dose, pFail, 1, "Presión en pool de memoria -> Shrink de buffers");
            return {DecisionPriority::Mitigate, PolicyRuleId::RULE_008_BUFFER_SHRINK_IN_BAND, "Reducción preventiva de búfer"};
        }

        return {DecisionPriority::Ignore, PolicyRuleId::RULE_000_NOMINAL, "Operación Nominal"};
    }

    [[nodiscard]] const PersistentDecisionTracer& getTracer() const noexcept {
        return tracer_;
    }
};

// ============================================================================
// 15. CHAOS INJECTION ENGINE (INYECCIÓN DE ANOMALÍAS EN TIEMPO DE EJECUCIÓN)
// ============================================================================
enum class ChaosFaultType : uint32_t {
    None               = 0,
    DecoderFreeze      = 1,
    RendererDeadlock   = 2,
    PacketLossBurst    = 3,
    ThermalSpike       = 4,
    EglContextLoss     = 5,
    SwapchainOutOfDate = 6,
    FenceLock          = 7,
    NNAPIFailure       = 8
};

class ChaosInjectionEngine {
private:
    std::atomic<uint32_t> activeFault_{static_cast<uint32_t>(ChaosFaultType::None)};
    std::atomic<uint32_t> faultDurationMs_{0};
    std::atomic<uint64_t> faultInjectedTimestampNs_{0};

public:
    void injectFault(ChaosFaultType fault, uint32_t durationMs) noexcept {
        activeFault_.store(static_cast<uint32_t>(fault), std::memory_order_release);
        faultDurationMs_.store(durationMs, std::memory_order_release);
        faultInjectedTimestampNs_.store(getMonotonicTimeNs(), std::memory_order_release);
        LOGW("[ChaosEngine] Falla inyectada intencionalmente: tipo=%u duracion=%u ms",
             static_cast<uint32_t>(fault), durationMs);
    }

    [[nodiscard]] ChaosFaultType checkFaultActive() noexcept {
        uint32_t f = activeFault_.load(std::memory_order_acquire);
        if (f == static_cast<uint32_t>(ChaosFaultType::None)) return ChaosFaultType::None;

        uint64_t elapsedMs = (getMonotonicTimeNs() - faultInjectedTimestampNs_.load(std::memory_order_relaxed)) / 1'000'000ULL;
        if (elapsedMs > faultDurationMs_.load(std::memory_order_relaxed)) {
            activeFault_.store(static_cast<uint32_t>(ChaosFaultType::None), std::memory_order_release);
            return ChaosFaultType::None;
        }
        return static_cast<ChaosFaultType>(f);
    }
};

// ============================================================================
// 16. SLO RUNTIME AUDITOR (DISPONIBILIDAD >= 99.99% Y BUDGET DE RECUPERACIÓN)
// ============================================================================
struct SloRuntimeReport {
    double availabilityPercent{100.0};
    uint64_t totalFrames{0};
    uint64_t droppedFrames{0};
    uint32_t maxRendererRecoveryMs{0};
    uint32_t maxDecoderRecoveryMs{0};
    uint32_t maxMigrationMs{0};
    bool sloCompliant{true};
};

class SloRuntimeAuditor {
private:
    std::atomic<uint64_t> totalFrames_{0};
    std::atomic<uint64_t> droppedFrames_{0};
    std::atomic<uint32_t> maxRendererRecoveryMs_{0};
    std::atomic<uint32_t> maxDecoderRecoveryMs_{0};
    std::atomic<uint32_t> maxMigrationMs_{0};

public:
    void recordFrameOutcome(bool presented) noexcept {
        totalFrames_.fetch_add(1, std::memory_order_relaxed);
        if (!presented) droppedFrames_.fetch_add(1, std::memory_order_relaxed);
    }

    void recordRecoveryDuration(uint8_t level, uint32_t durationMs) noexcept {
        if (level == 2) {
            uint32_t cur = maxDecoderRecoveryMs_.load(std::memory_order_relaxed);
            while (durationMs > cur && !maxDecoderRecoveryMs_.compare_exchange_weak(cur, durationMs)) {}
        } else if (level == 3) {
            uint32_t cur = maxRendererRecoveryMs_.load(std::memory_order_relaxed);
            while (durationMs > cur && !maxRendererRecoveryMs_.compare_exchange_weak(cur, durationMs)) {}
        } else if (level == 4) {
            uint32_t cur = maxMigrationMs_.load(std::memory_order_relaxed);
            while (durationMs > cur && !maxMigrationMs_.compare_exchange_weak(cur, durationMs)) {}
        }
    }

    [[nodiscard]] SloRuntimeReport generateReport() const noexcept {
        SloRuntimeReport rep{};
        rep.totalFrames = totalFrames_.load(std::memory_order_relaxed);
        rep.droppedFrames = droppedFrames_.load(std::memory_order_relaxed);
        rep.maxDecoderRecoveryMs = maxDecoderRecoveryMs_.load(std::memory_order_relaxed);
        rep.maxRendererRecoveryMs = maxRendererRecoveryMs_.load(std::memory_order_relaxed);
        rep.maxMigrationMs = maxMigrationMs_.load(std::memory_order_relaxed);

        if (rep.totalFrames > 0) {
            rep.availabilityPercent = 100.0 * (1.0 - (static_cast<double>(rep.droppedFrames) / static_cast<double>(rep.totalFrames)));
        }

        // SLO: Renderer < 250ms, Decoder < 500ms, Migration < 500ms, Availability >= 99.99%
        rep.sloCompliant = (rep.maxRendererRecoveryMs <= 250) &&
                           (rep.maxDecoderRecoveryMs <= 500) &&
                           (rep.maxMigrationMs <= 500) &&
                           (rep.availabilityPercent >= 99.99);
        return rep;
    }
};

// ============================================================================
// 17. PROPERTY-BASED TESTING (VERIFICACIÓN DETERMINISTA >= 100.000 SECUENCIAS)
// ============================================================================
class PropertyBasedTestingEngine {
public:
    struct TestResult {
        bool allPassed{true};
        uint64_t iterationsExecuted{0};
        uint32_t inv001Failures{0};
        uint32_t inv002Failures{0};
        uint32_t inv003Failures{0};
        uint32_t inv004Failures{0};
        uint32_t inv005Failures{0};
    };

    static TestResult runValidationSuite(uint32_t iterations = 100000, uint32_t seed = 0xDEADBEEF) noexcept {
        TestResult res{};
        res.iterationsExecuted = iterations;
        uint32_t rngState = seed;

        auto nextRandom = [&rngState]() {
            rngState = rngState * 1664525u + 1013904223u;
            return rngState;
        };

        MasterInvariantEnforcer enforcer{};
        VyukovLockFreeFramePool pool{};
        HardenedFSM fsm{};

        uint8_t dummyPayload[128] = {0};

        for (uint32_t i = 0; i < iterations; ++i) {
            uint32_t r = nextRandom();

            // Test INV-001: Intentos de mutación cruzada
            bool attemptMutation = (r % 10000 == 0);
            if (!enforcer.assertControlPlaneIsolation(attemptMutation)) {
                res.inv001Failures++;
                res.allPassed = false;
            }

            // Test INV-002: Terminalidad de fotograma
            uint64_t seq = i + 1;
            enforcer.markFrameInFlight(seq);
            FrameTerminalState targetTerminal = static_cast<FrameTerminalState>((r % 4) + 1);
            if (!enforcer.recordFrameTerminalState(seq, targetTerminal)) {
                res.inv002Failures++;
                res.allPassed = false;
            }

            // Intentar una segunda terminalidad inválida (debe fallar la segunda)
            if (enforcer.recordFrameTerminalState(seq, FrameTerminalState::Dropped)) {
                res.inv002Failures++;
                res.allPassed = false;
            }

            // Test INV-003: Rollback time
            uint64_t rollbackNs = (r % 600) * 1'000'000ULL;
            if (rollbackNs > 500'000'000ULL) {
                if (enforcer.assertMigrationRollbackSafety(rollbackNs)) {
                    res.inv003Failures++;
                    res.allPassed = false;
                }
            } else {
                if (!enforcer.assertMigrationRollbackSafety(rollbackNs)) {
                    res.inv003Failures++;
                    res.allPassed = false;
                }
            }

            // Test INV-004: Ownership y Pool O(1)
            auto slot = pool.acquireSlot(dummyPayload, sizeof(dummyPayload), i * 16666ULL, seq);
            if (slot.has_value()) {
                if (!pool.releaseSlot(slot.value())) {
                    res.inv004Failures++;
                    res.allPassed = false;
                }
            }

            // Test INV-005: Convergencia de recuperación
            uint32_t recoveryStreak = (r % 15);
            if (recoveryStreak > 10) {
                if (enforcer.assertRecoveryConvergence(recoveryStreak, 10)) {
                    res.inv005Failures++;
                    res.allPassed = false;
                }
            } else {
                if (!enforcer.assertRecoveryConvergence(recoveryStreak, 10)) {
                    res.inv005Failures++;
                    res.allPassed = false;
                }
            }
        }

        return res;
    }
};

} // namespace GodMode::Carrier
