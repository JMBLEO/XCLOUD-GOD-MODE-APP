#ifndef GODMODE_KERNEL_AFFINITY_H
#define GODMODE_KERNEL_AFFINITY_H

#include <sched.h>
#include <pthread.h>
#include <unistd.h>
#include <fstream>
#include <sstream>
#include <vector>
#include <algorithm>
#include <android/log.h>

#ifndef KERNEL_AFFINITY_LOG_TAG
#define KERNEL_AFFINITY_LOG_TAG "KernelAffinity"
#endif
#define K_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, KERNEL_AFFINITY_LOG_TAG, __VA_ARGS__)
#define K_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, KERNEL_AFFINITY_LOG_TAG, __VA_ARGS__)

namespace GodMode::Kernel {

class CpuTopology {
public:
    struct CoreInfo {
        int id;
        long maxFreq;
    };

    static void pinCurrentThreadToPerformanceCores(const char* threadName) {
        cpu_set_t cpuset;
        CPU_ZERO(&cpuset);

        std::vector<CoreInfo> cores = detectCpuTopology();
        if (cores.empty()) {
            CPU_SET(4, &cpuset);
            CPU_SET(5, &cpuset);
            CPU_SET(6, &cpuset);
            K_LOGD("[%s] Topology detection empty. Fallback to CPU affinity [4, 5, 6]", threadName);
        } else {
            std::sort(cores.begin(), cores.end(), [](const CoreInfo& a, const CoreInfo& b) {
                return a.maxFreq > b.maxFreq;
            });

            size_t countToPin = std::min(size_t(3), cores.size());
            for (size_t i = 0; i < countToPin; ++i) {
                CPU_SET(cores[i].id, &cpuset);
                K_LOGD("[%s] Pinned to core ID %d (Freq: %ld kHz)", threadName, cores[i].id, cores[i].maxFreq);
            }
        }

        // Use sched_setaffinity with pid 0 for current thread
        int rc = sched_setaffinity(0, sizeof(cpu_set_t), &cpuset);
        if (rc != 0) {
            K_LOGE("[%s] Failed to set sched_setaffinity: %d", threadName, rc);
        }

        struct sched_param param;
        int maxPriority = sched_get_priority_max(SCHED_FIFO);
        param.sched_priority = (maxPriority > 0) ? maxPriority : 99;
        
        rc = sched_setscheduler(0, SCHED_FIFO, &param);
        if (rc != 0) {
            K_LOGE("[%s] Failed to set SCHED_FIFO priority: %d", threadName, rc);
        } else {
            K_LOGD("[%s] Successfully elevated to SCHED_FIFO priority", threadName);
        }
    }

private:
    static std::vector<CoreInfo> detectCpuTopology() {
        std::vector<CoreInfo> cores;
        int numCores = sysconf(_SC_NPROCESSORS_CONF);
        for (int i = 0; i < numCores; ++i) {
            std::ostringstream path;
            path << "/sys/devices/system/cpu/cpu" << i << "/cpufreq/cpuinfo_max_freq";
            std::ifstream freqFile(path.str());
            long freq = 0;
            if (freqFile >> freq) {
                cores.push_back({i, freq});
            }
        }
        return cores;
    }
};

} // namespace GodMode::Kernel

#endif // GODMODE_KERNEL_AFFINITY_H
