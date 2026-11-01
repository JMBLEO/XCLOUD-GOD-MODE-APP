#include "AdaptiveGeoJitterBuffer.h"
#include <cmath>
#include <algorithm>

namespace GodMode::Stream {

void AdaptiveGeoJitterBuffer::applyGeoBypassProfile(const GeoJitterConfig& newConfig) {
    config = newConfig;
    currentDepthMs.store(newConfig.targetJitterMs, std::memory_order_release);
}

void AdaptiveGeoJitterBuffer::updateJitterEstimate(uint32_t rtpTimestamp, uint64_t arrivalTimeUs) {
    if (lastArrivalTimeUs != 0 && rtpTimestamp != lastRtpTimestamp) {
        int64_t arrivalDeltaUs = static_cast<int64_t>(arrivalTimeUs - lastArrivalTimeUs);
        int64_t rtpDeltaUs = (static_cast<int64_t>(rtpTimestamp - lastRtpTimestamp) * 1000) / 90;
        double transitDiffMs = std::abs(arrivalDeltaUs - rtpDeltaUs) / 1000.0;

        double prevJitter = ewmaJitterMs.load(std::memory_order_relaxed);
        double updatedJitter = prevJitter + (transitDiffMs - prevJitter) / 16.0;
        ewmaJitterMs.store(updatedJitter, std::memory_order_relaxed);

        uint32_t dynamicTarget = static_cast<uint32_t>(std::ceil(updatedJitter * 2.2));
        uint32_t clampedDepth = std::clamp(dynamicTarget, config.targetJitterMs, config.maxJitterMs);
        currentDepthMs.store(clampedDepth, std::memory_order_release);
    }
    lastArrivalTimeUs = arrivalTimeUs;
    lastRtpTimestamp = rtpTimestamp;
}

bool AdaptiveGeoJitterBuffer::shouldWaitInNackGraceWindow(uint64_t gapDetectedTimeUs, uint64_t nowUs) const {
    uint64_t elapsedMs = (nowUs - gapDetectedTimeUs) / 1000;
    return elapsedMs < config.nackGraceWindowMs;
}

uint32_t AdaptiveGeoJitterBuffer::getActiveBufferDepthMs() const {
    return currentDepthMs.load(std::memory_order_acquire);
}

double AdaptiveGeoJitterBuffer::getMeasuredJitterMs() const {
    return ewmaJitterMs.load(std::memory_order_relaxed);
}

}
