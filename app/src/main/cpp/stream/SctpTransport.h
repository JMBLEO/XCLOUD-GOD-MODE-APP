#pragma once

#include <vector>
#include <functional>
#include <memory>
#include <mutex>
#include <string>

namespace GodMode::Stream {

/**
 * SctpTransport: Manages SCTP over DTLS for DataChannels (Input, Control, Message).
 * Alignment: usrsctp stack.
 */
class SctpTransport {
public:
    using OnDataReceivedCallback = std::function<void(uint16_t streamId, const uint8_t* data, size_t size)>;

    SctpTransport();
    ~SctpTransport();

    bool initialize();
    void processIncomingSctp(const uint8_t* data, size_t size);
    
    bool sendData(uint16_t streamId, const uint8_t* data, size_t size);
    
    void setCallback(OnDataReceivedCallback callback);

private:
    OnDataReceivedCallback onData;
    std::mutex sctpMutex;
    bool initialized = false;
};

} // namespace GodMode::Stream
