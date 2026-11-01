#include "GodModeAudioEngine.h"
#include <oboe/Oboe.h>
#include <android/log.h>
#include <pthread.h>
#include <sched.h>

#define LOG_TAG "GodModeAudio"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace GodMode::Audio {

GodModeAudioEngine::GodModeAudioEngine() {
    ringBuffer.resize(kBufferSize, 0);
}

GodModeAudioEngine::~GodModeAudioEngine() {
    stop();
}

bool GodModeAudioEngine::start() {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (isRunning) return true;

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Exclusive)
           ->setFormat(oboe::AudioFormat::I16)
           ->setChannelCount(oboe::ChannelCount::Stereo)
           ->setSampleRate(48000)
           ->setCallback(this);

    oboe::Result result = builder.openStream(stream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open Oboe stream: %s", oboe::convertToText(result));
        return false;
    }

    result = stream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start Oboe stream: %s", oboe::convertToText(result));
        stream->close();
        return false;
    }

    isRunning = true;
    LOGD("GodModeAudioEngine started (Exclusive mode, LowLatency)");
    return true;
}

void GodModeAudioEngine::stop() {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!isRunning) return;

    if (stream) {
        stream->stop();
        stream->close();
        stream.reset();
    }
    isRunning = false;
    LOGD("GodModeAudioEngine stopped");
}

void GodModeAudioEngine::injectAudio(const int16_t* data, int32_t numFrames) {
    size_t wp = writePos.load(std::memory_order_relaxed);
    for (int32_t i = 0; i < numFrames * 2; ++i) {
        ringBuffer[wp % kBufferSize] = data[i];
        wp++;
    }
    writePos.store(wp, std::memory_order_release);
}

oboe::DataCallbackResult GodModeAudioEngine::onAudioReady(oboe::AudioStream* audioStream, void* audioData, int32_t numFrames) {
    static bool prioritySet = false;
    if (!prioritySet) {
        setupThreadPriority();
        prioritySet = true;
    }
    
    int16_t* output = static_cast<int16_t*>(audioData);
    size_t rp = readPos.load(std::memory_order_relaxed);
    size_t wp = writePos.load(std::memory_order_acquire);
    
    int32_t samplesToRead = numFrames * 2;
    int32_t available = static_cast<int32_t>(wp - rp);
    
    if (available < samplesToRead) {
        int32_t i = 0;
        for (; i < available; ++i) {
            output[i] = ringBuffer[rp % kBufferSize];
            rp++;
        }
        for (; i < samplesToRead; ++i) {
            output[i] = 0;
        }
    } else {
        for (int32_t i = 0; i < samplesToRead; ++i) {
            output[i] = ringBuffer[rp % kBufferSize];
            rp++;
        }
    }
    
    readPos.store(rp, std::memory_order_release);
    return oboe::DataCallbackResult::Continue;
}

void GodModeAudioEngine::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) {
    LOGE("Oboe error after close: %s", oboe::convertToText(error));
}

void GodModeAudioEngine::setupThreadPriority() {
    pthread_t this_thread = pthread_self();
    struct sched_param params;
    params.sched_priority = sched_get_priority_max(SCHED_FIFO);
    
    int result = pthread_setschedparam(this_thread, SCHED_FIFO, &params);
    if (result == 0) {
        LOGD("Successfully set SCHED_FIFO priority for audio callback thread");
    } else {
        LOGE("Failed to set SCHED_FIFO priority: %d", result);
    }
}

} // namespace GodMode::Audio
