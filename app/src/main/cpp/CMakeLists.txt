cmake_minimum_required(VERSION 3.22.1)

project("godmode_native")

# Find required NDK libraries
find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)
find_library(mediandk-lib mediandk)

# Find Oboe for low-latency audio
find_package(oboe REQUIRED CONFIG)

# Try to find OpenSSL
find_package(OpenSSL)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Add the main native library
add_library(godmode_native SHARED
        NativeStreamBridge.cpp
        stream/NativeStreamPipeline.cpp
        stream/NativeVsyncSync.cpp
        stream/ChoreographerVsyncSync.cpp
        stream/CodecQuirks.cpp
        stream/ZeroCopyBuffer.cpp
        stream/AdaptiveGeoJitterBuffer.cpp
        stream/StunHolePunch.cpp
        stream/RtpH264Depacketizer.cpp
        stream/GodModeVideoIngress.cpp
        stream/RtpH265Depacketizer.cpp
        stream/RtpAV1Depacketizer.cpp
        stream/RtpVVCDepacketizer.cpp
        stream/MultiEngineStreamCore.cpp
        audio/GodModeAudioEngine.cpp
        audio/AAudioEngine.cpp
        bridge/BluetoothNativeBridge.cpp
        audio_engine.cpp
        native_streamer.cpp
        scheduler_manager.cpp
        SecurityWatchdog.cpp
        HardwareProfiler.cpp
        ConsoleHardwareBridge.cpp
        stream/UnifiedZeroCopyStreamReactor.cpp
        stream/NativeIceDtlsSrtpBridge.cpp
        stream/IceAgent.cpp
        stream/DtlsTransport.cpp
        stream/SrtpContext.cpp
        stream/SctpTransport.cpp
        godmode_six_engines.cpp
        godmode_jni_bridge.cpp
        godmode_carrier_runtime.cpp
        godmode_six_engine_session_injector.cpp
        godmode_reproducibility_anchor.cpp
)

# Link zlib for STUN fingerprint CRC32
find_library(z-lib z)

# Include directories
target_include_directories(godmode_native PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/stream
)

# Link libraries
target_link_libraries(godmode_native
        ${log-lib}
        ${android-lib}
        ${jnigraphics-lib}
        ${mediandk-lib}
        oboe::oboe
        EGL
        GLESv3
        aaudio
        vulkan
        ${z-lib}
)

if (OPENSSL_FOUND)
    target_link_libraries(godmode_native PRIVATE OpenSSL::SSL OpenSSL::Crypto)
    target_compile_definitions(godmode_native PRIVATE GM_HAS_OPENSSL=1)
endif()
