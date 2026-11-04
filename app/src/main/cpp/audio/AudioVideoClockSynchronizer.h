#pragma once

#include <aaudio/AAudio.h>
#include <vector>
#include <mutex>
#include <cmath>
#include <algorithm>
#include <atomic>
#include <android/log.h>

#define TAG_SYNC "GodModeClockSync"

namespace GodMode::Audio {

/**
 * AudioVideoClockSynchronizer: Managed high-priority real-time synchronizer
 * using a PI Controller to compensate for clock drift between the local device
 * and the Azure Cloud Gaming server.
 */
class AudioVideoClockSynchronizer {
private:
    std::atomic<double> currentSpeedRatio{1.0};
    std::atomic<int64_t> estimatedDriftUs{0};
    
    // PI Controller Gains
    const double Kp = 0.0000045; // Proportional Gain
    const double Ki = 0.000000008; // Integral Gain
    double integralError = 0.0;
    
    // Filtering for instantaneous drift
    double driftFilterState = 0.0;
    const double driftFilterAlpha = 0.08;

public:
    AudioVideoClockSynchronizer() = default;

    /**
     * Calibrate synchronization loop using fresh timestamps.
     * @param audioClockUs Local AAudio presentation clock.
     * @param videoPtsUs Timestamp of the last rendered video frame from Azure.
     */
    void updateDrift(int64_t audioClockUs, int64_t videoPtsUs) {
        if (audioClockUs <= 0 || videoPtsUs <= 0) return;

        int64_t instantaneousDrift = videoPtsUs - audioClockUs;
        
        // Low-pass filter for the drift measurement
        driftFilterState = (1.0 - driftFilterAlpha) * driftFilterState + driftFilterAlpha * static_cast<double>(instantaneousDrift);
        estimatedDriftUs.store(static_cast<int64_t>(driftFilterState), std::memory_order_relaxed);

        // PI Controller Logic
        double error = driftFilterState;
        integralError += error;
        
        // Anti-windup clamping
        integralError = std::clamp(integralError, -6000000.0, 6000000.0);
        
        double speedAdjustment = (Kp * error) + (Ki * integralError);
        double newRatio = 1.0 + speedAdjustment;
        
        // Clamp speed adjustment to +/- 0.8% to ensure audibility is maintained without pitch shifts
        currentSpeedRatio.store(std::clamp(newRatio, 0.992, 1.008), std::memory_order_release);
    }

    double getSpeedRatio() const {
        return currentSpeedRatio.load(std::memory_order_acquire);
    }

    int64_t getEstimatedDriftUs() const {
        return estimatedDriftUs.load(std::memory_order_relaxed);
    }

    void reset() {
        currentSpeedRatio.store(1.0);
        estimatedDriftUs.store(0);
        integralError = 0.0;
        driftFilterState = 0.0;
    }
};

} // namespace GodMode::Audio
