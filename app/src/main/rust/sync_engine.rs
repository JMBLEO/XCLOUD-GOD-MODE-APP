pub struct AdaptiveSyncEngine {
    phase: f64,
    current_speed_ratio: f64,
    integral_error: f64,
    kp: f64,
    ki: f64,
    buffer_size: usize,
}

impl AdaptiveSyncEngine {
    pub fn new(capacity_frames: usize) -> Self {
        Self {
            phase: 0.0,
            current_speed_ratio: 1.0,
            integral_error: 0.0,
            kp: 0.000005,
            ki: 0.00000001,
            buffer_size: capacity_frames,
        }
    }

    pub fn calibrate_sync(&mut self, audio_timestamp_us: i64, video_timestamp_us: i64) {
        // 1. Calculate phase error (drift)
        let drift_us = video_timestamp_us - audio_timestamp_us;
        
        // 2. PI Controller for Resampler Speed Ratio
        let error = drift_us as f64;
        self.integral_error += error;
        
        // Anti-windup clamp
        self.integral_error = self.integral_error.clamp(-5_000_000.0, 5_000_000.0);
        
        let new_ratio = 1.0 + (self.kp * error) + (self.ki * self.integral_error);
        
        // Limit adjustment to +/- 1% for audio transparency
        self.current_speed_ratio = new_ratio.clamp(0.99, 1.01);
    }

    pub fn process_frames(&mut self, num_frames: i32) -> f64 {
        let delta = self.current_speed_ratio * num_frames as f64;
        self.phase = (self.phase + delta) % self.buffer_size as f64;
        self.current_speed_ratio
    }

    pub fn get_speed_ratio(&self) -> f64 {
        self.current_speed_ratio
    }
}
