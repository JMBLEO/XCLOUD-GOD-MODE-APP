#pragma once

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <array>
#include <mutex>
#include <condition_variable>
#include <optional>
#include <algorithm>

#if __has_include(<semaphore>) && (!defined(__ANDROID_API__) || __ANDROID_API__ >= 31)
#include <semaphore>
#define GM_HAS_STD_SEMAPHORE 1
#else
#define GM_HAS_STD_SEMAPHORE 0
#endif

#define LOG_TAG "GodModeHardenedCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static inline uint64_t getMonotonicTimeNs() noexcept {
    auto now = std::chrono::steady_clock::now().time_since_epoch();
    return std::chrono::duration_cast<std::chrono::nanoseconds>(now).count();
}

// ============================================================================
// 1. GESTOR EXPLICITO DE OWNERSHIP PARA ANativeWindow
// ============================================================================
class NativeWindowRef {
private:
    ANativeWindow* ptr_{nullptr};

public:
    NativeWindowRef() noexcept = default;

    explicit NativeWindowRef(ANativeWindow* win) noexcept : ptr_(win) {
        if (ptr_) {
            ANativeWindow_acquire(ptr_);
        }
    }

    ~NativeWindowRef() noexcept {
        reset();
    }

    NativeWindowRef(const NativeWindowRef& other) noexcept : ptr_(other.ptr_) {
        if (ptr_) {
            ANativeWindow_acquire(ptr_);
        }
    }

    NativeWindowRef& operator=(const NativeWindowRef& other) noexcept {
        if (this != &other) {
            reset();
            ptr_ = other.ptr_;
            if (ptr_) {
                ANativeWindow_acquire(ptr_);
            }
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
// 4. FSM COMPLETA (Con Buffering y Suspended)
// ============================================================================
enum class EngineState : uint32_t {
    Idle,
    Initializing,
    Buffering,
    Streaming,
    Degraded,
    RecoveringL1,
    RecoveringL2,
    RecoveringL3,
    Suspended,
    Failed,
    Shutdown
};

class EngineStateMachine {
protected:
    std::atomic<EngineState> state_{EngineState::Idle};

public:
    [[nodiscard]] EngineState state() const noexcept {
        return state_.load(std::memory_order_acquire);
    }

    [[nodiscard]] bool canProcessFrames() const noexcept {
        EngineState s = state();
        return s == EngineState::Streaming || s == EngineState::Degraded || s == EngineState::Buffering;
    }

    void transition(EngineState next) noexcept {
        state_.store(next, std::memory_order_release);
    }

    bool compareAndTransition(EngineState expected, EngineState next) noexcept {
        return state_.compare_exchange_strong(expected, next,
                                              std::memory_order_acq_rel,
                                              std::memory_order_acquire);
    }
};

// ============================================================================
// 2. FRAME POOL PREASIGNADO Y DESCRIPTORES LIVIANOS
// ============================================================================
struct FrameDescriptor {
    uint32_t poolIndex{0};
    uint32_t size{0};
    uint64_t ptsUs{0};
    uint64_t sequenceNumber{0};
};

class FrameMemoryPool {
public:
    static constexpr size_t POOL_SLOTS = 64;
    static constexpr size_t SLOT_CAPACITY_BYTES = 384 * 1024; // 384 KB por slot

private:
    alignas(64) std::array<std::array<uint8_t, SLOT_CAPACITY_BYTES>, POOL_SLOTS> memoryArena_{};
    std::array<std::atomic_bool, POOL_SLOTS> slotInUse_{};

public:
    FrameMemoryPool() {
        for (auto& flag : slotInUse_) {
            flag.store(false, std::memory_order_relaxed);
        }
    }

    std::optional<uint32_t> acquireSlot(const uint8_t* sourceData, size_t size) noexcept {
        if (!sourceData || size == 0 || size > SLOT_CAPACITY_BYTES) {
            return std::nullopt;
        }

        for (size_t i = 0; i < POOL_SLOTS; ++i) {
            bool expected = false;
            if (slotInUse_[i].compare_exchange_strong(expected, true,
                                                      std::memory_order_acq_rel,
                                                      std::memory_order_relaxed)) {
                std::memcpy(memoryArena_[i].data(), sourceData, size);
                return static_cast<uint32_t>(i);
            }
        }
        return std::nullopt; // Pool agotado: descarte sin fugas
    }

    void releaseSlot(uint32_t index) noexcept {
        if (index < POOL_SLOTS) {
            slotInUse_[index].store(false, std::memory_order_release);
        }
    }

    [[nodiscard]] const uint8_t* getSlotData(uint32_t index) const noexcept {
        if (index >= POOL_SLOTS) return nullptr;
        return memoryArena_[index].data();
    }
};

// ============================================================================
// 9. RING BUFFER SEGURO CON ÍNDICES MONOTÓNICOS uint64_t
// ============================================================================
template <typename T, size_t Capacity>
class MonotonicRingBuffer {
    static_assert((Capacity & (Capacity - 1)) == 0, "Capacity debe ser potencia de 2");

private:
    std::array<T, Capacity> buffer_{};
    alignas(64) std::atomic<uint64_t> headSequence_{0};
    alignas(64) std::atomic<uint64_t> tailSequence_{0};

public:
    bool push(const T& item) noexcept {
        const uint64_t currentTail = tailSequence_.load(std::memory_order_relaxed);
        const uint64_t currentHead = headSequence_.load(std::memory_order_acquire);

        if ((currentTail - currentHead) >= Capacity) {
            return false;
        }

        buffer_[currentTail & (Capacity - 1)] = item;
        tailSequence_.store(currentTail + 1, std::memory_order_release);
        return true;
    }

    bool pop(T& outItem) noexcept {
        const uint64_t currentHead = headSequence_.load(std::memory_order_relaxed);
        const uint64_t currentTail = tailSequence_.load(std::memory_order_acquire);

        if (currentHead == currentTail) {
            return false;
        }

        outItem = buffer_[currentHead & (Capacity - 1)];
        headSequence_.store(currentHead + 1, std::memory_order_release);
        return true;
    }

    void clear() noexcept {
        headSequence_.store(tailSequence_.load(std::memory_order_relaxed), std::memory_order_release);
    }

    [[nodiscard]] size_t size() const noexcept {
        uint64_t h = headSequence_.load(std::memory_order_relaxed);
        uint64_t t = tailSequence_.load(std::memory_order_relaxed);
        return (t >= h) ? static_cast<size_t>(t - h) : 0;
    }
};

// ============================================================================
// 5. CIRCUIT BREAKER CON SONDA ÚNICA EN HALF-OPEN
// ============================================================================
class HardenedCircuitBreaker {
public:
    enum class Status { Closed, Open, HalfOpen };

private:
    std::atomic<uint32_t> failureCount_{0};
    std::atomic<uint64_t> openedAtNs_{0};
    std::atomic_bool probeInFlight_{false};
    const uint32_t failureThreshold_{10};
    const uint64_t cooldownPeriodNs_{60'000'000'000ULL}; // 60 segundos

public:
    explicit HardenedCircuitBreaker(uint32_t threshold = 10) : failureThreshold_(threshold) {}

    bool allowExecution() noexcept {
        uint64_t openedAt = openedAtNs_.load(std::memory_order_acquire);
        if (openedAt == 0) {
            return true; // Estado Closed
        }

        uint64_t now = getMonotonicTimeNs();
        if ((now - openedAt) > cooldownPeriodNs_) {
            // Half-Open: autoriza exactamente una prueba unitaria
            bool expectedProbe = false;
            if (probeInFlight_.compare_exchange_strong(expectedProbe, true,
                                                       std::memory_order_acq_rel,
                                                       std::memory_order_acquire)) {
                return true;
            }
            return false; // Otra sonda ya está en curso
        }
        return false; // Open: circuito desconectado
    }

    void recordSuccess() noexcept {
        failureCount_.store(0, std::memory_order_relaxed);
        openedAtNs_.store(0, std::memory_order_release);
        probeInFlight_.store(false, std::memory_order_release);
    }

    void recordFailure() noexcept {
        probeInFlight_.store(false, std::memory_order_release);
        uint32_t fails = failureCount_.fetch_add(1, std::memory_order_acq_rel) + 1;
        if (fails >= failureThreshold_ || openedAtNs_.load(std::memory_order_relaxed) != 0) {
            openedAtNs_.store(getMonotonicTimeNs(), std::memory_order_release);
            LOGW("[CircuitBreaker] Fallo registrado. Circuito en estado OPEN.");
        }
    }

    [[nodiscard]] Status getStatus() const noexcept {
        uint64_t openedAt = openedAtNs_.load(std::memory_order_acquire);
        if (openedAt == 0) return Status::Closed;
        if ((getMonotonicTimeNs() - openedAt) > cooldownPeriodNs_) return Status::HalfOpen;
        return Status::Open;
    }
};

// ============================================================================
// 6. RECOVERY BUDGET INTEGRAL (Decoders, Renderers, Sessions, Migrations, Reattach)
// ============================================================================
class ComprehensiveRecoveryBudget {
private:
    std::mutex mtx_;
    std::array<uint64_t, 5> decoderResetTimes_{0};
    size_t decIdx_{0};

    std::array<uint64_t, 3> rendererResetTimes_{0};
    size_t rendIdx_{0};

    std::array<uint64_t, 2> engineMigrationTimes_{0};
    size_t migIdx_{0};

    std::array<uint64_t, 3> sessionResumeTimes_{0};
    size_t resIdx_{0};

    std::array<uint64_t, 1> fullReattachTimes_{0};
    size_t reatIdx_{0};

    bool evaluateRate(uint64_t* timestamps, size_t capacity, size_t& currentIdx, uint64_t windowNs) noexcept {
        uint64_t now = getMonotonicTimeNs();
        uint64_t oldest = timestamps[currentIdx];
        if (oldest != 0 && (now - oldest) < windowNs) {
            return false;
        }
        timestamps[currentIdx] = now;
        currentIdx = (currentIdx + 1) % capacity;
        return true;
    }

public:
    bool allowDecoderReset() noexcept {
        std::lock_guard<std::mutex> lock(mtx_);
        return evaluateRate(decoderResetTimes_.data(), decoderResetTimes_.size(), decIdx_, 60'000'000'000ULL); // 5/min
    }

    bool allowRendererReset() noexcept {
        std::lock_guard<std::mutex> lock(mtx_);
        return evaluateRate(rendererResetTimes_.data(), rendererResetTimes_.size(), rendIdx_, 60'000'000'000ULL); // 3/min
    }

    bool allowEngineMigration() noexcept {
        std::lock_guard<std::mutex> lock(mtx_);
        return evaluateRate(engineMigrationTimes_.data(), engineMigrationTimes_.size(), migIdx_, 60'000'000'000ULL); // 2/min
    }

    bool allowSessionResume() noexcept {
        std::lock_guard<std::mutex> lock(mtx_);
        return evaluateRate(sessionResumeTimes_.data(), sessionResumeTimes_.size(), resIdx_, 300'000'000'000ULL); // 3/5min
    }

    bool allowFullReattach() noexcept {
        std::lock_guard<std::mutex> lock(mtx_);
        return evaluateRate(fullReattachTimes_.data(), fullReattachTimes_.size(), reatIdx_, 300'000'000'000ULL); // 1/5min
    }
};

// ============================================================================
// 10. SEMÁFORO SEGURO Y EFICIENTE PARA SANDBOX (0% CPU en reposo)
// ============================================================================
template <ptrdiff_t LeastMaxValue = 64>
class ResilientSemaphore {
private:
    std::mutex mtx_;
    std::condition_variable cv_;
    ptrdiff_t count_{0};

public:
    explicit ResilientSemaphore(ptrdiff_t initial = 0) noexcept : count_(initial) {}

    void release(ptrdiff_t update = 1) noexcept {
        std::lock_guard<std::mutex> lock(mtx_);
        count_ += update;
        cv_.notify_all();
    }

    bool try_acquire_for(std::chrono::milliseconds timeout) noexcept {
        std::unique_lock<std::mutex> lock(mtx_);
        if (cv_.wait_for(lock, timeout, [this]() { return count_ > 0; })) {
            --count_;
            return true;
        }
        return false;
    }
};

// ============================================================================
// 11. DEADLINE WATCHDOG GLOBAL
// ============================================================================
enum class DeadlineSeverity {
    Nominal,
    Warning_16ms,
    Degraded_33ms,
    RecoveryTrigger_100ms
};

class DeadlineWatchdog {
public:
    static DeadlineSeverity auditFrameDuration(uint64_t durationUs) noexcept {
        if (durationUs > 100'000ULL) {
            return DeadlineSeverity::RecoveryTrigger_100ms;
        }
        if (durationUs > 33'333ULL) {
            return DeadlineSeverity::Degraded_33ms;
        }
        if (durationUs > 16'666ULL) {
            return DeadlineSeverity::Warning_16ms;
        }
        return DeadlineSeverity::Nominal;
    }
};
