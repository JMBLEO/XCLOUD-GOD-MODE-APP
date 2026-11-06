#include "GodModeAudioEngine.h"
#include <android/log.h>
#include <pthread.h>
#include <sched.h>
#include <sys/resource.h>
#include <thread>
#include <cmath>
#include <cstring>

#define LOG_TAG "GodModeAudio"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace GodMode::Audio {

GodModeAudioEngine::GodModeAudioEngine(size_t capacityFrames)
    : bufferFrames_(capacityFrames) {
    ringBuffer_.assign(bufferFrames_ * 2, 0.0f);
}

GodModeAudioEngine::~GodModeAudioEngine() {
    stop();
}

bool GodModeAudioEngine::start() {
    std::lock_guard<std::mutex> lock(engineMutex_);
    if (running_.load(std::memory_order_relaxed)) {
        return true;
    }

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Exclusive)
           ->setFormat(oboe::AudioFormat::Float)
           ->setChannelCount(oboe::ChannelCount::Stereo)
           ->setSampleRate(48000)
           ->setUsage(oboe::Usage::Game)
           ->setContentType(oboe::ContentType::Sonification)
           ->setDataCallback(this)
           ->setErrorCallback(this);

    oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        LOGW("Exclusive hardware audio mode unavailable (%s). Falling back to Shared LowLatency mode...", oboe::convertToText(result));
        builder.setSharingMode(oboe::SharingMode::Shared);
        result = builder.openStream(stream_);
        if (result != oboe::Result::OK) {
            LOGE("Failed to open fallback Oboe audio stream: %s", oboe::convertToText(result));
            return false;
        }
    }

    // Set buffer size to burst size * 2 for optimal ultra-low latency without underruns
    int32_t burst = stream_->getFramesPerBurst();
    if (burst > 0) {
        stream_->setBufferSizeInFrames(burst * 2);
    }

    result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start Oboe audio stream: %s", oboe::convertToText(result));
        stream_->close();
        stream_.reset();
        return false;
    }

    running_.store(true, std::memory_order_release);
    LOGI("GodModeAudioEngine initialized: 48kHz Stereo Float, LowLatency Exclusive mode (Burst: %d frames)", burst);
    return true;
}

void GodModeAudioEngine::stop() {
    std::lock_guard<std::mutex> lock(engineMutex_);
    if (!running_.load(std::memory_order_relaxed)) {
        return;
    }

    running_.store(false, std::memory_order_release);
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }
    LOGI("GodModeAudioEngine stopped");
}

void GodModeAudioEngine::injectAudio(const int16_t* data, int32_t numFrames) {
    if (!data || numFrames <= 0) return;

    size_t w = writeHead_.load(std::memory_order_relaxed);
    for (int32_t i = 0; i < numFrames; ++i) {
        // Convert signed 16-bit PCM to normalized float [-1.0f, 1.0f]
        float l = static_cast<float>(data[i * 2]) / 32768.0f;
        float r = static_cast<float>(data[i * 2 + 1]) / 32768.0f;
        ringBuffer_[(w % bufferFrames_) * 2] = l;
        ringBuffer_[(w % bufferFrames_) * 2 + 1] = r;
        w++;
    }
    writeHead_.store(w, std::memory_order_release);
}

void GodModeAudioEngine::injectAudioFloat(const float* data, int32_t numFrames) {
    if (!data || numFrames <= 0) return;

    size_t w = writeHead_.load(std::memory_order_relaxed);
    for (int32_t i = 0; i < numFrames; ++i) {
        ringBuffer_[(w % bufferFrames_) * 2] = data[i * 2];
        ringBuffer_[(w % bufferFrames_) * 2 + 1] = data[i * 2 + 1];
        w++;
    }
    writeHead_.store(w, std::memory_order_release);
}

void GodModeAudioEngine::syncAudioClock(int64_t audioClockUs, int64_t videoPtsUs) {
    if (audioClockUs <= 0 || videoPtsUs <= 0) return;

    int64_t instantaneousDrift = videoPtsUs - audioClockUs;

    // Low-pass filter for drift measurement (alpha = 0.08)
    filteredDriftUs_ = (1.0 - 0.08) * filteredDriftUs_ + 0.08 * static_cast<double>(instantaneousDrift);
    estimatedDriftUs_.store(static_cast<int64_t>(filteredDriftUs_), std::memory_order_relaxed);

    // PI Controller loop
    double error = filteredDriftUs_;
    integralDriftError_ += error;
    integralDriftError_ = std::clamp(integralDriftError_, -5000000.0, 5000000.0);

    double speedAdjustment = (kKp * error) + (kKi * integralDriftError_);
    double newRatio = 1.0 + speedAdjustment;

    // Clamp speed adjustment to +/- 0.75% to eliminate drift without any audible pitch shift
    double clampedRatio = std::clamp(newRatio, 1.0 - kMaxSpeedAdjustment, 1.0 + kMaxSpeedAdjustment);
    speedRatio_.store(clampedRatio, std::memory_order_release);
}

int64_t GodModeAudioEngine::getInternalAudioClockUs() const {
    return lastAudioClockUs_.load(std::memory_order_relaxed);
}

void GodModeAudioEngine::recordPacketArrival(int64_t sendTimeUs, int64_t arrivalTimeUs) {
    if (sendTimeUs <= 0 || arrivalTimeUs <= 0) return;

    int64_t currentTransitUs = arrivalTimeUs - sendTimeUs;
    if (lastTransitUs_ > 0) {
        int64_t deltaTransit = std::abs(currentTransitUs - lastTransitUs_);
        // RFC 3550 exponential moving average: J = J + (|D| - J) / 16
        float currentJitter = rfcJitterMs_.load(std::memory_order_relaxed);
        float deltaMs = static_cast<float>(deltaTransit) / 1000.0f;
        float newJitter = currentJitter + (deltaMs - currentJitter) / 16.0f;
        rfcJitterMs_.store(newJitter, std::memory_order_relaxed);

        // Dynamically adjust target buffer depth based on jitter
        // Base depth 8ms (384 frames), scales up to 24ms (1152 frames) if jitter spikes
        int32_t targetFrames = static_cast<int32_t>(std::clamp(newJitter * 2.0f * 48.0f, 384.0f, 1152.0f));
        targetBufferDepthFrames_.store(targetFrames, std::memory_order_relaxed);
    }
    lastTransitUs_ = currentTransitUs;
}

AudioTelemetrySnapshot GodModeAudioEngine::getTelemetry() const {
    AudioTelemetrySnapshot snap;
    snap.jitterMs = rfcJitterMs_.load(std::memory_order_relaxed);
    snap.driftUs = estimatedDriftUs_.load(std::memory_order_relaxed);
    snap.speedRatio = speedRatio_.load(std::memory_order_relaxed);
    snap.underrunCount = underrunCount_.load(std::memory_order_relaxed);

    size_t w = writeHead_.load(std::memory_order_relaxed);
    size_t r = readHead_.load(std::memory_order_relaxed);
    size_t queuedFrames = (w >= r) ? (w - r) : 0;
    snap.bufferDepthMs = (static_cast<float>(queuedFrames) / 48000.0f) * 1000.0f;

    if (stream_) {
        auto result = stream_->calculateLatencyMillis();
        if (result) {
            snap.latencyMs = static_cast<float>(result.value());
        } else {
            snap.latencyMs = snap.bufferDepthMs + 4.0f; // Baseline estimate
        }
    }
    return snap;
}

oboe::DataCallbackResult GodModeAudioEngine::onAudioReady(
    oboe::AudioStream* audioStream,
    void* audioData,
    int32_t numFrames
) {
    static bool prioritySet = false;
    if (!prioritySet) {
        setupThreadPriority();
        prioritySet = true;
    }

    float* output = static_cast<float*>(audioData);
    size_t r = readHead_.load(std::memory_order_relaxed);
    size_t w = writeHead_.load(std::memory_order_acquire);
    size_t availableFrames = (w >= r) ? (w - r) : 0;

    double ratio = speedRatio_.load(std::memory_order_relaxed);

    if (availableFrames < static_cast<size_t>(numFrames)) {
        // Buffer Underrun / Late Packet: Apply Packet Loss Concealment (PLC)
        underrunCount_.fetch_add(1, std::memory_order_relaxed);

        size_t framesRead = 0;
        for (; framesRead < availableFrames; ++framesRead) {
            size_t idx = (r + framesRead) % bufferFrames_;
            output[framesRead * 2] = ringBuffer_[idx * 2];
            output[framesRead * 2 + 1] = ringBuffer_[idx * 2 + 1];
            lastSampleL_ = ringBuffer_[idx * 2];
            lastSampleR_ = ringBuffer_[idx * 2 + 1];
        }
        r += availableFrames;

        // Graceful cosine decay envelope to smooth out missing frames without clicks
        for (int32_t i = static_cast<int32_t>(framesRead); i < numFrames; ++i) {
            float decayFactor = std::max(0.0f, 1.0f - static_cast<float>(i - framesRead) / 96.0f); // 2ms fade out
            output[i * 2] = lastSampleL_ * decayFactor;
            output[i * 2 + 1] = lastSampleR_ * decayFactor;
            lastSampleL_ *= decayFactor;
            lastSampleR_ *= decayFactor;
        }
    } else {
        // Catmull-Rom Cubic Hermite Spline Resampling for zero-click drift correction
        for (int32_t i = 0; i < numFrames; ++i) {
            size_t idx = static_cast<size_t>(phase_);
            double frac = phase_ - idx;

            size_t ringIdx = (r + idx) % bufferFrames_;
            size_t i0 = (ringIdx > 0) ? ringIdx - 1 : bufferFrames_ - 1;
            size_t i1 = ringIdx;
            size_t i2 = (ringIdx + 1) % bufferFrames_;
            size_t i3 = (ringIdx + 2) % bufferFrames_;

            for (int ch = 0; ch < 2; ++ch) {
                float v0 = ringBuffer_[i0 * 2 + ch];
                float v1 = ringBuffer_[i1 * 2 + ch];
                float v2 = ringBuffer_[i2 * 2 + ch];
                float v3 = ringBuffer_[i3 * 2 + ch];

                // Cubic Hermite Spline coefficients
                float a = -0.5f * v0 + 1.5f * v1 - 1.5f * v2 + 0.5f * v3;
                float b = v0 - 2.5f * v1 + 2.0f * v2 - 0.5f * v3;
                float c = -0.5f * v0 + 0.5f * v2;
                float d = v1;

                float sample = static_cast<float>(((a * frac + b) * frac + c) * frac + d);
                output[i * 2 + ch] = sample;
                if (ch == 0) lastSampleL_ = sample;
                else lastSampleR_ = sample;
            }

            phase_ += ratio;
            if (phase_ >= 1.0) {
                size_t step = static_cast<size_t>(phase_);
                r += step;
                phase_ -= step;
            }
        }
    }

    readHead_.store(r, std::memory_order_release);

    // Update internal clock
    int64_t frameTimestamp = 0;
    int64_t nanoTimestamp = 0;
    if (audioStream->getTimestamp(CLOCK_MONOTONIC, &frameTimestamp, &nanoTimestamp) == oboe::Result::OK) {
        lastAudioClockUs_.store((frameTimestamp * 1000000LL) / 48000LL, std::memory_order_relaxed);
    } else {
        lastAudioClockUs_.fetch_add((static_cast<int64_t>(numFrames) * 1000000LL) / 48000LL, std::memory_order_relaxed);
    }

    return oboe::DataCallbackResult::Continue;
}

void GodModeAudioEngine::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) {
    LOGW("Oboe audio stream disconnected or error after close: %s. Attempting auto-reconnect...", oboe::convertToText(error));
    // Automatic recovery will restart stream if active
    if (running_.load(std::memory_order_relaxed)) {
        std::thread([this]() {
            stop();
            start();
        }).detach();
    }
}

void GodModeAudioEngine::setupThreadPriority() {
    pthread_t thisThread = pthread_self();

    // Comprobación previa de permisos del sistema para planificación en tiempo real (RLIMIT_RTPRIO)
    struct rlimit rlim{};
    bool hasRtPermission = false;
    if (getrlimit(RLIMIT_RTPRIO, &rlim) == 0) {
        hasRtPermission = (rlim.rlim_cur > 0);
    }

    bool schedFifoSuccess = false;
    if (hasRtPermission) {
        int maxFifo = sched_get_priority_max(SCHED_FIFO);
        if (maxFifo > 0) {
            struct sched_param params{};
            params.sched_priority = std::min(maxFifo, static_cast<int>(rlim.rlim_cur));
            int rc = pthread_setschedparam(thisThread, SCHED_FIFO, &params);
            if (rc == 0) {
                schedFifoSuccess = true;
                LOGI("Prioridad de tiempo real SCHED_FIFO activada exitosamente (prioridad=%d)", params.sched_priority);
            }
        }
    }

    // Mecanismo de contingencia: Si no hay permisos para SCHED_FIFO o falló la asignación,
    // se aplica forzadamente la prioridad de audio urgente de Android (-19 / -16),
    // impidiendo de manera estricta que el hilo decaiga a prioridades inferiores o normales.
    if (!schedFifoSuccess) {
        if (setpriority(PRIO_PROCESS, 0, -19) == 0) {
            LOGD("Hilo de audio elevado a prioridad máxima: ANDROID_PRIORITY_URGENT_AUDIO (-19)");
        } else if (setpriority(PRIO_PROCESS, 0, -16) == 0) {
            LOGD("Hilo de audio elevado a prioridad alta: ANDROID_PRIORITY_AUDIO (-16)");
        } else {
            setpriority(PRIO_PROCESS, 0, -10);
            LOGD("Hilo de audio elevado a prioridad de proceso alta (-10)");
        }
    }
}

} // namespace GodMode::Audio
