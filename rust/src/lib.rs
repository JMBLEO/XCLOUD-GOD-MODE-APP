use std::net::{SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

const STUN_MAGIC_COOKIE: u32 = 0x2112A442;
const STUN_FINGERPRINT_XOR: u32 = 0x5354554E;
const RING_CAPACITY_FRAMES: usize = 64;
const MAX_FRAME_BYTES: usize = 512 * 1024; // 512 KB por Access Unit ensamblado

// ============================================================================
// 1. MÉTRICAS GLOBALES DE TRANSPORTE, DECODIFICADOR Y RENDER
// ============================================================================

#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct LatencyMetrics {
    pub network_ms: u32,
    pub decode_ms: u32,
    pub render_ms: u32,
    pub end_to_end_ms: u32,
}

#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct JitterMetrics {
    pub current_ms: u32,
    pub avg_ms: u32,
    pub peak_ms: u32,
}

#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct PacketMetrics {
    pub received: u64,
    pub lost: u64,
    pub reordered: u64,
}

#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct FrameMetrics {
    pub rendered: u64,
    pub dropped: u64,
    pub duplicated: u64,
}

#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct DecoderMetrics {
    pub queue_depth: usize,
    pub decode_time_ms: u32,
    pub stalls: u64,
}

#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct GpuMetrics {
    pub utilization: f32,
    pub vram_used_mb: u32,
    pub queue_wait_ms: u32,
}

#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct MemoryMetrics {
    pub ram_mb: u32,
    pub vram_mb: u32,
    pub buffer_count: usize,
}

#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct EngineMetrics {
    pub latency: LatencyMetrics,
    pub jitter: JitterMetrics,
    pub packets: PacketMetrics,
    pub frames: FrameMetrics,
    pub decoder: DecoderMetrics,
    pub gpu: GpuMetrics,
    pub memory: MemoryMetrics,
}

// ============================================================================
// 2. MÁQUINAS DE ESTADOS GLOBALES (FSM)
// ============================================================================

#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StreamingState {
    Idle,
    Authenticating,
    EstablishingSession,
    AllocatingResources,
    ConnectingTransport,
    Buffering,
    Streaming,
    Recovering,
    ReSyncing,
    Suspended,
    Terminating,
    Failed,
}

#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HealthState {
    Healthy,
    Degraded,
    Recovering,
    Critical,
    Failed,
}

#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RecoveryLevel {
    QueueFlush     = 1,
    DecoderReset   = 2,
    RendererReset  = 3,
    StreamResync   = 4,
    SessionResume  = 5,
    EngineRebuild  = 6,
    FullShutdown   = 7,
}

// ============================================================================
// 3. INTERFACES Y TRAITS RUST COMUNES A LOS 6 MOTORES
// ============================================================================

pub struct Packet {
    pub data: Vec<u8>,
    pub pts_us: u64,
    pub is_keyframe: bool,
}

pub struct VideoFrame {
    pub width: u32,
    pub height: u32,
    pub pts_us: u64,
    pub buffer_handle: usize,
}

pub type Result<T> = std::result::Result<T, Box<dyn std::error::Error + Send + Sync>>;

pub trait StreamEngine {
    fn initialize(&mut self) -> Result<()>;
    fn start_stream(&mut self) -> Result<()>;
    fn stop_stream(&mut self);
    fn pause_stream(&mut self);
    fn resume_stream(&mut self);
    fn recover(&mut self) -> Result<()>;
    fn metrics(&self) -> EngineMetrics;
}

pub trait DecoderBackend {
    fn configure(&mut self) -> Result<()>;
    fn submit_packet(&mut self, packet: Packet);
    fn dequeue_frame(&mut self) -> Option<VideoFrame>;
    fn flush(&mut self);
    fn reset(&mut self);
}

pub trait RendererBackend {
    fn create_surface(&mut self);
    fn render(&mut self, frame: VideoFrame);
    fn present(&mut self);
    fn reset(&mut self);
}

pub trait SessionManager {
    fn authenticate(&mut self);
    fn refresh_session(&mut self);
    fn keep_alive(&mut self);
    fn invalidate(&mut self);
}

pub trait RecoveryManager {
    fn recover_network(&mut self);
    fn recover_decoder(&mut self);
    fn recover_renderer(&mut self);
    fn recover_session(&mut self);
    fn full_recovery(&mut self);
}

// ============================================================================
// 4. SUPERVISOR GLOBAL DE ESTABILIDAD
// ============================================================================

pub struct StabilitySupervisor {
    pub latency: LatencyMetrics,
    pub jitter: JitterMetrics,
    pub packets: PacketMetrics,
    pub frames: FrameMetrics,
    pub decoder: DecoderMetrics,
    pub gpu: GpuMetrics,
    pub memory: MemoryMetrics,
    pub current_state: StreamingState,
    pub health: HealthState,
}

impl StabilitySupervisor {
    pub fn new() -> Self {
        Self {
            latency: LatencyMetrics::default(),
            jitter: JitterMetrics::default(),
            packets: PacketMetrics::default(),
            frames: FrameMetrics::default(),
            decoder: DecoderMetrics::default(),
            gpu: GpuMetrics::default(),
            memory: MemoryMetrics::default(),
            current_state: StreamingState::Idle,
            health: HealthState::Healthy,
        }
    }

    pub fn evaluate_health(&mut self) -> Option<RecoveryLevel> {
        let packet_loss_pct = if self.packets.received > 0 {
            (self.packets.lost as f32 / (self.packets.received + self.packets.lost) as f32) * 100.0
        } else {
            0.0
        };

        let frame_drop_pct = if self.frames.rendered > 0 {
            (self.frames.dropped as f32 / (self.frames.rendered + self.frames.dropped) as f32) * 100.0
        } else {
            0.0
        };

        // Reglas estrictas de estabilidad global
        if self.decoder.decode_time_ms > 3000 {
            self.health = HealthState::Critical;
            return Some(RecoveryLevel::DecoderReset);
        }

        if self.decoder.decode_time_ms > 1500 {
            self.health = HealthState::Recovering;
            return Some(RecoveryLevel::QueueFlush);
        }

        if self.gpu.queue_wait_ms > 100 {
            self.health = HealthState::Recovering;
            return Some(RecoveryLevel::RendererReset);
        }

        if packet_loss_pct > 3.0 || self.jitter.current_ms > 35 {
            self.health = HealthState::Recovering;
            return Some(RecoveryLevel::StreamResync);
        }

        if frame_drop_pct > 5.0 {
            self.health = HealthState::Degraded;
            return Some(RecoveryLevel::StreamResync);
        }

        self.health = HealthState::Healthy;
        None
    }
}

// ============================================================================
// 5. ANILLO SPSC ZERO-TRUST Y DESCRIPTORES DE ACCESO
// ============================================================================

#[repr(C)]
#[derive(Clone, Copy)]
pub struct ValidatedFrameDescriptor {
    pub pts_us: u64,
    pub data_offset: usize,
    pub data_len: usize,
    pub is_keyframe: bool,
    pub svc_temporal_id: u8,
    pub svc_quality_id: u8,
    pub packet_loss_detected: bool,
}

pub struct ZeroTrustFrameRing {
    arena: Box<[u8]>,
    descriptors: [ValidatedFrameDescriptor; RING_CAPACITY_FRAMES],
    write_idx: AtomicUsize,
    read_idx: AtomicUsize,
}

impl ZeroTrustFrameRing {
    pub fn new() -> Self {
        Self {
            arena: vec![0u8; RING_CAPACITY_FRAMES * MAX_FRAME_BYTES].into_boxed_slice(),
            descriptors: [ValidatedFrameDescriptor {
                pts_us: 0,
                data_offset: 0,
                data_len: 0,
                is_keyframe: false,
                svc_temporal_id: 0,
                svc_quality_id: 0,
                packet_loss_detected: false,
            }; RING_CAPACITY_FRAMES],
            write_idx: AtomicUsize::new(0),
            read_idx: AtomicUsize::new(0),
        }
    }

    pub fn push_access_unit(
        &mut self,
        payload: &[u8],
        pts_us: u64,
        is_keyframe: bool,
        temporal_id: u8,
        quality_id: u8,
        loss_flag: bool,
    ) -> bool {
        if payload.is_empty() || payload.len() > MAX_FRAME_BYTES {
            return false;
        }
        let w = self.write_idx.load(Ordering::Relaxed);
        let r = self.read_idx.load(Ordering::Acquire);
        if w.wrapping_sub(r) >= RING_CAPACITY_FRAMES {
            return false; // Anillo lleno: descarte seguro sin bloqueo
        }
        let slot = w % RING_CAPACITY_FRAMES;
        let offset = slot * MAX_FRAME_BYTES;
        self.arena[offset..offset + payload.len()].copy_from_slice(payload);
        self.descriptors[slot] = ValidatedFrameDescriptor {
            pts_us,
            data_offset: offset,
            data_len: payload.len(),
            is_keyframe,
            svc_temporal_id: temporal_id,
            svc_quality_id: quality_id,
            packet_loss_detected: loss_flag,
        };
        self.write_idx.store(w.wrapping_add(1), Ordering::Release);
        true
    }

    pub fn pop_access_unit(&self) -> Option<(*const u8, ValidatedFrameDescriptor)> {
        let r = self.read_idx.load(Ordering::Relaxed);
        let w = self.write_idx.load(Ordering::Acquire);
        if r == w {
            return None;
        }
        let slot = r % RING_CAPACITY_FRAMES;
        let desc = self.descriptors[slot];
        let ptr = unsafe { self.arena.as_ptr().add(desc.data_offset) };
        self.read_idx.store(r.wrapping_add(1), Ordering::Release);
        Some((ptr, desc))
    }
}

// ============================================================================
// 6. DEPACKETIZADOR H.264 SVC / HEVC Y GENERACIÓN DE NACK / PLI
// ============================================================================

pub struct RtpSvcDepacketizer {
    current_au: Vec<u8>,
    current_rtp_ts: u32,
    last_seq: Option<u16>,
    current_is_idr: bool,
    current_temporal_id: u8,
    current_quality_id: u8,
    max_allowed_temporal_layer: u8,
    loss_in_current_au: bool,
}

impl RtpSvcDepacketizer {
    pub fn new() -> Self {
        Self {
            current_au: Vec::with_capacity(MAX_FRAME_BYTES),
            current_rtp_ts: 0,
            last_seq: None,
            current_is_idr: false,
            current_temporal_id: 0,
            current_quality_id: 0,
            max_allowed_temporal_layer: 7,
            loss_in_current_au: false,
        }
    }

    pub fn set_svc_layer_ceiling(&mut self, max_temporal: u8) {
        self.max_allowed_temporal_layer = max_temporal;
    }

    /// Procesa paquete RTP; devuelve Some(range) si requiere emitir RTCP NACK
    pub fn process_rtp_video_packet(
        &mut self,
        rtp_packet: &[u8],
        ring: &mut ZeroTrustFrameRing,
    ) -> Option<(u16, u16)> {
        if rtp_packet.len() < 12 {
            return None;
        }

        let v = rtp_packet[0] >> 6;
        if v != 2 {
            return None;
        }
        let has_padding = (rtp_packet[0] & 0x20) != 0;
        let has_extension = (rtp_packet[0] & 0x10) != 0;
        let csrc_count = (rtp_packet[0] & 0x0F) as usize;
        let marker = (rtp_packet[1] & 0x80) != 0;
        let seq = u16::from_be_bytes([rtp_packet[2], rtp_packet[3]]);
        let rtp_ts = u32::from_be_bytes([rtp_packet[4], rtp_packet[5], rtp_packet[6], rtp_packet[7]]);

        let mut header_len = 12 + csrc_count * 4;
        if rtp_packet.len() < header_len {
            return None;
        }

        if has_extension {
            if rtp_packet.len() < header_len + 4 {
                return None;
            }
            let ext_words = u16::from_be_bytes([rtp_packet[header_len + 2], rtp_packet[header_len + 3]]) as usize;
            header_len += 4 + ext_words * 4;
        }

        let mut payload_end = rtp_packet.len();
        if has_padding && payload_end > header_len {
            let pad_len = rtp_packet[payload_end - 1] as usize;
            if pad_len <= payload_end - header_len {
                payload_end -= pad_len;
            }
        }

        if header_len >= payload_end {
            return None;
        }

        let mut nack_range = None;
        if let Some(prev_seq) = self.last_seq {
            let expected = prev_seq.wrapping_add(1);
            if seq != expected {
                self.loss_in_current_au = true;
                nack_range = Some((expected, seq.wrapping_sub(1)));
            }
        }
        self.last_seq = Some(seq);

        if rtp_ts != self.current_rtp_ts && !self.current_au.is_empty() {
            self.flush_access_unit(ring);
        }
        self.current_rtp_ts = rtp_ts;

        let payload = &rtp_packet[header_len..payload_end];
        let nal_header = payload[0];
        let nal_type = nal_header & 0x1F;

        match nal_type {
            1..=23 => {
                if nal_type == 14 || nal_type == 20 {
                    if payload.len() >= 4 {
                        let svc_b1 = payload[2];
                        let svc_b2 = payload[3];
                        self.current_quality_id = svc_b1 & 0x0F;
                        self.current_temporal_id = (svc_b2 >> 5) & 0x07;
                        if self.current_temporal_id > self.max_allowed_temporal_layer {
                            return nack_range;
                        }
                    }
                }
                if nal_type == 5 || nal_type == 7 {
                    self.current_is_idr = true;
                }
                self.current_au.extend_from_slice(&[0x00, 0x00, 0x00, 0x01]);
                self.current_au.extend_from_slice(payload);
            }
            24 => {
                let mut offset = 1;
                while offset + 2 <= payload.len() {
                    let nal_size = u16::from_be_bytes([payload[offset], payload[offset + 1]]) as usize;
                    offset += 2;
                    if offset + nal_size > payload.len() {
                        break;
                    }
                    let sub_nal = &payload[offset..offset + nal_size];
                    if !sub_nal.is_empty() {
                        let sub_type = sub_nal[0] & 0x1F;
                        if sub_type == 5 || sub_type == 7 {
                            self.current_is_idr = true;
                        }
                        self.current_au.extend_from_slice(&[0x00, 0x00, 0x00, 0x01]);
                        self.current_au.extend_from_slice(sub_nal);
                    }
                    offset += nal_size;
                }
            }
            28 => {
                if payload.len() >= 2 {
                    let fu_indicator = payload[0];
                    let fu_header = payload[1];
                    let start_bit = (fu_header & 0x80) != 0;
                    let orig_nal_type = fu_header & 0x1F;
                    let reconstructed_nal = (fu_indicator & 0xE0) | orig_nal_type;

                    if orig_nal_type == 5 {
                        self.current_is_idr = true;
                    }
                    if start_bit {
                        self.current_au.extend_from_slice(&[0x00, 0x00, 0x00, 0x01, reconstructed_nal]);
                    }
                    self.current_au.extend_from_slice(&payload[2..]);
                }
            }
            _ => {}
        }

        if marker {
            self.flush_access_unit(ring);
        }

        nack_range
    }

    fn flush_access_unit(&mut self, ring: &mut ZeroTrustFrameRing) {
        if self.current_au.is_empty() {
            return;
        }
        let pts_us = (self.current_rtp_ts as u64 * 1_000_000) / 90_000;
        ring.push_access_unit(
            &self.current_au,
            pts_us,
            self.current_is_idr,
            self.current_temporal_id,
            self.current_quality_id,
            self.loss_in_current_au,
        );
        self.current_au.clear();
        self.current_is_idr = false;
        self.current_temporal_id = 0;
        self.current_quality_id = 0;
        self.loss_in_current_au = false;
    }
}

// ============================================================================
// 7. FUNCIONES AUXILIARES STUN / RTCP / CRC32
// ============================================================================

pub fn build_stun_binding_request(username: &str, transaction_id: &[u8; 12]) -> Vec<u8> {
    let mut pkt = Vec::with_capacity(128);
    pkt.extend_from_slice(&0x0001u16.to_be_bytes());
    pkt.extend_from_slice(&0x0000u16.to_be_bytes());
    pkt.extend_from_slice(&STUN_MAGIC_COOKIE.to_be_bytes());
    pkt.extend_from_slice(transaction_id);

    let user_bytes = username.as_bytes();
    pkt.extend_from_slice(&0x0006u16.to_be_bytes());
    pkt.extend_from_slice(&(user_bytes.len() as u16).to_be_bytes());
    pkt.extend_from_slice(user_bytes);
    let pad = (4 - (user_bytes.len() % 4)) % 4;
    for _ in 0..pad {
        pkt.push(0);
    }

    pkt.extend_from_slice(&0x0025u16.to_be_bytes());
    pkt.extend_from_slice(&0x0000u16.to_be_bytes());
    pkt.extend_from_slice(&0x0024u16.to_be_bytes());
    pkt.extend_from_slice(&0x0004u16.to_be_bytes());
    pkt.extend_from_slice(&0x7E801EFFu32.to_be_bytes());

    let attr_len_with_fp = (pkt.len() - 20 + 8) as u16;
    pkt[2..4].copy_from_slice(&attr_len_with_fp.to_be_bytes());

    let crc = compute_crc32(&pkt) ^ STUN_FINGERPRINT_XOR;
    pkt.extend_from_slice(&0x8028u16.to_be_bytes());
    pkt.extend_from_slice(&0x0004u16.to_be_bytes());
    pkt.extend_from_slice(&crc.to_be_bytes());

    pkt
}

pub fn build_rtcp_nack_packet(sender_ssrc: u32, media_ssrc: u32, missing_pid: u16) -> [u8; 16] {
    let mut rtcp = [0u8; 16];
    rtcp[0] = 0x81;
    rtcp[1] = 205;
    rtcp[2..4].copy_from_slice(&3u16.to_be_bytes());
    rtcp[4..8].copy_from_slice(&sender_ssrc.to_be_bytes());
    rtcp[8..12].copy_from_slice(&media_ssrc.to_be_bytes());
    rtcp[12..14].copy_from_slice(&missing_pid.to_be_bytes());
    rtcp[14..16].copy_from_slice(&0u16.to_be_bytes());
    rtcp
}

pub fn build_rtcp_pli_packet(sender_ssrc: u32, media_ssrc: u32) -> [u8; 12] {
    let mut rtcp = [0u8; 12];
    rtcp[0] = 0x81;
    rtcp[1] = 206;
    rtcp[2..4].copy_from_slice(&2u16.to_be_bytes());
    rtcp[4..8].copy_from_slice(&sender_ssrc.to_be_bytes());
    rtcp[8..12].copy_from_slice(&media_ssrc.to_be_bytes());
    rtcp
}

fn compute_crc32(data: &[u8]) -> u32 {
    let mut crc: u32 = 0xFFFF_FFFF;
    for &byte in data {
        crc ^= byte as u32;
        for _ in 0..8 {
            if (crc & 1) != 0 {
                crc = (crc >> 1) ^ 0xEDB8_8320;
            } else {
                crc >>= 1;
            }
        }
    }
    !crc
}
