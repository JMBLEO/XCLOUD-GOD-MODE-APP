#include <android/hardware_buffer.h>
#include <android/log.h>

#define TAG "ZeroCopyBuffer"

extern "C" AHardwareBuffer* createZeroCopyBuffer(int width, int height) {
    AHardwareBuffer_Desc desc = {};
    desc.width = width;
    desc.height = height;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_Y8Cb8Cr8_420; // Standard video format
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN;
    
    AHardwareBuffer* buffer = nullptr;
    int status = AHardwareBuffer_allocate(&desc, &buffer);
    if (status != 0 || !buffer) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to allocate AHardwareBuffer (%dx%d)", width, height);
        return nullptr;
    }
    
    __android_log_print(ANDROID_LOG_INFO, TAG, "Successfully allocated AHardwareBuffer (%dx%d)", width, height);
    return buffer;
}

extern "C" void releaseZeroCopyBuffer(AHardwareBuffer* buffer) {
    if (buffer) {
        AHardwareBuffer_release(buffer);
    }
}
