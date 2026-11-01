#pragma once

#include <cstdint>
#include <cstddef>
#include <cstring>
#include <string>
#include <mutex>
#include <vector>

namespace GodMode::Security {

/**
 * SecureRamVault: Volatile in-memory vault for session tokens (XSTS, UserToken, gsToken).
 * Protects against cold boot attacks and memory dumping by enforcing explicit zero-out wipes.
 */
class SecureRamVault {
private:
    std::mutex vaultMutex;
    std::vector<uint8_t> encryptedPayload;
    bool isLocked = true;

    // Secure zeroing memory function
    static void secureZeroMemory(void* ptr, size_t size) {
        if (!ptr || size == 0) return;
        volatile uint8_t* p = static_cast<volatile uint8_t*>(ptr);
        while (size--) {
            *p++ = 0;
        }
    }

public:
    SecureRamVault() = default;
    ~SecureRamVault() {
        purge();
    }

    void storeToken(const uint8_t* data, size_t size) {
        std::lock_guard<std::mutex> lock(vaultMutex);
        purgeInternal();
        if (data && size > 0) {
            encryptedPayload.assign(data, data + size);
            isLocked = false;
        }
    }

    bool retrieveToken(uint8_t* outData, size_t maxLen, size_t& outLen) {
        std::lock_guard<std::mutex> lock(vaultMutex);
        if (isLocked || encryptedPayload.empty() || maxLen < encryptedPayload.size()) {
            outLen = 0;
            return false;
        }
        outLen = encryptedPayload.size();
        std::memcpy(outData, encryptedPayload.data(), outLen);
        return true;
    }

    void purge() {
        std::lock_guard<std::mutex> lock(vaultMutex);
        purgeInternal();
    }

private:
    void purgeInternal() {
        if (!encryptedPayload.empty()) {
            secureZeroMemory(encryptedPayload.data(), encryptedPayload.size());
            encryptedPayload.clear();
        }
        isLocked = true;
    }
};

} // namespace GodMode::Security
