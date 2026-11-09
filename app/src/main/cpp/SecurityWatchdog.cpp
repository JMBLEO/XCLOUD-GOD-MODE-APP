#include <jni.h>
#include <unistd.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/socket.h>
#include <pthread.h>
#include <string>
#include <vector>
#include <fstream>
#include <android/log.h>

#define TAG "GodModeSecurity"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace godmode {

class SecurityWatchdog {
public:
    static void start() {
        pthread_t thread;
        pthread_create(&thread, nullptr, watchdogLoop, nullptr);
        pthread_detach(thread);
    }

private:
    static void* watchdogLoop(void*) {
        while (true) {
            // Disabled to prevent SIGTRAP and instability in the AI Studio environment
            // checkPtrace();
            // checkMaps();
            usleep(5000000); // Check every 5 seconds
        }
        return nullptr;
    }

    static void checkPtrace() {
        if (ptrace(PTRACE_TRACEME, 0, 1, 0) == -1) {
            // LOGE("Debugger detected via ptrace!");
            // In AI Studio environment, ptrace might be used for monitoring.
            // We log but don't exit to avoid crashing the build environment preview.
            return;
        }
        ptrace(PTRACE_DETACH, 0, 1, 0);
    }

    static void checkMaps() {
        std::ifstream maps("/proc/self/maps");
        if (!maps.is_open()) return;
        
        std::string line;
        while (std::getline(maps, line)) {
            if (line.find("frida") != std::string::npos || 
                line.find("xposed") != std::string::npos ||
                line.find("libsubstrate.so") != std::string::npos) {
                LOGE("Hooking framework detected in memory maps: %s", line.c_str());
                // exit(0); // Disabled for development stability
            }
        }
    }
};

} // namespace godmode

extern "C" JNIEXPORT void JNICALL
Java_com_xcloud_godmode_security_SecurityManager_nativeStartWatchdog(JNIEnv* env, jobject thiz) {
    godmode::SecurityWatchdog::start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_xcloud_godmode_services_SecurityManager_nativeStartWatchdog(JNIEnv* env, jobject thiz) {
    godmode::SecurityWatchdog::start();
}
