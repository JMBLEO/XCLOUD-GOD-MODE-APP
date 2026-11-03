#ifndef XCLOUD_PTP_CLOCK_SYNCHRONIZER_H
#define XCLOUD_PTP_CLOCK_SYNCHRONIZER_H

#include <cstdint>
#include <chrono>
#include <atomic>
#include <vector>
#include <algorithm>

namespace godmode {

class XcloudPtpClockSynchronizer {
public:
    XcloudPtpClockSynchronizer() : clockOffsetNs(0) {}

    void processHeartbeat(int64_t t1_azure_send_ns, int64_t t2_device_receive_ns, 
                        int64_t t3_device_send_ns, int64_t t4_azure_receive_ns) {
        // PTP (Precision Time Protocol) basic calculation
        // Round Trip Time (RTT)
        int64_t rtt = (t4_azure_receive_ns - t1_azure_send_ns) - (t3_device_send_ns - t2_device_receive_ns);
        
        // Offset between Azure clock and device clock
        int64_t offset = ((t2_device_receive_ns - t1_azure_send_ns) + (t3_device_send_ns - t4_azure_receive_ns)) / 2;
        
        // Simple moving average or filtering could be applied here
        // For now, let's use a small alpha for smoothing
        const double alpha = 0.1;
        clockOffsetNs.store(static_cast<int64_t>(clockOffsetNs.load() * (1.0 - alpha) + offset * alpha));
    }

    int64_t getAzureTimeNs() const {
        auto now = std::chrono::steady_clock::now().time_since_epoch();
        int64_t localNs = std::chrono::duration_cast<std::chrono::nanoseconds>(now).count();
        return localNs - clockOffsetNs.load();
    }

    int64_t getClockOffsetNs() const {
        return clockOffsetNs.load();
    }

private:
    std::atomic<int64_t> clockOffsetNs;
};

} // namespace godmode

#endif // XCLOUD_PTP_CLOCK_SYNCHRONIZER_H
