use std::time::Instant;
use std::cmp;

#[derive(Debug, PartialEq, Clone, Copy)]
pub enum BbrMode {
    Startup,
    Drain,
    ProbeBw,
    ProbeRtt,
}

pub struct BbrPacingController {
    mode: BbrMode,
    max_bandwidth_bps: f64,
    min_rtt_us: u64,
    last_ack_time: Instant,
    
    // Kalman Filter for Jitter-Free RTT
    kalman_estimate: f64,
    kalman_error_cov: f64,
    q: f64, // Process noise
    r: f64, // Measurement noise
    
    // BBR Cycle state
    cycle_index: usize,
    pacing_gains: [f64; 8],
    filled_pipe: bool,
    startup_count: u32,
}

impl BbrPacingController {
    pub fn new() -> Self {
        Self {
            mode: BbrMode::Startup,
            max_bandwidth_bps: 15_000_000.0,
            min_rtt_us: u64::MAX,
            last_ack_time: Instant::now(),
            kalman_estimate: 20.0,
            kalman_error_cov: 1.0,
            q: 0.1,
            r: 2.0,
            cycle_index: 0,
            pacing_gains: [1.25, 0.75, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0],
            filled_pipe: false,
            startup_count: 0,
        }
    }

    pub fn update_telemetry(&mut self, bytes_acked: usize, rtt_us: u64) {
        // 1. Kalman Filtering for RTT
        let measurement = rtt_us as f64 / 1000.0;
        let pred_est = self.kalman_estimate;
        let pred_cov = self.kalman_error_cov + self.q;
        let gain = pred_cov / (pred_cov + self.r);
        
        self.kalman_estimate = pred_est + gain * (measurement - pred_est);
        self.kalman_error_cov = (1.0 - gain) * pred_cov;

        if rtt_us < self.min_rtt_us || self.min_rtt_us == u64::MAX {
            self.min_rtt_us = rtt_us;
        }

        // 2. Bandwidth Tracking
        let now = Instant::now();
        let elapsed = now.duration_since(self.last_ack_time).as_secs_f64();
        self.last_ack_time = now;

        if elapsed > 0.001 {
            let instant_bw = (bytes_acked as f64 * 8.0) / elapsed;
            if instant_bw > self.max_bandwidth_bps {
                self.max_bandwidth_bps = instant_bw;
            } else {
                self.max_bandwidth_bps = self.max_bandwidth_bps * 0.98 + instant_bw * 0.02;
            }
        }

        // 3. State Machine Transitions
        self.update_state_machine();
    }

    pub fn get_optimal_pacing_rate_bps(&self, ultra_1440p: bool) -> u64 {
        let gain = match self.mode {
            BbrMode::Startup => 2.89,
            BbrMode::Drain => 0.35,
            BbrMode::ProbeBw => self.pacing_gains[self.cycle_index],
            BbrMode::ProbeRtt => 1.0,
        };

        let base = self.max_bandwidth_bps * gain;
        let ceiling = if ultra_1440p { 55_000_000.0 } else { 35_000_000.0 };
        let floor = 6_000_000.0;

        base.clamp(floor, ceiling) as u64
    }

    fn update_state_machine(&mut self) {
        match self.mode {
            BbrMode::Startup => {
                if self.filled_pipe {
                    self.mode = BbrMode::Drain;
                }
                self.startup_count += 1;
                if self.startup_count > 50 {
                    self.filled_pipe = true;
                }
            },
            BbrMode::Drain => {
                if self.kalman_estimate < (self.min_rtt_us as f64 / 1000.0 * 1.1) {
                    self.mode = BbrMode::ProbeBw;
                }
            },
            BbrMode::ProbeBw => {
                self.cycle_index = (self.cycle_index + 1) % 8;
            },
            BbrMode::ProbeRtt => {
                // Return to BW probing after measuring min RTT
                self.mode = BbrMode::ProbeBw;
            }
        }
    }
}
