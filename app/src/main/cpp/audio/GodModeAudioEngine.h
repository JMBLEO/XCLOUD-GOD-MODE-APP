#pragma once

#include <oboe/Oboe.h>
#include <memory>
#include <atomic>
#include <mutex>
#include <vector>

namespace GodMode::Audio {

/**
 * GodModeAudioEngine: High-performance Oboe pipeline for xCloud streaming.
 * Achieves <8ms latency using Exclusive mode and SCHED_FIFO priority.
 */
class GodModeAudioEngine : public oboe::AudioStreamCallback {
public:
    GodModeAudioEngine();
    virtual ~GodModeAudioEngine();

    bool start();
    void stop();

    // Injects raw PCM data (48kHz Stereo) into the ring buffer for playback
    void injectAudio(const int16_t* data, int32_t numFrames);

    // AudioStreamCallback implementation
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* audioStream, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    std::shared_ptr<oboe::AudioStream> stream;
    
    // Simple SPSC ring buffer for audio frames
    static constexpr size_t kBufferSize = 48000 * 2; // 1 second buffer
    std::vector<int16_t> ringBuffer;
    std::atomic<size_t> writePos{0};
    std::atomic<size_t> readPos{0};
    
    std::mutex engineMutex;
    std::atomic<bool> isRunning{false};

    void setupThreadPriority();
};

} // namespace GodMode::Audio
