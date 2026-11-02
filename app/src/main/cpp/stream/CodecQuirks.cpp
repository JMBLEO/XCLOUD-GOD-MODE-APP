#include <media/NdkMediaCodec.h>
#include <string>
#include <android/log.h>
#include <android/api-level.h>

#define TAG "CodecQuirks"

extern "C" void applyCodecQuirks(AMediaCodec* codec, AMediaFormat* format) {
    if (!codec || !format) return;
    
#if __ANDROID_API__ >= 28
    char* codecNameChars = nullptr;
    media_status_t status = AMediaCodec_getName(codec, &codecNameChars);
    if (status == AMEDIA_OK && codecNameChars) {
        std::string codecName = codecNameChars;
        __android_log_print(ANDROID_LOG_INFO, TAG, "Applying quirks for decoder: %s", codecName.c_str());
        
        if (codecName.find("OMX.MTK.") != std::string::npos) {
            AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_FORMAT, 19); // COLOR_FormatYUV420Planar
        } 
        else if (codecName.find("c2.exynos.") != std::string::npos) {
            int width = 1920;
            int alignedWidth = (width + 63) & ~63;
            AMediaFormat_setInt32(format, "stride", alignedWidth);
        }
        else if (codecName.find("OMX.qcom.") != std::string::npos) {
            AMediaFormat_setInt32(format, "vendor.qti-ext-dec-low-latency.enable", 1);
        }
        
        AMediaCodec_releaseName(codec, codecNameChars);
    }
#else
    __android_log_print(ANDROID_LOG_INFO, TAG, "API level < 28, skipping AMediaCodec_getName quirks");
#endif
}
