#pragma once

#include <oboe/Oboe.h>
#include <memory>
#include <atomic>
#include <mutex>
#include <vector>
#include <cmath>
#include <algorithm>

namespace GodMode::Audio {

/**
 * AudioTelemetrySnapshot: Real-time observability metrics from the Oboe audio pipeline.
 */
struct AudioTelemetrySnapshot {
    float latencyMs{0.0f};
    float bufferDepthMs{0.0f};
    float jitterMs{0.0f};
    int64_t driftUs{0};
    uint64_t underrunCount{0};
    double speedRatio{1.0};
};

/**
 * GodModeAudioEngine: High-performance Oboe pipeline for ultra-low-latency game audio
 * streaming (48 kHz Stereo Float PCM) with minimal jitter and dynamic A/V clock synchronization.
 *
 * Implements:
 * 1. Oboe Exclusive LowLatency audio stream (SCHED_FIFO thread priority).
 * 2. Adaptive Jitter Buffer with RFC 3550 packet jitter tracking and dynamic depth targets.
 * 3. A/V Clock Synchronizer using a Proportional-Integral (PI) phase-locked loop and
 *    Catmull-Rom cubic hermite spline resampling for zero-click drift correction (+/- 0.75%).
 * 4. Packet Loss Concealment (PLC) with graceful cosine decay to prevent buffer underrun clicks.
 */
class GodModeAudioEngine : public oboe::AudioStreamDataCallback,
                           public oboe::AudioStreamErrorCallback {
public:
    GodModeAudioEngine(size_t capacityFrames = 19200); // 400ms buffer capacity at 48kHz
    virtual ~GodModeAudioEngine();

    bool start();
    void stop();
    bool isRunning() const { return running_.load(std::memory_order_relaxed); }

    // Ingestion APIs: Float (normalized [-1.0, 1.0]) and Int16 PCM (48kHz Stereo)
    void injectAudio(const int16_t* data, int32_t numFrames);
    void injectAudioFloat(const float* data, int32_t numFrames);

    // Dynamic A/V clock calibration with video PTS (presentation timestamps)
    void syncAudioClock(int64_t audioClockUs, int64_t videoPtsUs);
    int64_t getInternalAudioClockUs() const;

    // Jitter telemetry tracking (RFC 3550)
    void recordPacketArrival(int64_t sendTimeUs, int64_t arrivalTimeUs);

    // Observability snapshot
    AudioTelemetrySnapshot getTelemetry() const;

    // Oboe Callbacks
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* audioStream, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    std::shared_ptr<oboe::AudioStream> stream_{nullptr};
    std::mutex engineMutex_;
    std::atomic<bool> running_{false};

    // Circular ring buffer for stereo float samples
    const size_t bufferFrames_;
    std::vector<float> ringBuffer_;
    std::atomic<size_t> writeHead_{0};
    std::atomic<size_t> readHead_{0};

    // Resampling state for Catmull-Rom interpolation
    double phase_{0.0};
    std::atomic<double> speedRatio_{1.0};
    std::atomic<int64_t> estimatedDriftUs_{0};

    // Clock drift PI controller parameters
    double integralDriftError_{0.0};
    double filteredDriftUs_{0.0};
    static constexpr double kKp = 0.0000040;
    static constexpr double kKi = 0.000000006;
    static constexpr double kMaxSpeedAdjustment = 0.0075; // +/- 0.75% max drift adjustment

    // RFC 3550 Jitter Estimator
    std::atomic<float> rfcJitterMs_{0.0f};
    int64_t lastTransitUs_{0};

    // Adaptive buffer target depth (frames)
    std::atomic<int32_t> targetBufferDepthFrames_{480}; // Default 10ms at 48kHz

    // PLC (Packet Loss Concealment) state
    float lastSampleL_{0.0f};
    float lastSampleR_{0.0f};
    std::atomic<uint64_t> underrunCount_{0};

    // Internal timing
    std::atomic<int64_t> lastAudioClockUs_{0};

    void setupThreadPriority();
};

} // namespace GodMode::Audio
