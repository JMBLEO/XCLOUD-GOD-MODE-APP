#include "SctpTransport.h"
#include <android/log.h>

#define TAG "SctpTransport"

namespace GodMode::Stream {

SctpTransport::SctpTransport() : initialized(false) {}

SctpTransport::~SctpTransport() {}

bool SctpTransport::initialize() {
    std::lock_guard<std::mutex> lock(sctpMutex);
    __android_log_print(ANDROID_LOG_INFO, TAG, "Initializing SCTP transport...");
    // usrsctp initialization logic goes here.
    initialized = true;
    return true;
}

void SctpTransport::processIncomingSctp(const uint8_t* data, size_t size) {
    if (!initialized) return;
    // Feed data to usrsctp_conninput
}

bool SctpTransport::sendData(uint16_t streamId, const uint8_t* data, size_t size) {
    if (!initialized) return false;
    // usrsctp_send
    return true;
}

void SctpTransport::setCallback(OnDataReceivedCallback callback) {
    onData = callback;
}

} // namespace GodMode::Stream
