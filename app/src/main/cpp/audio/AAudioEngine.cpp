#include <aaudio/AAudio.h>
#include <vector>
#include <mutex>
#include <cmath>
#include <algorithm>
#include <android/log.h>

#define AUDIO_LOG_TAG "GodModeAudio"

class GodModeAdaptiveSyncAudioEngine {
private:
    AAudioStream* audioStream = nullptr;
    std::vector<float> pcmCircularBuffer;
    size_t writeHead = 0;
    size_t bufferSize = 0;
    std::mutex audioMtx;
    
    // PI Controller state for PLL Resampling
    double phase = 0.0;
    double currentSpeedRatio = 1.0;
    double integralError = 0.0;
    const double Kp = 0.000005; // Ganancia Proporcional
    const double Ki = 0.00000001; // Ganancia Integral
    
    int64_t lastVideoPtsUs = 0;
    int64_t lastAudioClockUs = 0;

    static aaudio_data_callback_result_t audioDataCallback(
        AAudioStream* stream,
        void* userData,
        void* audioData,
        int32_t numFrames
    ) {
        auto* self = static_cast<GodModeAdaptiveSyncAudioEngine*>(userData);
        float* outFloat = static_cast<float*>(audioData);
        
        std::lock_guard<std::mutex> lock(self->audioMtx);
        
        // Output with adaptive cubic hermite resampling (higher quality than linear)
        for (int32_t i = 0; i < numFrames; ++i) {
            size_t idx = static_cast<size_t>(self->phase);
            double frac = self->phase - idx;
            
            // Catmull-Rom / Cubic Hermite Spline for high-fidelity resampling
            size_t i0 = (idx > 0) ? (idx - 1) % self->bufferSize : (self->bufferSize - 1);
            size_t i1 = idx % self->bufferSize;
            size_t i2 = (idx + 1) % self->bufferSize;
            size_t i3 = (idx + 2) % self->bufferSize;

            for (int ch = 0; ch < 2; ++ch) {
                float v0 = self->pcmCircularBuffer[i0 * 2 + ch];
                float v1 = self->pcmCircularBuffer[i1 * 2 + ch];
                float v2 = self->pcmCircularBuffer[i2 * 2 + ch];
                float v3 = self->pcmCircularBuffer[i3 * 2 + ch];

                float a = -0.5f * v0 + 1.5f * v1 - 1.5f * v2 + 0.5f * v3;
                float b = v0 - 2.5f * v1 + 2.0f * v2 - 0.5f * v3;
                float c = -0.5f * v0 + 0.5f * v2;
                float d = v1;

                outFloat[i * 2 + ch] = static_cast<float>(((a * frac + b) * frac + c) * frac + d);
            }
            
            self->phase += self->currentSpeedRatio;
            if (self->phase >= self->bufferSize) self->phase -= self->bufferSize;
        }
        
        // Track internal audio clock for drift calculation
        self->lastAudioClockUs += (static_cast<int64_t>(numFrames) * 1000000LL) / 48000;
        
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

public:
    GodModeAdaptiveSyncAudioEngine(size_t capacityFrames = 19200) { // 400ms buffer
        bufferSize = capacityFrames;
        pcmCircularBuffer.assign(bufferSize * 2, 0.0f);
    }

    ~GodModeAdaptiveSyncAudioEngine() { stop(); }

    bool initLowLatencyStream(int sampleRate = 48000) {
        AAudioStreamBuilder* builder = nullptr;
        AAudio_createStreamBuilder(&builder);
        
        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
        AAudioStreamBuilder_setChannelCount(builder, 2);
        AAudioStreamBuilder_setSampleRate(builder, sampleRate);
        AAudioStreamBuilder_setDataCallback(builder, audioDataCallback, this);

        aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &audioStream);
        AAudioStreamBuilder_delete(builder);

        if (result == AAUDIO_OK && audioStream) {
            AAudioStream_requestStart(audioStream);
            __android_log_print(ANDROID_LOG_INFO, AUDIO_LOG_TAG, "Adaptive Sync Audio Engine Ready (Exclusive)");
            return true;
        }
        return false;
    }

    void calibrateSync(int64_t audioTimestampUs, int64_t videoTimestampUs) {
        std::lock_guard<std::mutex> lock(audioMtx);
        
        // 1. Calcular error de fase (drift)
        int64_t driftUs = videoTimestampUs - audioTimestampUs;
        
        // 2. Controlador PI para el Speed Ratio del remuestreador
        double error = static_cast<double>(driftUs);
        integralError += error;
        
        // Limitar la integral para evitar windup
        integralError = std::clamp(integralError, -5000000.0, 5000000.0);
        
        double newRatio = 1.0 + (Kp * error) + (Ki * integralError);
        
        // Limitar el ajuste a +/- 1% para que sea imperceptible al oído
        currentSpeedRatio = std::clamp(newRatio, 0.99, 1.01);
    }

    void injectPcmData(const float* data, size_t numFrames) {
        std::lock_guard<std::mutex> lock(audioMtx);
        for (size_t i = 0; i < numFrames; ++i) {
            pcmCircularBuffer[writeHead * 2] = data[i * 2];
            pcmCircularBuffer[writeHead * 2 + 1] = data[i * 2 + 1];
            writeHead = (writeHead + 1) % bufferSize;
        }
    }

    void stop() {
        if (audioStream) {
            AAudioStream_requestStop(audioStream);
            AAudioStream_close(audioStream);
            audioStream = nullptr;
        }
    }

    int64_t getInternalClockUs() {
        std::lock_guard<std::mutex> lock(audioMtx);
        return lastAudioClockUs;
    }
};

// Global instance bridge
static GodModeAdaptiveSyncAudioEngine gAudioEngine;

#include "AAudioEngine.h"

extern "C" {
    bool initLowLatencyAudio() { return gAudioEngine.initLowLatencyStream(); }
    void stopAudioEngine() { gAudioEngine.stop(); }
    void injectAudioFrames(const float* data, int numFrames) { gAudioEngine.injectPcmData(data, numFrames); }
    void syncAudioClock(int64_t audioUs, int64_t videoUs) { gAudioEngine.calibrateSync(audioUs, videoUs); }
    int64_t getInternalAudioClockUs() { return gAudioEngine.getInternalClockUs(); }
}

