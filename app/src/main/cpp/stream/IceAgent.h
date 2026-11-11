#pragma once

#include <string>
#include <vector>
#include <functional>
#include <memory>
#include <mutex>
#include <thread>
#include <atomic>
#include <sys/socket.h>
#include <netinet/in.h>

namespace GodMode::Stream {

struct IceCandidate {
    std::string foundation;
    uint32_t componentId;
    std::string transport;
    uint32_t priority;
    std::string address;
    uint16_t port;
    std::string type;
    std::string relAddress;
    uint16_t relPort;
};

/**
 * IceAgent: Manages ICE gathering and connectivity checks.
 * Alignment: Recommended native C++ stack (Zero-WebView, Zero-WebRTC).
 */
class IceAgent {
public:
    using OnSelectedPairCallback = std::function<void(int socketFd, const sockaddr* remoteAddr, socklen_t addrLen)>;
    using OnCandidateGatheredCallback = std::function<void(const IceCandidate& candidate)>;

    IceAgent();
    ~IceAgent();

    void setRemoteCredentials(const std::string& ufrag, const std::string& pwd);
    void addRemoteCandidate(const IceCandidate& candidate);
    
    void startGathering(OnCandidateGatheredCallback callback);
    void startConnectivityChecks(OnSelectedPairCallback callback);
    
    void stop();

    bool isConnected() const { return connected.load(); }
    int getSelectedSocket() const { return selectedSocket.load(); }

private:
    void gatheringThreadFunc();
    void connectivityThreadFunc();

    std::string localUfrag;
    std::string localPwd;
    std::string remoteUfrag;
    std::string remotePwd;

    std::vector<IceCandidate> remoteCandidates;
    std::mutex candidatesMutex;

    std::atomic<bool> gathering;
    std::atomic<bool> checking;
    std::atomic<bool> connected;
    std::atomic<int> selectedSocket{-1};

    OnCandidateGatheredCallback candidateCallback;
    OnSelectedPairCallback selectedPairCallback;

    std::unique_ptr<std::thread> gatheringThread;
    std::unique_ptr<std::thread> checkingThread;
};

} // namespace GodMode::Stream
