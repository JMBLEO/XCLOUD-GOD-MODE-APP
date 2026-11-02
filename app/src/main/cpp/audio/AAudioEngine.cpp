#include <aaudio/AAudio.h>
#include <cstdint>

// Real-time audio callback running on high-priority kernel thread
aaudio_data_callback_result_t audioDataCallback(
    AAudioStream* stream,
    void* userData,
    void* audioData,
    int32_t numFrames
) {
    float* outputBuffer = static_cast<float*>(audioData);
    
    // Fill with zero or pull from Opus ring buffer
    for (int i = 0; i < numFrames * 2; ++i) {
        outputBuffer[i] = 0.0f; // Temporary silence or PCM stream
    }
    
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

AAudioStream* initDirectAAudioStream() {
    AAudioStreamBuilder* builder = nullptr;
    AAudio_createStreamBuilder(&builder);
    
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setSampleRate(builder, 48000);
    AAudioStreamBuilder_setDataCallback(builder, audioDataCallback, nullptr);

    AAudioStream* stream = nullptr;
    AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);
    
    if (stream) {
        AAudioStream_requestStart(stream);
    }
    return stream;
}
