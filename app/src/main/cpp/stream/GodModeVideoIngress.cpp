#include "GodModeVideoIngress.h"
#include <algorithm>

namespace GodMode::Stream {

// -----------------------------------------------------------------------------
// BitReader para SPS
// -----------------------------------------------------------------------------
class BitReader {
private:
    const uint8_t* data;
    size_t totalBits;
    size_t bitPos = 0;

public:
    BitReader(const uint8_t* buf, size_t byteLen)
        : data(buf), totalBits(byteLen * 8), bitPos(0) {}

    uint32_t readBit() {
        if (bitPos >= totalBits) return 0;
        uint32_t bit = (data[bitPos >> 3] >> (7 - (bitPos & 7))) & 1;
        bitPos++;
        return bit;
    }

    uint32_t readBits(int n) {
        uint32_t val = 0;
        for (int i = 0; i < n; ++i) {
            val = (val << 1) | readBit();
        }
        return val;
    }

    uint32_t readUe() {
        int leadingZeros = 0;
        while (readBit() == 0 && bitPos < totalBits && leadingZeros < 32) {
            leadingZeros++;
        }
        if (leadingZeros == 0) return 0;
        return (1U << leadingZeros) - 1U + readBits(leadingZeros);
    }

    int32_t readSe() {
        uint32_t ue = readUe();
        return (ue & 1) ? static_cast<int32_t>((ue + 1) >> 1) : -static_cast<int32_t>(ue >> 1);
    }

    bool hasMoreBits() const {
        return bitPos < totalBits;
    }
};

void H264SpsParser::removeEmulationPrevention(const uint8_t* src, size_t len, std::vector<uint8_t>& rbsp) {
    rbsp.clear();
    rbsp.reserve(len);
    for (size_t i = 0; i < len; ++i) {
        if (i + 2 < len && src[i] == 0x00 && src[i + 1] == 0x00 && src[i + 2] == 0x03) {
            rbsp.push_back(0x00);
            rbsp.push_back(0x00);
            i += 2; // Omitir el byte 0x03 de prevención de emulación
        } else {
            rbsp.push_back(src[i]);
        }
    }
}

bool H264SpsParser::parseSps(const uint8_t* spsNal, size_t len, H264SpsInfo& outInfo) {
    if (!spsNal || len < 4) return false;

    // Saltar Start Code si viene incluido
    size_t offset = 0;
    if (len >= 4 && spsNal[0] == 0x00 && spsNal[1] == 0x00) {
        if (spsNal[2] == 0x01) offset = 3;
        else if (spsNal[2] == 0x00 && spsNal[3] == 0x01) offset = 4;
    }

    if (offset >= len) return false;
    uint8_t nalHeader = spsNal[offset];
    if ((nalHeader & 0x1F) != 7) {
        // No es tipo SPS (7)
        return false;
    }
    offset++; // Saltar byte de cabecera NAL

    std::vector<uint8_t> rbsp;
    removeEmulationPrevention(spsNal + offset, len - offset, rbsp);
    if (rbsp.size() < 3) return false;

    BitReader reader(rbsp.data(), rbsp.size());

    uint32_t profileIdc = reader.readBits(8);
    outInfo.profileIdc = static_cast<int>(profileIdc);
    reader.readBits(8); // constraint flags
    outInfo.levelIdc = static_cast<int>(reader.readBits(8));
    reader.readUe();    // seq_parameter_set_id

    uint32_t chromaFormatIdc = 1; // 4:2:0 por defecto
    if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 || profileIdc == 244 ||
        profileIdc == 44  || profileIdc == 83  || profileIdc == 86  || profileIdc == 118 ||
        profileIdc == 128 || profileIdc == 138 || profileIdc == 139 || profileIdc == 134) {
        chromaFormatIdc = reader.readUe();
        if (chromaFormatIdc == 3) {
            reader.readBit(); // separate_colour_plane_flag
        }
        reader.readUe(); // bit_depth_luma_minus8
        reader.readUe(); // bit_depth_chroma_minus8
        reader.readBit(); // qpprime_y_zero_transform_bypass_flag
        if (reader.readBit()) { // seq_scaling_matrix_present_flag
            for (int i = 0; i < ((chromaFormatIdc != 3) ? 8 : 12); ++i) {
                if (reader.readBit()) { // seq_scaling_list_present_flag
                    int lastScale = 8;
                    int nextScale = 8;
                    int sizeOfScalingList = (i < 6) ? 16 : 64;
                    for (int j = 0; j < sizeOfScalingList; ++j) {
                        if (nextScale != 0) {
                            int32_t deltaScale = reader.readSe();
                            nextScale = (lastScale + deltaScale + 256) % 256;
                        }
                        lastScale = (nextScale == 0) ? lastScale : nextScale;
                    }
                }
            }
        }
    }

    reader.readUe(); // log2_max_frame_num_minus4
    uint32_t picOrderCntType = reader.readUe();
    if (picOrderCntType == 0) {
        reader.readUe(); // log2_max_pic_order_cnt_lsb_minus4
    } else if (picOrderCntType == 1) {
        reader.readBit(); // delta_pic_order_always_zero_flag
        reader.readSe();  // offset_for_non_ref_pic
        reader.readSe();  // offset_for_top_to_bottom_field
        uint32_t numRef = reader.readUe();
        for (uint32_t i = 0; i < numRef; ++i) {
            reader.readSe(); // offset_for_ref_frame
        }
    }

    reader.readUe();  // max_num_ref_frames
    reader.readBit(); // gaps_in_frame_num_value_allowed_flag

    uint32_t picWidthInMbsMinus1 = reader.readUe();
    uint32_t picHeightInMapUnitsMinus1 = reader.readUe();
    uint32_t frameMbsOnlyFlag = reader.readBit();
    if (!frameMbsOnlyFlag) {
        reader.readBit(); // mb_adaptive_frame_field_flag
    }
    reader.readBit(); // direct_8x8_inference_flag

    uint32_t cropLeft = 0, cropRight = 0, cropTop = 0, cropBottom = 0;
    if (reader.readBit()) { // frame_cropping_flag
        cropLeft   = reader.readUe();
        cropRight  = reader.readUe();
        cropTop    = reader.readUe();
        cropBottom = reader.readUe();
    }

    // Cálculo exacto de resolución en píxeles
    uint32_t cropUnitX = (chromaFormatIdc == 0) ? 1 : (chromaFormatIdc == 3 ? 1 : 2);
    uint32_t cropUnitY = (chromaFormatIdc == 0) ? (2 - frameMbsOnlyFlag) : (chromaFormatIdc == 3 ? (2 - frameMbsOnlyFlag) : 2 * (2 - frameMbsOnlyFlag));

    outInfo.width = static_cast<int>((picWidthInMbsMinus1 + 1) * 16 - (cropLeft + cropRight) * cropUnitX);
    outInfo.height = static_cast<int>((2 - frameMbsOnlyFlag) * (picHeightInMapUnitsMinus1 + 1) * 16 - (cropTop + cropBottom) * cropUnitY);

    // VUI Parameters (video_full_range_flag y matrix_coefficients)
    outInfo.fullRange = false;
    outInfo.matrixCoefficients = 1; // BT.709 por defecto para HD
    outInfo.colourPrimaries = 1;
    outInfo.transferCharacteristics = 1;

    if (reader.hasMoreBits() && reader.readBit()) { // vui_parameters_present_flag
        if (reader.readBit()) { // aspect_ratio_info_present_flag
            uint32_t arIdc = reader.readBits(8);
            if (arIdc == 255) { // Extended_SAR
                reader.readBits(16); // sar_width
                reader.readBits(16); // sar_height
            }
        }
        if (reader.readBit()) { // overscan_info_present_flag
            reader.readBit(); // overscan_appropriate_flag
        }
        if (reader.readBit()) { // video_signal_type_present_flag
            reader.readBits(3); // video_format
            outInfo.fullRange = (reader.readBit() == 1); // video_full_range_flag (0: Limited, 1: Full)
            if (reader.readBit()) { // colour_description_present_flag
                outInfo.colourPrimaries = static_cast<uint8_t>(reader.readBits(8));
                outInfo.transferCharacteristics = static_cast<uint8_t>(reader.readBits(8));
                outInfo.matrixCoefficients = static_cast<uint8_t>(reader.readBits(8));
            }
        }
    }

    outInfo.valid = (outInfo.width > 0 && outInfo.height > 0);
    if (outInfo.valid) {
        GM_LOGI("SPS parseado: %dx%d, Perfil=%d, Rango=%s, Matriz=%d (%s)",
                outInfo.width, outInfo.height, outInfo.profileIdc,
                outInfo.fullRange ? "Full (0-255)" : "Limited (16-235)",
                outInfo.matrixCoefficients,
                outInfo.matrixCoefficients == 1 ? "BT.709" : (outInfo.matrixCoefficients == 6 ? "BT.601" : "Otro"));
    }
    return outInfo.valid;
}

// -----------------------------------------------------------------------------
// Buffer de Reordenamiento Circular con Grace Inbox de NACKs
// -----------------------------------------------------------------------------
RtpSequenceReorderBuffer::RtpSequenceReorderBuffer() {
    reset();
}

void RtpSequenceReorderBuffer::reset() {
    for (size_t i = 0; i < RING_CAPACITY; ++i) {
        ring[i].occupied = false;
        ring[i].size = 0;
    }
    expectedSeq = 0;
    highestSeq = 0;
    initialized = false;
    pendingHoles.clear();
    lastServerSsrc = 0;
    lastNackSentTime = std::chrono::steady_clock::now();
}

void RtpSequenceReorderBuffer::pushPacket(
    const RtpVideoPacket& pkt,
    uint32_t clientSsrc,
    const PacketConsumer& consumer,
    const NackSender& nackSender
) {
    if (!pkt.valid || pkt.payloadLen == 0 || pkt.payloadLen > 2048) return;

    lastServerSsrc = pkt.ssrc;
    uint16_t seq = pkt.seqNum;
    auto now = std::chrono::steady_clock::now();

    if (!initialized) {
        expectedSeq = seq;
        highestSeq = seq;
        initialized = true;

        consumer(pkt.payload, pkt.payloadLen, pkt.timestamp, pkt.marker, false);
        expectedSeq++;
        return;
    }

    int16_t diff = seqDiff(seq, expectedSeq);

    if (diff < 0) {
        // Paquete duplicado o tardío: descartar sin procesar
        return;
    }

    if (diff == 0) {
        // Paquete en orden exacto: procesar inmediatamente
        consumer(pkt.payload, pkt.payloadLen, pkt.timestamp, pkt.marker, false);
        expectedSeq++;

        // Eliminar de huecos pendientes si estaba registrado
        pendingHoles.erase(
            std::remove_if(pendingHoles.begin(), pendingHoles.end(),
                           [seq](const MissingHole& h) { return h.seq == seq; }),
            pendingHoles.end()
        );

        // Drenar paquetes subsiguientes que ya estén en la ranura circular
        while (true) {
            size_t slotIdx = expectedSeq & (RING_CAPACITY - 1);
            Slot& slot = ring[slotIdx];
            if (slot.occupied && slot.seqNum == expectedSeq) {
                consumer(slot.data, slot.size, slot.timestamp, slot.marker, false);
                slot.occupied = false;
                slot.size = 0;
                expectedSeq++;
            } else {
                break;
            }
        }
        return;
    }

    // diff > 0: Paquete futuro (detectada brecha de secuencia)
    if (diff >= static_cast<int16_t>(RING_CAPACITY) || diff > MAX_REORDER_DRIFT) {
        // Desfase mayor a la capacidad: resincronización forzada
        GM_LOGW("Desfase RTP masivo detectado (%d). Resincronizando secuencia a %u", diff, seq);
        flushPendingPackets(consumer);
        expectedSeq = seq;
        highestSeq = seq;
        consumer(pkt.payload, pkt.payloadLen, pkt.timestamp, pkt.marker, true);
        expectedSeq++;
        pendingHoles.clear();
        return;
    }

    // Almacenar en ranura circular
    size_t slotIdx = seq & (RING_CAPACITY - 1);
    Slot& slot = ring[slotIdx];
    std::memcpy(slot.data, pkt.payload, pkt.payloadLen);
    slot.size = pkt.payloadLen;
    slot.seqNum = seq;
    slot.timestamp = pkt.timestamp;
    slot.marker = pkt.marker;
    slot.occupied = true;
    slot.arrivalTime = now;

    // Registrar paquetes faltantes en el Grace Inbox si son nuevos
    if (seqDiff(seq, highestSeq) > 0) {
        for (uint16_t miss = (highestSeq + 1); seqDiff(miss, seq) < 0; ++miss) {
            if (seqDiff(miss, expectedSeq) >= 0) {
                bool alreadyTracked = false;
                for (const auto& h : pendingHoles) {
                    if (h.seq == miss) { alreadyTracked = true; break; }
                }
                if (!alreadyTracked && pendingHoles.size() < 64) {
                    pendingHoles.push_back({miss, now, 0, false});
                }
            }
        }
        highestSeq = seq;
    }

    // Incrementar contador de paquetes recibidos desde que se detectó la brecha
    for (auto& h : pendingHoles) {
        h.packetsReceivedSinceGap++;
    }

    // Evaluar Grace Inbox para disparar NACK RFC 4585 tras 3-5ms o K>=2 paquetes
    evaluateNackGraceInbox(clientSsrc, nackSender);
}

void RtpSequenceReorderBuffer::evaluateNackGraceInbox(uint32_t clientSsrc, const NackSender& nackSender) {
    if (pendingHoles.empty() || !nackSender) return;

    auto now = std::chrono::steady_clock::now();
    uint16_t baseLostSeq = 0;
    uint16_t blpMask = 0;
    bool shouldSendNack = false;

    // Ordenar huecos por número de secuencia
    std::sort(pendingHoles.begin(), pendingHoles.end(),
              [](const MissingHole& a, const MissingHole& b) {
                  return seqDiff(a.seq, b.seq) < 0;
              });

    for (size_t i = 0; i < pendingHoles.size(); ++i) {
        auto& hole = pendingHoles[i];
        if (hole.nackSent) continue;

        auto elapsedUs = std::chrono::duration_cast<std::chrono::microseconds>(now - hole.detectedAt).count();
        // Ventana de gracia: 3500us (3.5ms) o al menos 2 paquetes recibidos después
        if (elapsedUs >= 3500 || hole.packetsReceivedSinceGap >= 2) {
            if (!shouldSendNack) {
                baseLostSeq = hole.seq;
                shouldSendNack = true;
                hole.nackSent = true;
            } else {
                int16_t offset = seqDiff(hole.seq, baseLostSeq);
                if (offset >= 1 && offset <= 16) {
                    blpMask |= (1 << (offset - 1));
                    hole.nackSent = true;
                }
            }
        }
    }

    if (shouldSendNack) {
        // Enviar NACK RFC 4585 Generic NACK (PT=205, FMT=1)
        RtcpNackPacket nack{};
        nack.header = 0x81;
        nack.packetType = 205;
        nack.length = htons(3); // 4 palabras de 32 bits menos 1
        nack.senderSsrc = htonl(clientSsrc);
        nack.mediaSsrc = htonl(lastServerSsrc);
        nack.pid = htons(baseLostSeq);
        nack.blp = htons(blpMask);

        nackSender(reinterpret_cast<const uint8_t*>(&nack), sizeof(RtcpNackPacket));
        lastNackSentTime = now;
        GM_LOGD("NACK RFC 4585 despachado: PID=%u, BLP=0x%04X", baseLostSeq, blpMask);
    }
}

void RtpSequenceReorderBuffer::flushPendingPackets(const PacketConsumer& consumer) {
    for (size_t i = 0; i < RING_CAPACITY; ++i) {
        if (ring[i].occupied) {
            consumer(ring[i].data, ring[i].size, ring[i].timestamp, ring[i].marker, true);
            ring[i].occupied = false;
            ring[i].size = 0;
        }
    }
    pendingHoles.clear();
}

// -----------------------------------------------------------------------------
// Máquina de Estados de Integridad IDR
// -----------------------------------------------------------------------------
IdrRecoveryStateMachine::IdrRecoveryStateMachine() {
    lastPliTime = std::chrono::steady_clock::now() - std::chrono::milliseconds(1000);
}

void IdrRecoveryStateMachine::reset() {
    currentState = IdrRecoveryState::WAITING_FIRST_SYNC;
    lastPliTime = std::chrono::steady_clock::now() - std::chrono::milliseconds(1000);
}

void IdrRecoveryStateMachine::onDecoderError() {
    currentState = IdrRecoveryState::BROKEN_REFERENCE;
}

bool IdrRecoveryStateMachine::processAccessUnit(
    bool isIdr,
    bool hasDiscontinuity,
    bool hasSps,
    bool hasPps,
    uint32_t clientSsrc,
    uint32_t serverVideoSsrc,
    std::vector<uint8_t>& outPliPacket
) {
    outPliPacket.clear();
    auto now = std::chrono::steady_clock::now();

    switch (currentState) {
        case IdrRecoveryState::WAITING_FIRST_SYNC: {
            if (isIdr && !hasDiscontinuity) {
                currentState = IdrRecoveryState::HEALTHY;
                GM_LOGI("IDR State: Sincronización inicial lograda -> HEALTHY");
                return true;
            }
            // Disparar PLI si han pasado más de PLI_COOLDOWN_MS para apresurar primer IDR
            auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(now - lastPliTime).count();
            if (elapsedMs >= PLI_COOLDOWN_MS) {
                lastPliTime = now;
                outPliPacket.resize(12);
                outPliPacket[0] = 0x81; // V=2, P=0, FMT=1
                outPliPacket[1] = 206;  // PSFB (Payload-Specific Feedback)
                *reinterpret_cast<uint16_t*>(&outPliPacket[2]) = htons(2);
                *reinterpret_cast<uint32_t*>(&outPliPacket[4]) = htonl(clientSsrc);
                *reinterpret_cast<uint32_t*>(&outPliPacket[8]) = htonl(serverVideoSsrc);
            }
            return false;
        }

        case IdrRecoveryState::HEALTHY: {
            if (hasDiscontinuity) {
                currentState = IdrRecoveryState::BROKEN_REFERENCE;
                GM_LOGW("IDR State: Pérdida en AU detectada -> BROKEN_REFERENCE. Congelando fotograma y pidiendo PLI");
                lastPliTime = now;
                outPliPacket.resize(12);
                outPliPacket[0] = 0x81;
                outPliPacket[1] = 206;
                *reinterpret_cast<uint16_t*>(&outPliPacket[2]) = htons(2);
                *reinterpret_cast<uint32_t*>(&outPliPacket[4]) = htonl(clientSsrc);
                *reinterpret_cast<uint32_t*>(&outPliPacket[8]) = htonl(serverVideoSsrc);
                return false; // Descartar frame corrupto
            }
            return true;
        }

        case IdrRecoveryState::BROKEN_REFERENCE: {
            if (isIdr && !hasDiscontinuity) {
                currentState = IdrRecoveryState::HEALTHY;
                GM_LOGI("IDR State: IDR limpio recibido -> Recuperado a HEALTHY");
                return true;
            }
            // Mientras esté roto, descartar P-frames para no mostrar macrobloques verdes / corruptos
            auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(now - lastPliTime).count();
            if (elapsedMs >= PLI_COOLDOWN_MS) {
                lastPliTime = now;
                outPliPacket.resize(12);
                outPliPacket[0] = 0x81;
                outPliPacket[1] = 206;
                *reinterpret_cast<uint16_t*>(&outPliPacket[2]) = htons(2);
                *reinterpret_cast<uint32_t*>(&outPliPacket[4]) = htonl(clientSsrc);
                *reinterpret_cast<uint32_t*>(&outPliPacket[8]) = htonl(serverVideoSsrc);
            }
            return false;
        }
    }
    return false;
}

// -----------------------------------------------------------------------------
// Ensamblador Manual de Access Units H.264
// -----------------------------------------------------------------------------
CompleteH264AccessUnitAssembler::CompleteH264AccessUnitAssembler() {
    currentAuBuffer.reserve(512 * 1024);
    fuaAccumulator.reserve(512 * 1024);
    cachedSps.reserve(256);
    cachedPps.reserve(256);
}

void CompleteH264AccessUnitAssembler::reset() {
    currentAuBuffer.clear();
    fuaAccumulator.clear();
    cachedSps.clear();
    cachedPps.clear();
    activeTimestamp = 0;
    activeAuIsIdr = false;
    activeAuDiscontinuous = false;
    hasSeenSpsThisAu = false;
    hasSeenPpsThisAu = false;
    idrStateMachine.reset();
}

void CompleteH264AccessUnitAssembler::appendStartCode() {
    currentAuBuffer.insert(currentAuBuffer.end(), ANNEXB_START_CODE, ANNEXB_START_CODE + 4);
}

void CompleteH264AccessUnitAssembler::appendData(const uint8_t* data, size_t len) {
    currentAuBuffer.insert(currentAuBuffer.end(), data, data + len);
}

void CompleteH264AccessUnitAssembler::handleNalUnit(const uint8_t* nal, size_t len) {
    if (!nal || len == 0) return;
    uint8_t nalType = nal[0] & 0x1F;

    if (nalType == 7) { // SPS
        cachedSps.assign(nal, nal + len);
        hasSeenSpsThisAu = true;
        H264SpsParser::parseSps(nal, len, currentSpsInfo);
    } else if (nalType == 8) { // PPS
        cachedPps.assign(nal, nal + len);
        hasSeenPpsThisAu = true;
    } else if (nalType == 5) { // IDR
        activeAuIsIdr = true;
    }

    appendStartCode();
    appendData(nal, len);
}

void CompleteH264AccessUnitAssembler::feedRtpPayload(
    const uint8_t* payload,
    size_t len,
    uint32_t rtpTimestamp,
    bool marker,
    bool discontinuous,
    uint32_t clientSsrc,
    uint32_t serverSsrc,
    const OnCompleteAuCallback& onAu,
    const PliCallback& onPli
) {
    if (!payload || len < 1) return;

    // Detectar cambio de fotograma por cambio de timestamp de 90kHz
    if (rtpTimestamp != activeTimestamp && !currentAuBuffer.empty()) {
        std::vector<uint8_t> pliPacket;
        bool shouldSubmit = idrStateMachine.processAccessUnit(
            activeAuIsIdr, activeAuDiscontinuous, hasSeenSpsThisAu, hasSeenPpsThisAu,
            clientSsrc, serverSsrc, pliPacket
        );

        if (!pliPacket.empty() && onPli) {
            onPli(pliPacket.data(), pliPacket.size());
        }

        if (shouldSubmit && onAu) {
            uint64_t ptsUs = (static_cast<uint64_t>(activeTimestamp) * 1000000ULL) / 90000ULL;
            onAu(currentAuBuffer.data(), currentAuBuffer.size(), ptsUs, activeAuIsIdr, currentSpsInfo);
        }

        currentAuBuffer.clear();
        activeAuIsIdr = false;
        activeAuDiscontinuous = false;
        hasSeenSpsThisAu = false;
        hasSeenPpsThisAu = false;
    }

    activeTimestamp = rtpTimestamp;
    if (discontinuous) {
        activeAuDiscontinuous = true;
    }

    uint8_t nalHeader = payload[0];
    uint8_t nalType = nalHeader & 0x1F;

    if (nalType >= 1 && nalType <= 23) {
        // NALU Simple
        handleNalUnit(payload, len);
    } else if (nalType == 24) {
        // STAP-A: Agregación de NALUs
        size_t offset = 1;
        while (offset + 2 <= len) {
            uint16_t naluSize = (static_cast<uint16_t>(payload[offset]) << 8) | payload[offset + 1];
            offset += 2;
            if (offset + naluSize > len) break;
            handleNalUnit(payload + offset, naluSize);
            offset += naluSize;
        }
    } else if (nalType == 28) {
        // FU-A: Fragmentación
        if (len < 2) return;
        uint8_t fuHeader = payload[1];
        bool s = (fuHeader & 0x80) != 0;
        bool e = (fuHeader & 0x40) != 0;
        uint8_t origType = fuHeader & 0x1F;

        if (s) {
            fuaAccumulator.clear();
            uint8_t reconstructedHeader = (nalHeader & 0xE0) | origType;
            fuaAccumulator.push_back(reconstructedHeader);
            if (origType == 5) {
                activeAuIsIdr = true;
            }
        }

        if (len > 2) {
            fuaAccumulator.insert(fuaAccumulator.end(), payload + 2, payload + len);
        }

        if (e && !fuaAccumulator.empty()) {
            handleNalUnit(fuaAccumulator.data(), fuaAccumulator.size());
            fuaAccumulator.clear();
        }
    }

    if (marker && !currentAuBuffer.empty()) {
        std::vector<uint8_t> pliPacket;
        bool shouldSubmit = idrStateMachine.processAccessUnit(
            activeAuIsIdr, activeAuDiscontinuous, hasSeenSpsThisAu, hasSeenPpsThisAu,
            clientSsrc, serverSsrc, pliPacket
        );

        if (!pliPacket.empty() && onPli) {
            onPli(pliPacket.data(), pliPacket.size());
        }

        if (shouldSubmit && onAu) {
            uint64_t ptsUs = (static_cast<uint64_t>(activeTimestamp) * 1000000ULL) / 90000ULL;
            onAu(currentAuBuffer.data(), currentAuBuffer.size(), ptsUs, activeAuIsIdr, currentSpsInfo);
        }

        currentAuBuffer.clear();
        activeAuIsIdr = false;
        activeAuDiscontinuous = false;
        hasSeenSpsThisAu = false;
        hasSeenPpsThisAu = false;
    }
}

// -----------------------------------------------------------------------------
// 7. Implementación de CompleteHevcAccessUnitAssembler (RFC 7798)
// -----------------------------------------------------------------------------
CompleteHevcAccessUnitAssembler::CompleteHevcAccessUnitAssembler() {
    currentAuBuffer.reserve(512 * 1024);
    fuAccumulator.reserve(256 * 1024);
}

void CompleteHevcAccessUnitAssembler::reset() {
    currentAuBuffer.clear();
    fuAccumulator.clear();
    cachedVps.clear();
    cachedSps.clear();
    cachedPps.clear();
    activeTimestamp = 0;
    activeAuIsKeyframe = false;
    activeAuDiscontinuous = false;
    hasAppendedVpsSpsPps = false;
}

void CompleteHevcAccessUnitAssembler::appendStartCode() {
    currentAuBuffer.insert(currentAuBuffer.end(), ANNEXB_START_CODE, ANNEXB_START_CODE + 4);
}

void CompleteHevcAccessUnitAssembler::appendData(const uint8_t* data, size_t len) {
    if (data && len > 0) {
        currentAuBuffer.insert(currentAuBuffer.end(), data, data + len);
    }
}

void CompleteHevcAccessUnitAssembler::handleNalu(const uint8_t* nalu, size_t len) {
    if (!nalu || len < 2) return;
    uint8_t nalType = (nalu[0] >> 1) & 0x3F;

    if (nalType == 32) { // VPS
        cachedVps.assign(nalu, nalu + len);
    } else if (nalType == 33) { // SPS
        cachedSps.assign(nalu, nalu + len);
    } else if (nalType == 34) { // PPS
        cachedPps.assign(nalu, nalu + len);
    } else if (nalType >= 16 && nalType <= 21) { // IRAP / Keyframe (BLA/IDR/CRA)
        activeAuIsKeyframe = true;
        // Inyectar VPS/SPS/PPS antes del fotograma clave si aún no están incluidos en este AU
        if (!hasAppendedVpsSpsPps && !cachedVps.empty() && !cachedSps.empty() && !cachedPps.empty()) {
            appendStartCode();
            appendData(cachedVps.data(), cachedVps.size());
            appendStartCode();
            appendData(cachedSps.data(), cachedSps.size());
            appendStartCode();
            appendData(cachedPps.data(), cachedPps.size());
            hasAppendedVpsSpsPps = true;
        }
    }

    appendStartCode();
    appendData(nalu, len);
}

void CompleteHevcAccessUnitAssembler::feedRtpPayload(
    const uint8_t* payload,
    size_t len,
    uint32_t rtpTimestamp,
    bool marker,
    bool discontinuous,
    uint32_t clientSsrc,
    uint32_t serverSsrc,
    const OnCompleteHevcAuCallback& onAu,
    const PliCallback& onPli
) {
    if (!payload || len < 2) return;

    // Detectar transición de fotograma mediante cambio de RTP Timestamp
    if (rtpTimestamp != activeTimestamp && !currentAuBuffer.empty()) {
        if (onAu) {
            uint64_t ptsUs = (static_cast<uint64_t>(activeTimestamp) * 1000000ULL) / 90000ULL;
            onAu(currentAuBuffer.data(), currentAuBuffer.size(), ptsUs, activeAuIsKeyframe);
        }
        currentAuBuffer.clear();
        activeAuIsKeyframe = false;
        activeAuDiscontinuous = false;
        hasAppendedVpsSpsPps = false;
    }

    activeTimestamp = rtpTimestamp;
    if (discontinuous) {
        activeAuDiscontinuous = true;
    }

    uint8_t nalHeader1 = payload[0];
    uint8_t nalType = (nalHeader1 >> 1) & 0x3F;

    if (nalType <= 47) {
        // NALU Simple
        handleNalu(payload, len);
    } else if (nalType == 48) {
        // AP (Aggregation Packet)
        size_t offset = 2;
        while (offset + 2 <= len) {
            uint16_t naluSize = (static_cast<uint16_t>(payload[offset]) << 8) | payload[offset + 1];
            offset += 2;
            if (offset + naluSize > len) break;
            handleNalu(payload + offset, naluSize);
            offset += naluSize;
        }
    } else if (nalType == 49) {
        // FU (Fragmentation Unit)
        if (len < 3) return;
        uint8_t fuHeader = payload[2];
        bool isStart = (fuHeader & 0x80) != 0;
        bool isEnd = (fuHeader & 0x40) != 0;
        uint8_t actualNalType = fuHeader & 0x3F;

        if (isStart) {
            fuAccumulator.clear();
            // Reconstruir cabecera NAL HEVC de 2 bytes
            uint8_t reconstructedHeader1 = (actualNalType << 1) & 0x7E | (payload[0] & 0x01);
            uint8_t reconstructedHeader2 = payload[1];
            fuAccumulator.push_back(reconstructedHeader1);
            fuAccumulator.push_back(reconstructedHeader2);

            if (actualNalType >= 16 && actualNalType <= 21) {
                activeAuIsKeyframe = true;
            }
        }

        if (len > 3) {
            fuAccumulator.insert(fuAccumulator.end(), payload + 3, payload + len);
        }

        if (isEnd && !fuAccumulator.empty()) {
            handleNalu(fuAccumulator.data(), fuAccumulator.size());
            fuAccumulator.clear();
        }
    }

    if (marker && !currentAuBuffer.empty()) {
        if (onAu) {
            uint64_t ptsUs = (static_cast<uint64_t>(activeTimestamp) * 1000000ULL) / 90000ULL;
            onAu(currentAuBuffer.data(), currentAuBuffer.size(), ptsUs, activeAuIsKeyframe);
        }
        currentAuBuffer.clear();
        activeAuIsKeyframe = false;
        activeAuDiscontinuous = false;
        hasAppendedVpsSpsPps = false;
    }
}

// -----------------------------------------------------------------------------
// 8. Implementación de CompleteAv1AccessUnitAssembler (AOMedia RTP & LEB128)
// -----------------------------------------------------------------------------
CompleteAv1AccessUnitAssembler::CompleteAv1AccessUnitAssembler() {
    currentAuBuffer.reserve(512 * 1024);
}

void CompleteAv1AccessUnitAssembler::reset() {
    currentAuBuffer.clear();
    cachedSeqHeader.clear();
    activeTimestamp = 0;
    activeAuIsKeyframe = false;
    activeAuDiscontinuous = false;
    hasTemporalDelimiter = false;
}

void CompleteAv1AccessUnitAssembler::injectTemporalDelimiter() {
    if (!hasTemporalDelimiter) {
        currentAuBuffer.insert(currentAuBuffer.end(), OBU_TEMPORAL_DELIMITER_BYTES, OBU_TEMPORAL_DELIMITER_BYTES + 2);
        hasTemporalDelimiter = true;
    }
}

void CompleteAv1AccessUnitAssembler::appendData(const uint8_t* data, size_t len) {
    if (data && len > 0) {
        currentAuBuffer.insert(currentAuBuffer.end(), data, data + len);
    }
}

void CompleteAv1AccessUnitAssembler::feedRtpPayload(
    const uint8_t* payload,
    size_t len,
    uint32_t rtpTimestamp,
    bool marker,
    bool discontinuous,
    uint32_t clientSsrc,
    uint32_t serverSsrc,
    const OnCompleteAv1AuCallback& onAu,
    const PliCallback& onPli
) {
    if (!payload || len < 1) return;

    if (rtpTimestamp != activeTimestamp && !currentAuBuffer.empty()) {
        if (onAu) {
            uint64_t ptsUs = (static_cast<uint64_t>(activeTimestamp) * 1000000ULL) / 90000ULL;
            onAu(currentAuBuffer.data(), currentAuBuffer.size(), ptsUs, activeAuIsKeyframe);
        }
        currentAuBuffer.clear();
        activeAuIsKeyframe = false;
        activeAuDiscontinuous = false;
        hasTemporalDelimiter = false;
    }

    activeTimestamp = rtpTimestamp;
    if (discontinuous) {
        activeAuDiscontinuous = true;
    }

    injectTemporalDelimiter();

    // AOMedia AV1 RTP Aggregation Header (Byte 0)
    // Bit 0: Z (continuación de fragmento anterior)
    // Bit 1: Y (último OBU continúa en siguiente paquete)
    // Bits 2-3: W (número de elementos OBU, 0..3)
    // Bit 4: N (inicio de nueva secuencia de video codificado)
    uint8_t aggHeader = payload[0];
    uint8_t z = (aggHeader >> 7) & 0x01;
    uint8_t y = (aggHeader >> 6) & 0x01;
    uint8_t w = (aggHeader >> 4) & 0x03;
    uint8_t n = (aggHeader >> 3) & 0x01;

    if (n == 1) {
        activeAuIsKeyframe = true;
    }

    size_t offset = 1;
    if (z == 1) {
        // Continuación directa del OBU anterior
        if (w == 0) {
            appendData(payload + offset, len - offset);
            offset = len;
        } else {
            size_t bytesRead = 0;
            uint32_t elemLen = Leb128Codec::readLeb128(payload + offset, len - offset, &bytesRead);
            offset += bytesRead;
            if (offset + elemLen <= len) {
                appendData(payload + offset, elemLen);
                offset += elemLen;
            }
        }
    }

    // Parsear los elementos OBU subsiguientes en el paquete
    while (offset < len) {
        size_t bytesRead = 0;
        uint32_t obuSize = 0;
        if (offset + 1 < len) {
            obuSize = Leb128Codec::readLeb128(payload + offset, len - offset, &bytesRead);
            offset += bytesRead;
        } else {
            obuSize = static_cast<uint32_t>(len - offset);
        }

        if (offset + obuSize > len) {
            obuSize = static_cast<uint32_t>(len - offset);
        }

        if (obuSize > 0) {
            const uint8_t* obuData = payload + offset;
            uint8_t obuHeader = obuData[0];
            uint8_t obuType = (obuHeader >> 3) & 0x0F;

            if (obuType == 1) { // OBU_SEQUENCE_HEADER
                activeAuIsKeyframe = true;
                cachedSeqHeader.assign(obuData, obuData + obuSize);
            } else if (obuType == 3 || obuType == 6) { // OBU_FRAME_HEADER u OBU_FRAME
                // Si contiene cabecera de fotograma clave
                if (obuSize > 1) {
                    bool showExistingFrame = (obuData[1] & 0x80) != 0;
                    if (!showExistingFrame) {
                        uint8_t frameType = (obuData[1] >> 5) & 0x03;
                        if (frameType == 0) { // KEY_FRAME
                            activeAuIsKeyframe = true;
                        }
                    }
                }
            }

            appendData(obuData, obuSize);
            offset += obuSize;
        } else {
            break;
        }
    }

    if (marker && !currentAuBuffer.empty()) {
        if (onAu) {
            uint64_t ptsUs = (static_cast<uint64_t>(activeTimestamp) * 1000000ULL) / 90000ULL;
            onAu(currentAuBuffer.data(), currentAuBuffer.size(), ptsUs, activeAuIsKeyframe);
        }
        currentAuBuffer.clear();
        activeAuIsKeyframe = false;
        activeAuDiscontinuous = false;
        hasTemporalDelimiter = false;
    }
}

} // namespace GodMode::Stream

// -----------------------------------------------------------------------------
// 9. Implementación de Funciones Nativas C de Ingesta y SDP NextGen
// -----------------------------------------------------------------------------
static thread_local GodMode::Stream::CompleteHevcAccessUnitAssembler gHevcCAssembler;
static thread_local GodMode::Stream::CompleteAv1AccessUnitAssembler gAv1CAssembler;

extern "C" {

bool x4_ingress_push_rtp_hevc(const uint8_t* payload, size_t len, uint32_t rtpTimestamp, bool marker,
                              uint8_t* outAu, size_t* outAuLen, bool* outIsKeyframe) {
    if (!payload || len == 0 || !outAu || !outAuLen) return false;
    *outAuLen = 0;
    if (outIsKeyframe) *outIsKeyframe = false;

    bool producedAu = false;
    gHevcCAssembler.feedRtpPayload(
        payload, len, rtpTimestamp, marker, false, 0x12345678, 0,
        [&](const uint8_t* auData, size_t auSize, uint64_t ptsUs, bool isKeyframe) {
            std::memcpy(outAu, auData, auSize);
            *outAuLen = auSize;
            if (outIsKeyframe) *outIsKeyframe = isKeyframe;
            producedAu = true;
        },
        nullptr
    );
    return producedAu;
}

bool x4_ingress_push_rtp_av1(const uint8_t* payload, size_t len, uint32_t rtpTimestamp, bool marker,
                             uint8_t* outAu, size_t* outAuLen, bool* outIsKeyframe) {
    if (!payload || len == 0 || !outAu || !outAuLen) return false;
    *outAuLen = 0;
    if (outIsKeyframe) *outIsKeyframe = false;

    bool producedAu = false;
    gAv1CAssembler.feedRtpPayload(
        payload, len, rtpTimestamp, marker, false, 0x12345678, 0,
        [&](const uint8_t* auData, size_t auSize, uint64_t ptsUs, bool isKeyframe) {
            std::memcpy(outAu, auData, auSize);
            *outAuLen = auSize;
            if (outIsKeyframe) *outIsKeyframe = isKeyframe;
            producedAu = true;
        },
        nullptr
    );
    return producedAu;
}

size_t x4_sdp_build_video_offer_nextgen(char* outBuf, size_t maxLen, uint32_t maxBitrateKbps) {
    if (!outBuf || maxLen == 0) return 0;
    const char* sdpTemplate =
        "m=video 9 UDP/TLS/RTP/SAVPF 96 97 98 99\r\n"
        "c=IN IP4 0.0.0.0\r\n"
        "b=AS:%u\r\n"
        "b=TIAS:%u\r\n"
        "a=dscp:34\r\n"
        "a=rtcp-fb:* nack\r\n"
        "a=rtcp-fb:* nack pli\r\n"
        "a=rtcp-fb:* goog-remb\r\n"
        "a=rtcp-fb:* transport-cc\r\n"
        "a=rtpmap:96 AV1/90000\r\n"
        "a=fmtp:96 profile=0;level-idx=13;tier=0;x-google-max-bitrate=%u;x-google-min-bitrate=%u;x-google-start-bitrate=%u\r\n"
        "a=rtcp-fb:96 nack\r\n"
        "a=rtcp-fb:96 nack pli\r\n"
        "a=rtcp-fb:96 goog-remb\r\n"
        "a=rtcp-fb:96 transport-cc\r\n"
        "a=rtpmap:97 rtx/90000\r\n"
        "a=fmtp:97 apt=96\r\n"
        "a=rtpmap:98 H265/90000\r\n"
        "a=fmtp:98 profile-id=2;tier-flag=0;level-id=153;tx-mode=SRST;x-google-max-bitrate=%u;x-google-min-bitrate=%u;x-google-start-bitrate=%u\r\n"
        "a=rtcp-fb:98 nack\r\n"
        "a=rtcp-fb:98 nack pli\r\n"
        "a=rtcp-fb:98 goog-remb\r\n"
        "a=rtcp-fb:98 transport-cc\r\n"
        "a=rtpmap:99 rtx/90000\r\n"
        "a=fmtp:99 apt=98\r\n";

    uint32_t tias = maxBitrateKbps * 1000;
    uint32_t minBr = maxBitrateKbps / 2;
    uint32_t startBr = (maxBitrateKbps * 3) / 4;

    int written = snprintf(outBuf, maxLen, sdpTemplate,
                           maxBitrateKbps, tias,
                           maxBitrateKbps, minBr, startBr,
                           maxBitrateKbps, minBr, startBr);
    return (written > 0 && static_cast<size_t>(written) < maxLen) ? static_cast<size_t>(written) : 0;
}

}

