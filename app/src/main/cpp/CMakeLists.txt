cmake_minimum_required(VERSION 3.22.1)

project("godmode_native")

# Find required NDK libraries
find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)
find_library(mediandk-lib mediandk)

# Find Oboe for low-latency audio
find_package(oboe REQUIRED CONFIG)

# Add the main native library
add_library(godmode_native SHARED
        NativeStreamBridge.cpp
        stream/NativeStreamPipeline.cpp
        stream/AdaptiveGeoJitterBuffer.cpp
        stream/RtpH264Depacketizer.cpp
        stream/RtpH265Depacketizer.cpp
        stream/RtpAV1Depacketizer.cpp
        stream/RtpVVCDepacketizer.cpp
        audio/GodModeAudioEngine.cpp
)

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
)
