use std::collections::VecDeque;

use serde::{Deserialize, Serialize};

use crate::eco_habits::{EcoBreakdown, EcoConfig, EcoState, EcoTracker, RoadKm};
use crate::models::TripResult;
use crate::round_half_up_i32;

/// 점수 계산에 쓰는 기준값. 가속도 단위는 km/h/s.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ScoreConfig {
    pub harsh_accel_kmh_per_sec: f64,
    pub harsh_brake_kmh_per_sec: f64,
    pub harsh_brake_no_pedal_kmh_per_sec: f64,
    pub brake_pedal_grace_ms: i64,
    pub accel_penalty: f64,
    pub brake_penalty: f64,
    pub harsh_steer_deg_per_sec: f64,
    pub steer_min_speed_kmh: f64,
    pub steer_penalty: f64,
    pub window_ms: i64,
    pub min_span_ms: i64,
    pub max_gap_ms: i64,
    pub min_distance_for_rate_km: f64,
    pub eco_best_pke: f64,
    pub eco_worst_pke: f64,
    pub safety_weight: f64,
    pub eco: EcoConfig,
    pub speed_limit_stale_ms: i64,
}

impl Default for ScoreConfig {
    fn default() -> Self {
        Self {
            harsh_accel_kmh_per_sec: 11.0,
            harsh_brake_kmh_per_sec: 11.0,
            harsh_brake_no_pedal_kmh_per_sec: 14.0,
            brake_pedal_grace_ms: 1_500,
            accel_penalty: 4.0,
            brake_penalty: 5.0,
            harsh_steer_deg_per_sec: 60.0,
            steer_min_speed_kmh: 30.0,
            steer_penalty: 4.0,
            window_ms: 1_000,
            min_span_ms: 800,
            max_gap_ms: 5_000,
            min_distance_for_rate_km: 10.0,
            eco_best_pke: 0.20,
            eco_worst_pke: 0.60,
            safety_weight: 0.4,
            eco: EcoConfig::default(),
            speed_limit_stale_ms: 5_000,
        }
    }
}

pub struct ScoreCalculator;

impl ScoreCalculator {
    /// 안전 점수: 급가속·급제동·급조향 횟수를 10km당 감점으로 환산해 100점에서 뺀다. 0~100점.
    pub fn score(
        distance_km: f64,
        harsh_accel: i32,
        harsh_brake: i32,
        config: &ScoreConfig,
        harsh_steer: i32,
    ) -> i32 {
        let per_10km = 10.0 / distance_km.max(config.min_distance_for_rate_km);
        let penalty = (harsh_accel as f64 * config.accel_penalty
            + harsh_brake as f64 * config.brake_penalty
            + harsh_steer as f64 * config.steer_penalty)
            * per_10km;
        round_half_up_i32(100.0 - penalty).clamp(0, 100)
    }

    /// 가속 점수(PKE): 1m 를 가는 동안 속도를 올리는 데 쓴 에너지가 적을수록 높다.
    pub fn eco(pke: f64, config: &ScoreConfig) -> i32 {
        let ratio = ((pke - config.eco_best_pke) / (config.eco_worst_pke - config.eco_best_pke))
            .clamp(0.0, 1.0);
        round_half_up_i32(100.0 * (1.0 - ratio))
    }

    /// 종합 점수: 안전과 에코를 정해진 비율로 합친다.
    pub fn total(safety: i32, eco: i32, config: &ScoreConfig) -> i32 {
        round_half_up_i32(
            safety as f64 * config.safety_weight + eco as f64 * (1.0 - config.safety_weight),
        )
        .clamp(0, 100)
    }
}

/// 주행 중 감지된 급조작.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum HarshType {
    Accel,
    Brake,
    Steer,
}

impl HarshType {
    pub fn label(self) -> &'static str {
        match self {
            HarshType::Accel => "급출발",
            HarshType::Brake => "급정거",
            HarshType::Steer => "급조향",
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            HarshType::Accel => "ACCEL",
            HarshType::Brake => "BRAKE",
            HarshType::Steer => "STEER",
        }
    }

    pub fn from_name(s: &str) -> Option<Self> {
        match s {
            "ACCEL" => Some(HarshType::Accel),
            "BRAKE" => Some(HarshType::Brake),
            "STEER" => Some(HarshType::Steer),
            _ => None,
        }
    }
}

/// 급조작 한 번과 그로 인해 깎인 안전 점수.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HarshEvent {
    #[serde(rename = "type")]
    pub kind: HarshType,
    pub penalty: i32,
    pub safety_after: i32,
}

/// 감점 기록 한 줄.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LiveEvent {
    #[serde(rename = "type")]
    pub kind: HarshType,
    pub penalty: i32,
    pub score_after: i32,
    pub wall_time_ms: i64,
    pub km: f64,
}

/// 진행 중인 주행의 누적 값.
#[derive(Debug, Clone, Copy, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TripSnapshot {
    pub distance_km: f64,
    pub max_speed_kmh: f64,
    pub harsh_accel: i32,
    pub harsh_brake: i32,
    pub harsh_steer: i32,
    pub positive_kinetic_sum: f64,
    #[serde(default)]
    pub eco: EcoState,
}

#[derive(Debug, Clone, Copy)]
struct Sample {
    time_ms: i64,
    speed_kmh: f64,
}

#[derive(Debug, Clone, Copy)]
struct SteerSample {
    time_ms: i64,
    angle_deg: f64,
}

/// 주행 한 번을 기록한다.
#[derive(Debug, Clone)]
pub struct TripRecorder {
    config: ScoreConfig,
    window: VecDeque<Sample>,
    active: bool,
    distance_km: f64,
    max_speed_kmh: f64,
    harsh_accel: i32,
    harsh_brake: i32,
    in_accel_event: bool,
    in_brake_event: bool,
    steer_window: VecDeque<SteerSample>,
    brake_signal_known: bool,
    brake_pressed: bool,
    brake_released_at_ms: Option<i64>,
    harsh_steer: i32,
    in_steer_event: bool,
    last_speed_kmh: f64,
    positive_kinetic_sum: f64,
    eco_tracker: EcoTracker,
    speed_limit_kmh: Option<i32>,
    speed_limit_at_ms: i64,
}

impl Default for TripRecorder {
    fn default() -> Self {
        Self::new(ScoreConfig::default())
    }
}

impl TripRecorder {
    pub fn new(config: ScoreConfig) -> Self {
        Self {
            eco_tracker: EcoTracker::new(config.eco),
            config,
            window: VecDeque::new(),
            active: false,
            distance_km: 0.0,
            max_speed_kmh: 0.0,
            harsh_accel: 0,
            harsh_brake: 0,
            in_accel_event: false,
            in_brake_event: false,
            steer_window: VecDeque::new(),
            brake_signal_known: false,
            brake_pressed: false,
            brake_released_at_ms: None,
            harsh_steer: 0,
            in_steer_event: false,
            last_speed_kmh: 0.0,
            positive_kinetic_sum: 0.0,
            speed_limit_kmh: None,
            speed_limit_at_ms: 0,
        }
    }

    pub fn is_active(&self) -> bool {
        self.active
    }

    pub fn current_distance_km(&self) -> f64 {
        self.distance_km
    }

    pub fn current_harsh_accel(&self) -> i32 {
        self.harsh_accel
    }

    pub fn current_harsh_brake(&self) -> i32 {
        self.harsh_brake
    }

    pub fn current_harsh_steer(&self) -> i32 {
        self.harsh_steer
    }

    pub fn current_safety_score(&self) -> i32 {
        ScoreCalculator::score(
            self.distance_km,
            self.harsh_accel,
            self.harsh_brake,
            &self.config,
            self.harsh_steer,
        )
    }

    pub fn current_eco_breakdown(&self) -> EcoBreakdown {
        self.eco_tracker.breakdown(
            ScoreCalculator::eco(self.current_pke(), &self.config),
            self.distance_km,
        )
    }

    pub fn current_eco_score(&self) -> i32 {
        self.current_eco_breakdown().score(&self.config.eco)
    }

    pub fn current_road_km(&self) -> RoadKm {
        self.eco_tracker.state().road_km
    }

    pub fn current_total_score(&self) -> i32 {
        ScoreCalculator::total(
            self.current_safety_score(),
            self.current_eco_score(),
            &self.config,
        )
    }

    fn current_pke(&self) -> f64 {
        let distance_m = self.distance_km * 1000.0;
        if distance_m >= 50.0 {
            self.positive_kinetic_sum / distance_m
        } else {
            0.0
        }
    }

    pub fn start(&mut self) {
        self.window.clear();
        self.active = true;
        self.distance_km = 0.0;
        self.max_speed_kmh = 0.0;
        self.harsh_accel = 0;
        self.harsh_brake = 0;
        self.in_accel_event = false;
        self.in_brake_event = false;
        self.positive_kinetic_sum = 0.0;
        self.steer_window.clear();
        self.harsh_steer = 0;
        self.brake_signal_known = false;
        self.brake_pressed = false;
        self.brake_released_at_ms = None;
        self.in_steer_event = false;
        self.last_speed_kmh = 0.0;
        self.eco_tracker.reset(EcoState::default());
        self.speed_limit_kmh = None;
        self.speed_limit_at_ms = 0;
    }

    pub fn snapshot(&self) -> TripSnapshot {
        TripSnapshot {
            distance_km: self.distance_km,
            max_speed_kmh: self.max_speed_kmh,
            harsh_accel: self.harsh_accel,
            harsh_brake: self.harsh_brake,
            harsh_steer: self.harsh_steer,
            positive_kinetic_sum: self.positive_kinetic_sum,
            eco: self.eco_tracker.state(),
        }
    }

    pub fn resume(&mut self, snapshot: &TripSnapshot) {
        self.start();
        self.distance_km = snapshot.distance_km;
        self.max_speed_kmh = snapshot.max_speed_kmh;
        self.harsh_accel = snapshot.harsh_accel;
        self.harsh_brake = snapshot.harsh_brake;
        self.harsh_steer = snapshot.harsh_steer;
        self.positive_kinetic_sum = snapshot.positive_kinetic_sum;
        self.eco_tracker.reset(snapshot.eco);
    }

    pub fn on_speed_limit(&mut self, time_ms: i64, limit_kmh: i32) {
        self.speed_limit_kmh = if limit_kmh > 0 { Some(limit_kmh) } else { None };
        self.speed_limit_at_ms = time_ms;
    }

    fn limit_at(&self, time_ms: i64) -> Option<i32> {
        self.speed_limit_kmh
            .filter(|_| time_ms - self.speed_limit_at_ms <= self.config.speed_limit_stale_ms)
    }

    fn pedal_at(&self, time_ms: i64) -> Option<bool> {
        if !self.brake_signal_known {
            return None;
        }
        if self.brake_pressed {
            return Some(true);
        }
        match self.brake_released_at_ms {
            Some(released) => Some(time_ms - released <= self.config.brake_pedal_grace_ms),
            None => Some(false),
        }
    }

    pub fn on_brake_pedal(&mut self, time_ms: i64, pressed: bool) {
        self.brake_signal_known = true;
        if self.brake_pressed && !pressed {
            self.brake_released_at_ms = Some(time_ms);
        }
        self.brake_pressed = pressed;
    }

    fn brake_counts(&self, time_ms: i64, decel_kmh_per_sec: f64) -> bool {
        match self.pedal_at(time_ms) {
            None => true,
            Some(pedal) => pedal || decel_kmh_per_sec >= self.config.harsh_brake_no_pedal_kmh_per_sec,
        }
    }

    pub fn on_speed(&mut self, time_ms: i64, speed_kmh: f64) -> Option<HarshEvent> {
        if !self.active {
            return None;
        }
        let speed = speed_kmh.max(0.0);
        self.max_speed_kmh = self.max_speed_kmh.max(speed);
        self.last_speed_kmh = speed;

        if let Some(&last) = self.window.back() {
            let dt_ms = time_ms - last.time_ms;
            if dt_ms <= 0 {
                return None;
            }
            if dt_ms <= self.config.max_gap_ms {
                let km = (last.speed_kmh + speed) / 2.0 * dt_ms as f64 / 3_600_000.0;
                self.distance_km += km;
                let limit = self.limit_at(time_ms);
                let pedal = self.pedal_at(time_ms);
                self.eco_tracker
                    .on_segment(time_ms, dt_ms, last.speed_kmh, speed, km, limit, pedal);
                if speed > last.speed_kmh {
                    let before = last.speed_kmh / 3.6;
                    let after = speed / 3.6;
                    self.positive_kinetic_sum += after * after - before * before;
                }
            } else {
                self.window.clear();
                self.in_accel_event = false;
                self.in_brake_event = false;
                self.eco_tracker.gap();
            }
        }
        self.window.push_back(Sample {
            time_ms,
            speed_kmh: speed,
        });

        while self.window.len() > 2 && time_ms - self.window[1].time_ms >= self.config.window_ms {
            self.window.pop_front();
        }
        let reference = *self.window.front()?;
        let span_ms = time_ms - reference.time_ms;
        if span_ms < self.config.min_span_ms {
            return None;
        }

        let rate_per_sec = (speed - reference.speed_kmh) / (span_ms as f64 / 1000.0);
        let mut event = None;

        if rate_per_sec >= self.config.harsh_accel_kmh_per_sec {
            if !self.in_accel_event {
                let before = self.current_safety_score();
                self.harsh_accel += 1;
                let after = self.current_safety_score();
                event = Some(HarshEvent {
                    kind: HarshType::Accel,
                    penalty: before - after,
                    safety_after: after,
                });
                self.in_accel_event = true;
            }
        } else if rate_per_sec < self.config.harsh_accel_kmh_per_sec / 2.0 {
            self.in_accel_event = false;
        }

        if -rate_per_sec >= self.config.harsh_brake_kmh_per_sec
            && self.brake_counts(time_ms, -rate_per_sec)
        {
            if !self.in_brake_event {
                let before = self.current_safety_score();
                self.harsh_brake += 1;
                let after = self.current_safety_score();
                event = Some(HarshEvent {
                    kind: HarshType::Brake,
                    penalty: before - after,
                    safety_after: after,
                });
                self.in_brake_event = true;
            }
        } else if -rate_per_sec < self.config.harsh_brake_kmh_per_sec / 2.0 {
            self.in_brake_event = false;
        }
        event
    }

    pub fn on_steering(&mut self, time_ms: i64, angle_deg: f64) -> Option<HarshEvent> {
        if !self.active {
            return None;
        }
        if let Some(&last) = self.steer_window.back() {
            let dt_ms = time_ms - last.time_ms;
            if dt_ms <= 0 {
                return None;
            }
            if dt_ms > self.config.max_gap_ms {
                self.steer_window.clear();
                self.in_steer_event = false;
            }
        }
        self.steer_window.push_back(SteerSample { time_ms, angle_deg });
        while self.steer_window.len() > 2
            && time_ms - self.steer_window[1].time_ms >= self.config.window_ms
        {
            self.steer_window.pop_front();
        }
        let reference = *self.steer_window.front()?;
        let span_ms = time_ms - reference.time_ms;
        if span_ms < self.config.min_span_ms {
            return None;
        }

        let swing = self
            .steer_window
            .iter()
            .map(|s| (s.angle_deg - reference.angle_deg).abs())
            .fold(0.0_f64, f64::max);
        let rate_per_sec = swing / (span_ms as f64 / 1000.0);
        let mut event = None;
        if rate_per_sec >= self.config.harsh_steer_deg_per_sec
            && self.last_speed_kmh >= self.config.steer_min_speed_kmh
        {
            if !self.in_steer_event {
                let before = self.current_safety_score();
                self.harsh_steer += 1;
                let after = self.current_safety_score();
                event = Some(HarshEvent {
                    kind: HarshType::Steer,
                    penalty: before - after,
                    safety_after: after,
                });
                self.in_steer_event = true;
            }
        } else if rate_per_sec < self.config.harsh_steer_deg_per_sec / 2.0 {
            self.in_steer_event = false;
        }
        event
    }

    pub fn finish(&mut self, start_wall_time_ms: i64, end_wall_time_ms: i64) -> TripResult {
        self.active = false;
        let safety = self.current_safety_score();
        let detail = self.current_eco_breakdown();
        let eco = detail.score(&self.config.eco);
        TripResult {
            start_time_ms: start_wall_time_ms,
            end_time_ms: end_wall_time_ms,
            distance_km: self.distance_km,
            harsh_accel_count: self.harsh_accel,
            harsh_brake_count: self.harsh_brake,
            max_speed_kmh: self.max_speed_kmh,
            score: ScoreCalculator::total(safety, eco, &self.config),
            safety_score: safety,
            eco_score: eco,
            harsh_steer_count: self.harsh_steer,
            efficiency: None,
            efficiency_kind: None,
            efficiency_improvement: None,
            eco_detail: Some(detail),
            road_km: self.current_road_km(),
            simulated: false,
            events: Vec::new(),
            hidden: false,
        }
    }
}
