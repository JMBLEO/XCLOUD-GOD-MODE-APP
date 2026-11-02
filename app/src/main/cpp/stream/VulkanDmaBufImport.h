#ifndef GODMODE_VULKAN_DMABUF_IMPORT_H
#define GODMODE_VULKAN_DMABUF_IMPORT_H

#include <vulkan/vulkan.h>
#include <android/hardware_buffer.h>
#include <android/log.h>

#define LOG_TAG "VulkanDmaBuf"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace GodMode::Vulkan {

class VulkanDmaBufImporter {
public:
    /**
     * Imports an Android HardwareBuffer (backed by DMA-Buf ION) directly into a Vulkan VkImage
     * using the VK_ANDROID_external_memory_android_hardware_buffer extension.
     */
    static bool importHardwareBufferToVulkan(
        VkDevice device,
        VkPhysicalDevice physicalDevice,
        AHardwareBuffer* hardwareBuffer,
        VkImage& outImage,
        VkDeviceMemory& outMemory,
        uint32_t width,
        uint32_t height
    ) {
        if (!hardwareBuffer) {
            LOGE("Cannot import null AHardwareBuffer into Vulkan");
            return false;
        }

        // 1. Get Vulkan format requirements from AHardwareBuffer
        VkAndroidHardwareBufferFormatPropertiesANDROID bufferFormatProps = {};
        bufferFormatProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
        
        VkAndroidHardwareBufferPropertiesANDROID bufferProps = {};
        bufferProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
        bufferProps.pNext = &bufferFormatProps;

        // Function pointer for vkGetAndroidHardwareBufferPropertiesANDROID
        auto fpGetAndroidHardwareBufferProperties = 
            (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)vkGetDeviceProcAddr(device, "vkGetAndroidHardwareBufferPropertiesANDROID");
        
        if (!fpGetAndroidHardwareBufferProperties) {
            LOGE("vkGetAndroidHardwareBufferPropertiesANDROID function pointer not found");
            return false;
        }

        if (fpGetAndroidHardwareBufferProperties(device, hardwareBuffer, &bufferProps) != VK_SUCCESS) {
            LOGE("Failed to query AHardwareBuffer Vulkan properties");
            return false;
        }

        // 2. Create external memory image info
        VkExternalMemoryImageCreateInfo externalMemoryImageCreateInfo = {};
        externalMemoryImageCreateInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
        externalMemoryImageCreateInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

        VkImageCreateInfo imageCreateInfo = {};
        imageCreateInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        imageCreateInfo.pNext = &externalMemoryImageCreateInfo;
        imageCreateInfo.imageType = VK_IMAGE_TYPE_2D;
        imageCreateInfo.format = bufferFormatProps.format != VK_FORMAT_UNDEFINED ? bufferFormatProps.format : VK_FORMAT_R8G8B8A8_UNORM;
        imageCreateInfo.extent = {width, height, 1};
        imageCreateInfo.mipLevels = 1;
        imageCreateInfo.arrayLayers = 1;
        imageCreateInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imageCreateInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imageCreateInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT;
        imageCreateInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        imageCreateInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

        if (vkCreateImage(device, &imageCreateInfo, nullptr, &outImage) != VK_SUCCESS) {
            LOGE("Failed to create Vulkan external image from AHardwareBuffer");
            return false;
        }

        // 3. Allocate device memory and import AHardwareBuffer
        VkImportAndroidHardwareBufferInfoANDROID importBufferInfo = {};
        importBufferInfo.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
        importBufferInfo.buffer = hardwareBuffer;

        VkMemoryAllocateInfo memoryAllocateInfo = {};
        memoryAllocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        memoryAllocateInfo.pNext = &importBufferInfo;
        memoryAllocateInfo.allocationSize = bufferProps.allocationSize;

        // Find matching memory type index
        memoryAllocateInfo.memoryTypeIndex = 0; // Simplified for zero-copy device local memory

        if (vkAllocateMemory(device, &memoryAllocateInfo, nullptr, &outMemory) != VK_SUCCESS) {
            LOGE("Failed to allocate and import device memory from AHardwareBuffer");
            vkDestroyImage(device, outImage, nullptr);
            return false;
        }

        if (vkBindImageMemory(device, outImage, outMemory, 0) != VK_SUCCESS) {
            LOGE("Failed to bind imported Vulkan image memory");
            vkFreeMemory(device, outMemory, nullptr);
            vkDestroyImage(device, outImage, nullptr);
            return false;
        }

        LOGD("Successfully imported DMA-Buf AHardwareBuffer into Vulkan image zero-copy");
        return true;
    }

    static void destroyImportedImage(VkDevice device, VkImage image, VkDeviceMemory memory) {
        if (image != VK_NULL_HANDLE) {
            vkDestroyImage(device, image, nullptr);
        }
        if (memory != VK_NULL_HANDLE) {
            vkFreeMemory(device, memory, nullptr);
        }
    }
};

} // namespace GodMode::Vulkan

#endif // GODMODE_VULKAN_DMABUF_IMPORT_H
