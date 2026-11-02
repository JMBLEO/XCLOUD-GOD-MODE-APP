#include <oboe/Oboe.h>
#include <atomic>
#include <vector>
#include <cstring>
#include <android/log.h>

#define LOG_TAG "XboxAudioEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * XboxAudioEngine: High-performance Oboe NDK Audio Sink with lock-free ring buffer
 * for 48kHz Stereo float PCM decoding from Opus UDP datagrams.
 */
class XboxAudioEngine : public oboe::AudioStreamDataCallback {
private:
    std::shared_ptr<oboe::AudioStream> stream;
    static constexpr size_t RING_BUFFER_SIZE = 48000 * 2 * 2; // ~1 sec float stereo buffer
    std::vector<float> pcmRingBuffer;
    std::atomic<size_t> writeHead{0};
    std::atomic<size_t> readHead{0};
    std::atomic<bool> isRunning{false};

public:
    XboxAudioEngine() {
        pcmRingBuffer.resize(RING_BUFFER_SIZE, 0.0f);
    }

    ~XboxAudioEngine() {
        stopEngine();
    }

    bool startEngine() {
        if (isRunning.load()) return true;

        oboe::AudioStreamBuilder builder;
        builder.setFormat(oboe::AudioFormat::Float)
               ->setChannelCount(oboe::ChannelCount::Stereo)
               ->setSampleRate(48000)
               ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
               ->setSharingMode(oboe::SharingMode::Exclusive)
               ->setDataCallback(this);

        oboe::Result result = builder.openStream(stream);
        if (result != oboe::Result::OK) {
            LOGE("Failed to open Oboe audio stream: %s", oboe::convertToText(result));
            return false;
        }

        result = stream->requestStart();
        if (result != oboe::Result::OK) {
            LOGE("Failed to start Oboe audio stream: %s", oboe::convertToText(result));
            stream->close();
            return false;
        }

        isRunning.store(true);
        LOGD("XboxAudioEngine Oboe stream started at 48kHz Stereo Float Exclusive");
        return true;
    }

    void stopEngine() {
        if (!isRunning.load()) return;
        if (stream) {
            stream->stop();
            stream->close();
            stream.reset();
        }
        isRunning.store(false);
        LOGD("XboxAudioEngine stopped");
    }

    void writePcmData(const float* pcmSamples, size_t numSamples) {
        size_t w = writeHead.load(std::memory_order_relaxed);
        for (size_t i = 0; i < numSamples; ++i) {
            pcmRingBuffer[w % RING_BUFFER_SIZE] = pcmSamples[i];
            w++;
        }
        writeHead.store(w, std::memory_order_release);
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *oboeStream, void *audioData, int32_t numFrames) override {
        float *output = static_cast<float *>(audioData);
        size_t samplesToRead = static_cast<size_t>(numFrames * 2);

        size_t r = readHead.load(std::memory_order_relaxed);
        size_t w = writeHead.load(std::memory_order_acquire);
        size_t available = (w >= r) ? (w - r) : 0;

        if (available < samplesToRead) {
            size_t i = 0;
            for (; i < available; ++i) {
                output[i] = pcmRingBuffer[r % RING_BUFFER_SIZE];
                r++;
            }
            // Buffer underrun PLC (fill silence to prevent clicks & crackling)
            std::memset(output + i, 0, (samplesToRead - i) * sizeof(float));
        } else {
            for (size_t i = 0; i < samplesToRead; ++i) {
                output[i] = pcmRingBuffer[r % RING_BUFFER_SIZE];
                r++;
            }
        }

        readHead.store(r, std::memory_order_release);
        return oboe::DataCallbackResult::Continue;
    }
};
