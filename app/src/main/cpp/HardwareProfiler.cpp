#include <jni.h>
#include <vulkan/vulkan.h>
#include <vector>
#include <cstring>
#include <dlfcn.h>
#include <unistd.h>
#include <android/log.h>

#define LOG_TAG "HardwareProfiler"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
extern "C" JNIEXPORT jint JNICALL
Java_com_xcloud_godmode_engine_HardwareCapabilityProfiler_nativeProbeGpuAndNpuCapabilities(
        JNIEnv* env, jobject thiz) {
    jint flags = 0;

    // 1. Audit NPU (NNAPI) via dynamic loading to avoid header issues
    void* libNn = dlopen("libneuralnetworks.so", RTLD_NOW | RTLD_LOCAL);
    if (libNn) {
        typedef int (*PFN_ANeuralNetworks_getDeviceCount)(uint32_t*);
        typedef int (*PFN_ANeuralNetworks_getDevice)(uint32_t, void**);
        typedef int (*PFN_ANeuralNetworksDevice_getType)(void*, int32_t*);

        auto fpGetCount = (PFN_ANeuralNetworks_getDeviceCount)dlsym(libNn, "ANeuralNetworks_getDeviceCount");
        auto fpGetDevice = (PFN_ANeuralNetworks_getDevice)dlsym(libNn, "ANeuralNetworks_getDevice");
        auto fpGetType = (PFN_ANeuralNetworksDevice_getType)dlsym(libNn, "ANeuralNetworksDevice_getType");

        if (fpGetCount && fpGetDevice && fpGetType) {
            uint32_t numDevices = 0;
            if (fpGetCount(&numDevices) == 0) {
                for (uint32_t i = 0; i < numDevices; ++i) {
                    void* dev = nullptr;
                    if (fpGetDevice(i, &dev) == 0 && dev) {
                        int32_t devType = 0;
                        fpGetType(dev, &devType);
                        if (devType == 2 /* ANEURALNETWORKS_DEVICE_ACCELERATOR */) {
                            flags |= 0x04; 
                            break;
                        }
                    }
                }
            }
        }
        dlclose(libNn);
    }

    // 2. Audit GPU (Vulkan) - Check for DRI nodes first to avoid platform error logs
    if (access("/dev/dri", F_OK) != 0) {
        LOGI("DRI nodes not found (/dev/dri), skipping Vulkan capability probe.");
    } else {
        VkApplicationInfo appInfo = { VK_STRUCTURE_TYPE_APPLICATION_INFO, nullptr, "Probe", 1, "GodMode", 1, VK_API_VERSION_1_1 };
        VkInstanceCreateInfo instInfo = { VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, nullptr, 0, &appInfo, 0, nullptr, 0, nullptr };
        VkInstance instance = VK_NULL_HANDLE;

        if (vkCreateInstance(&instInfo, nullptr, &instance) == VK_SUCCESS) {
        uint32_t gpuCount = 0;
        vkEnumeratePhysicalDevices(instance, &gpuCount, nullptr);
        if (gpuCount > 0) {
            std::vector<VkPhysicalDevice> gpus(gpuCount);
            vkEnumeratePhysicalDevices(instance, &gpuCount, gpus.data());
            VkPhysicalDevice gpu = gpus[0];

            uint32_t extCount = 0;
            vkEnumerateDeviceExtensionProperties(gpu, nullptr, &extCount, nullptr);
            std::vector<VkExtensionProperties> exts(extCount);
            vkEnumerateDeviceExtensionProperties(gpu, nullptr, &extCount, exts.data());

            bool hasVideoQueueExt = false;
            bool hasFp16Ext = false;
            bool hasAhbExt = false;

            for (const auto& e : exts) {
                if (std::strcmp(e.extensionName, "VK_KHR_video_decode_queue") == 0) hasVideoQueueExt = true;
                if (std::strcmp(e.extensionName, "VK_KHR_shader_float16_int8") == 0) hasFp16Ext = true;
                if (std::strcmp(e.extensionName, "VK_ANDROID_external_memory_android_hardware_buffer") == 0) hasAhbExt = true;
            }

            if (hasFp16Ext && hasAhbExt) {
                flags |= 0x01; // Vulkan FP16 + AHB
            }
            if (hasVideoQueueExt) {
                flags |= 0x02; // Vulkan Video ASIC
            }
        }
        vkDestroyInstance(instance, nullptr);
        }
    }

    return flags;
}
