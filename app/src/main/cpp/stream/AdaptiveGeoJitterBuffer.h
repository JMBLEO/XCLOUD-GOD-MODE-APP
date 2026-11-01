#pragma once
#include <cstdint>
#include <atomic>

namespace GodMode::Stream {

struct GeoJitterConfig {
    uint32_t targetJitterMs;
    uint32_t maxJitterMs;
    uint32_t nackGraceWindowMs;
    uint32_t ringSize;
};

class AdaptiveGeoJitterBuffer {
private:
    GeoJitterConfig config{8, 24, 3, 128};
    std::atomic<double> ewmaJitterMs{2.0};
    std::atomic<uint32_t> currentDepthMs{8};
    uint64_t lastArrivalTimeUs = 0;
    uint32_t lastRtpTimestamp = 0;

public:
    void applyGeoBypassProfile(const GeoJitterConfig& newConfig);
    void updateJitterEstimate(uint32_t rtpTimestamp, uint64_t arrivalTimeUs);
    bool shouldWaitInNackGraceWindow(uint64_t gapDetectedTimeUs, uint64_t nowUs) const;
    uint32_t getActiveBufferDepthMs() const;
    double getMeasuredJitterMs() const;
};

}
