#include <jni.h>
#include <sched.h>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>

#define LOG_TAG "SchedulerManager"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

/**
 * Binds the calling thread (Network UDP or Oboe Audio) to specified performance cores
 * using sched_setaffinity and elevates its scheduling policy to SCHED_FIFO.
 */
JNIEXPORT void JNICALL
Java_com_xcloud_godmode_streaming_NativeStreamBridge_pinCurrentThreadToPerformanceTier(JNIEnv* env, jobject thiz, jint coreMask) {
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);

    if (coreMask == 0) {
        CPU_SET(4, &cpuset);
        CPU_SET(5, &cpuset);
        CPU_SET(6, &cpuset);
        LOGD("Pinning thread to default performance cores [4, 5, 6]");
    } else {
        for (int i = 0; i < 8; ++i) {
            if ((coreMask & (1 << i)) != 0) {
                CPU_SET(i, &cpuset);
                LOGD("Pinning thread to core ID %d", i);
            }
        }
    }

    int rc = sched_setaffinity(0, sizeof(cpu_set_t), &cpuset);
    if (rc != 0) {
        LOGE("Failed to set sched_setaffinity: %d", rc);
    }

    struct sched_param param;
    int maxPriority = sched_get_priority_max(SCHED_FIFO);
    param.sched_priority = (maxPriority > 0) ? maxPriority : 99;

    rc = sched_setscheduler(0, SCHED_FIFO, &param);
    if (rc != 0) {
        LOGE("Failed to set SCHED_FIFO priority: %d", rc);
    } else {
        LOGD("Thread successfully pinned and elevated to SCHED_FIFO Real-Time priority");
    }
}

}
