#ifndef GODMODE_DMABUF_PIPELINE_H
#define GODMODE_DMABUF_PIPELINE_H

#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <GLES3/gl32.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <android/log.h>

#ifndef DMABUF_LOG_TAG
#define DMABUF_LOG_TAG "DmaBufPipeline"
#endif
#define D_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, DMABUF_LOG_TAG, __VA_ARGS__)
#define D_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, DMABUF_LOG_TAG, __VA_ARGS__)

namespace GodMode::Stream {

class DmabufZeroCopyBridge {
public:
    static AHardwareBuffer* createHardwareBuffer(uint32_t width, uint32_t height) {
        AHardwareBuffer_Desc desc = {};
        desc.width = width;
        desc.height = height;
        desc.layers = 1;
        desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        desc.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_NEVER |
                     AHARDWAREBUFFER_USAGE_CPU_READ_NEVER |
                     AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                     AHARDWAREBUFFER_USAGE_VIDEO_ENCODE;

        AHardwareBuffer* buffer = nullptr;
        int err = AHardwareBuffer_allocate(&desc, &buffer);
        if (err != 0 || !buffer) {
            D_LOGE("Failed to allocate DMA-Buf backed AHardwareBuffer: %d", err);
            return nullptr;
        }
        D_LOGD("Successfully allocated zero-copy DMA-Buf HardwareBuffer (%dx%d)", width, height);
        return buffer;
    }

    static void releaseHardwareBuffer(AHardwareBuffer* buffer) {
        if (buffer) {
            AHardwareBuffer_release(buffer);
        }
    }
};

} // namespace GodMode::Stream

#endif // GODMODE_DMABUF_PIPELINE_H
