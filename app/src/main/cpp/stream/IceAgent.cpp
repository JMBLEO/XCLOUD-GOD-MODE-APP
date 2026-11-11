#include "IceAgent.h"
#include <android/log.h>
#include <cstring>
#include <chrono>

#define TAG "IceAgent"

namespace GodMode::Stream {

IceAgent::IceAgent() : gathering(false), checking(false), connected(false) {}

IceAgent::~IceAgent() {
    stop();
}

void IceAgent::setRemoteCredentials(const std::string& ufrag, const std::string& pwd) {
    remoteUfrag = ufrag;
    remotePwd = pwd;
}

void IceAgent::addRemoteCandidate(const IceCandidate& candidate) {
    std::lock_guard<std::mutex> lock(candidatesMutex);
    remoteCandidates.push_back(candidate);
}

void IceAgent::startGathering(OnCandidateGatheredCallback callback) {
    candidateCallback = callback;
    gathering = true;
    gatheringThread = std::make_unique<std::thread>(&IceAgent::gatheringThreadFunc, this);
}

void IceAgent::startConnectivityChecks(OnSelectedPairCallback callback) {
    selectedPairCallback = callback;
    checking = true;
    checkingThread = std::make_unique<std::thread>(&IceAgent::connectivityThreadFunc, this);
}

void IceAgent::stop() {
    gathering = false;
    checking = false;
    if (gatheringThread && gatheringThread->joinable()) gatheringThread->join();
    if (checkingThread && checkingThread->joinable()) checkingThread->join();
}

void IceAgent::gatheringThreadFunc() {
    __android_log_print(ANDROID_LOG_INFO, TAG, "ICE Gathering Started");
    // Implementation for gathering local candidates (host, srflx) goes here.
    // For now, we stub a host candidate.
    IceCandidate c;
    c.address = "0.0.0.0";
    c.port = 9002;
    c.type = "host";
    if (candidateCallback) candidateCallback(c);
}

void IceAgent::connectivityThreadFunc() {
    __android_log_print(ANDROID_LOG_INFO, TAG, "ICE Connectivity Checks Started");
    // Implementation for STUN binding requests to remote candidates goes here.
    // Once a pair is selected, trigger the callback.
    
    // Simulate successful connection for now
    std::this_thread::sleep_for(std::chrono::milliseconds(500));
    connected = true;
    if (selectedPairCallback) {
        // Pass dummy values to trigger DTLS
        struct sockaddr_in addr;
        std::memset(&addr, 0, sizeof(addr));
        selectedPairCallback(123, (struct sockaddr*)&addr, sizeof(addr)); 
    }
}

} // namespace GodMode::Stream
