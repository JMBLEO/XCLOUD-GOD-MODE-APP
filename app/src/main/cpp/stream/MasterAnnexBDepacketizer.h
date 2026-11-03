#ifndef MASTER_ANNEXB_DEPACKETIZER_H
#define MASTER_ANNEXB_DEPACKETIZER_H

#include <vector>
#include <cstdint>
#include <cstring>

namespace godmode {

class MasterAnnexBDepacketizer {
public:
    static std::vector<uint8_t> wrapInAnnexB(const uint8_t* payload, size_t size, bool isKeyframe) {
        std::vector<uint8_t> annexB;
        // 4-byte start code 0x00000001
        const uint8_t startCode[] = {0, 0, 0, 1};
        annexB.insert(annexB.end(), startCode, startCode + 4);
        annexB.insert(annexB.end(), payload, payload + size);
        return annexB;
    }

    // Advanced logic to find NAL units and handle fragmentation could go here
};

} // namespace godmode

#endif // MASTER_ANNEXB_DEPACKETIZER_H
