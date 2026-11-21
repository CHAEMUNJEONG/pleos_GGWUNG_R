use serde::{Deserialize, Serialize};

use crate::models::RoadType;
use crate::round_half_up_i32;

/// 에코 점수를 이루는 운전 습관 기준.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EcoConfig {
    pub cruise_min_kmh: f64,
    pub min_cruise_sec: f64,
    pub steady_best: f64,
    pub steady_worst: f64,

    pub over_speed_margin_kmh: f64,
    pub fallback_over_speed_kmh: f64,
    pub over_speed_worst_share: f64,
    pub min_speed_item_km: f64,

    pub coast_best_share: f64,
    pub coast_worst_share: f64,
    pub min_decel_energy: f64,

    pub idle_grace_sec: f64,
    pub idle_penalty_per_min: f64,
    pub stopped_kmh: f64,

    pub weight_accel: f64,
    pub weight_steady: f64,
    pub weight_speed: f64,
    pub weight_coast: f64,
    pub weight_idle: f64,
}

impl Default for EcoConfig {
    fn default() -> Self {
        Self {
            cruise_min_kmh: 60.0,
            min_cruise_sec: 60.0,
            steady_best: 0.5,
            steady_worst: 2.0,

            over_speed_margin_kmh: 10.0,
            fallback_over_speed_kmh: 110.0,
            over_speed_worst_share: 0.3,
            min_speed_item_km: 1.0,

            coast_best_share: 0.6,
            coast_worst_share: 0.1,
            min_decel_energy: 500.0,

            idle_grace_sec: 120.0,
            idle_penalty_per_min: 10.0,
            stopped_kmh: 1.0,

            weight_accel: 0.40,
            weight_steady: 0.20,
            weight_speed: 0.15,
            weight_coast: 0.15,
            weight_idle: 0.10,
        }
    }
}

/// 에코 점수 항목.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum EcoItem {
    Accel,
    Steady,
    Speed,
    Coast,
    Idle,
}

impl EcoItem {
    pub fn label(self) -> &'static str {
        match self {
            EcoItem::Accel => "부드러운 가속",
            EcoItem::Steady => "정속 주행",
            EcoItem::Speed => "적정 속도",
            EcoItem::Coast => "미리 감속",
            EcoItem::Idle => "공회전",
        }
    }

    pub fn tip(self) -> &'static str {
        match self {
            EcoItem::Accel => "출발할 때 3초만 더 천천히 밟아 보세요",
            EcoItem::Steady => "고속 구간에서는 속도를 일정하게 유지해 보세요",
            EcoItem::Speed => "제한속도 안에서 달리면 공기 저항이 줄어요",
            EcoItem::Coast => "앞이 막히면 일찍 발을 떼고 천천히 줄여 보세요",
            EcoItem::Idle => "오래 설 때는 시동을 꺼 보세요",
        }
    }
}

/// 에코 점수 세부 항목 (각 0~100점). 매기지 않은 항목은 None.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EcoBreakdown {
    pub accel: i32,
    #[serde(default)]
    pub steady: Option<i32>,
    #[serde(default)]
    pub speed: Option<i32>,
    #[serde(default)]
    pub coast: Option<i32>,
    #[serde(default)]
    pub idle: Option<i32>,
    #[serde(default)]
    pub over_speed_percent: Option<i32>,
    #[serde(default)]
    pub coast_percent: Option<i32>,
    #[serde(default)]
    pub idle_min: f64,
}

impl EcoBreakdown {
    pub fn with_accel(accel: i32) -> Self {
        Self {
            accel,
            steady: None,
            speed: None,
            coast: None,
            idle: None,
            over_speed_percent: None,
            coast_percent: None,
            idle_min: 0.0,
        }
    }

    /// 항목별 점수. 매긴 항목만.
    pub fn items(&self) -> Vec<(EcoItem, i32)> {
        let mut out = Vec::with_capacity(5);
        out.push((EcoItem::Accel, self.accel));
        if let Some(v) = self.steady {
            out.push((EcoItem::Steady, v));
        }
        if let Some(v) = self.speed {
            out.push((EcoItem::Speed, v));
        }
        if let Some(v) = self.coast {
            out.push((EcoItem::Coast, v));
        }
        if let Some(v) = self.idle {
            out.push((EcoItem::Idle, v));
        }
        out
    }

    /// 가장 아쉬운 항목. 모두 90점 이상이면 None.
    pub fn weakest(&self) -> Option<EcoItem> {
        self.items()
            .into_iter()
            .filter(|&(_, v)| v < 90)
            .min_by_key(|&(_, v)| v)
            .map(|(item, _)| item)
    }

    /// 항목을 비중대로 합친 에코 점수.
    pub fn score(&self, config: &EcoConfig) -> i32 {
        let mut sum = 0.0;
        let mut weight = 0.0;
        for (item, value) in self.items() {
            let w = match item {
                EcoItem::Accel => config.weight_accel,
                EcoItem::Steady => config.weight_steady,
                EcoItem::Speed => config.weight_speed,
                EcoItem::Coast => config.weight_coast,
                EcoItem::Idle => config.weight_idle,
            };
            sum += value as f64 * w;
            weight += w;
        }
        if weight > 0.0 {
            round_half_up_i32(sum / weight).clamp(0, 100)
        } else {
            self.accel
        }
    }
}

/// 도로 종류별로 달린 거리 (km). 내비가 알려 준 제한속도로 나눈다.
#[derive(Debug, Clone, Copy, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RoadKm {
    #[serde(default)]
    pub city: f64,
    #[serde(default)]
    pub road: f64,
    #[serde(default)]
    pub highway: f64,
}

impl RoadKm {
    pub fn new(city: f64, road: f64, highway: f64) -> Self {
        Self { city, road, highway }
    }

    pub fn total(&self) -> f64 {
        self.city + self.road + self.highway
    }

    pub fn add(&self, road_type: RoadType, km: f64) -> Self {
        match road_type {
            RoadType::City => Self {
                city: self.city + km,
                ..*self
            },
            RoadType::Mixed => Self {
                road: self.road + km,
                ..*self
            },
            RoadType::Highway => Self {
                highway: self.highway + km,
                ..*self
            },
        }
    }

    /// 가장 많이 달린 도로 종류.
    pub fn dominant(&self) -> Option<RoadType> {
        if self.total() <= 0.0 {
            None
        } else if self.highway >= self.road && self.highway >= self.city {
            Some(RoadType::Highway)
        } else if self.road >= self.city {
            Some(RoadType::Mixed)
        } else {
            Some(RoadType::City)
        }
    }
}

/// 이어 가기를 위해 저장하는 누적 값.
#[derive(Debug, Clone, Copy, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EcoState {
    #[serde(default)]
    pub cruise_sec: f64,
    #[serde(default)]
    pub cruise_change: f64,
    #[serde(default)]
    pub over_speed_km: f64,
    #[serde(default)]
    pub coast_energy: f64,
    #[serde(default)]
    pub brake_energy: f64,
    #[serde(default)]
    pub idle_sec: f64,
    #[serde(default)]
    pub road_km: RoadKm,
}

/// 속도·브레이크·제한속도로 에코 운전 습관을 잰다. TripRecorder 가 속도 값마다 호출한다.
#[derive(Debug, Clone)]
pub struct EcoTracker {
    config: EcoConfig,
    state: EcoState,
    cruise_ref_ms: Option<i64>,
    cruise_ref_kmh: f64,
    stopped_sec: f64,
}

impl EcoTracker {
    pub fn new(config: EcoConfig) -> Self {
        Self {
            config,
            state: EcoState::default(),
            cruise_ref_ms: None,
            cruise_ref_kmh: 0.0,
            stopped_sec: 0.0,
        }
    }

    pub fn state(&self) -> EcoState {
        self.state
    }

    pub fn reset(&mut self, from: EcoState) {
        self.state = from;
        self.cruise_ref_ms = None;
        self.stopped_sec = 0.0;
    }

    /// 값이 오래 끊겼을 때. 구간 비교를 처음부터 다시 한다.
    pub fn gap(&mut self) {
        self.cruise_ref_ms = None;
        self.stopped_sec = 0.0;
    }

    pub fn on_segment(
        &mut self,
        time_ms: i64,
        dt_ms: i64,
        prev_kmh: f64,
        kmh: f64,
        km: f64,
        limit_kmh: Option<i32>,
        pedal: Option<bool>,
    ) {
        let mut s = self.state;
        let dt_sec = dt_ms as f64 / 1000.0;

        // 공회전: 멈춰 선 지 idle_grace_sec 를 넘긴 시간만
        if kmh < self.config.stopped_kmh && prev_kmh < self.config.stopped_kmh {
            let before = self.stopped_sec;
            self.stopped_sec += dt_sec;
            let extra = (self.stopped_sec - self.config.idle_grace_sec).max(0.0)
                - (before - self.config.idle_grace_sec).max(0.0);
            if extra > 0.0 {
                s.idle_sec += extra;
            }
        } else {
            self.stopped_sec = 0.0;
        }

        // 적정 속도와 도로 종류
        let avg = (prev_kmh + kmh) / 2.0;
        let threshold = limit_kmh
            .map(|l| l as f64 + self.config.over_speed_margin_kmh)
            .unwrap_or(self.config.fallback_over_speed_kmh);
        if avg > threshold {
            s.over_speed_km += km;
        }
        if let Some(limit) = limit_kmh {
            s.road_km = s.road_km.add(RoadType::of_limit(limit), km);
        }

        // 미리 감속: 줄어든 (속도 제곱)을 페달을 밟았는지에 따라 나눠 담는다
        if let Some(is_pedal) = pedal {
            if kmh < prev_kmh {
                let v0 = prev_kmh / 3.6;
                let v1 = kmh / 3.6;
                let energy = v0 * v0 - v1 * v1;
                if is_pedal {
                    s.brake_energy += energy;
                } else {
                    s.coast_energy += energy;
                }
            }
        }

        // 정속 주행: 시속 60km 이상에서 약 1초마다 속도 변화를 더한다
        if kmh >= self.config.cruise_min_kmh {
            match self.cruise_ref_ms {
                None => {
                    self.cruise_ref_ms = Some(time_ms);
                    self.cruise_ref_kmh = kmh;
                }
                Some(ref_ms) if time_ms - ref_ms >= 1_000 => {
                    s.cruise_sec += (time_ms - ref_ms) as f64 / 1000.0;
                    s.cruise_change += (kmh - self.cruise_ref_kmh).abs();
                    self.cruise_ref_ms = Some(time_ms);
                    self.cruise_ref_kmh = kmh;
                }
                Some(_) => {}
            }
        } else {
            self.cruise_ref_ms = None;
        }

        self.state = s;
    }

    pub fn breakdown(&self, accel: i32, distance_km: f64) -> EcoBreakdown {
        let s = self.state;
        let steady = if s.cruise_sec >= self.config.min_cruise_sec {
            let per_sec = s.cruise_change / s.cruise_sec;
            Some(Self::linear(
                per_sec,
                self.config.steady_best,
                self.config.steady_worst,
            ))
        } else {
            None
        };

        let over_share = if distance_km >= self.config.min_speed_item_km {
            Some((s.over_speed_km / distance_km).clamp(0.0, 1.0))
        } else {
            None
        };
        let speed = over_share.map(|it| Self::linear(it, 0.0, self.config.over_speed_worst_share));

        let decel = s.coast_energy + s.brake_energy;
        let coast_share = if decel >= self.config.min_decel_energy {
            Some(s.coast_energy / decel)
        } else {
            None
        };
        let coast = coast_share.map(|it| {
            Self::linear(
                it,
                self.config.coast_best_share,
                self.config.coast_worst_share,
            )
        });

        let idle_min = s.idle_sec / 60.0;
        let idle = if distance_km >= self.config.min_speed_item_km || s.idle_sec > 0.0 {
            Some(
                round_half_up_i32(100.0 - idle_min * self.config.idle_penalty_per_min)
                    .clamp(0, 100),
            )
        } else {
            None
        };

        EcoBreakdown {
            accel,
            steady,
            speed,
            coast,
            idle,
            over_speed_percent: over_share.map(|it| round_half_up_i32(it * 100.0)),
            coast_percent: coast_share.map(|it| round_half_up_i32(it * 100.0)),
            idle_min,
        }
    }

    /// best 이면 100점, worst 이면 0점, 사이는 직선으로.
    fn linear(value: f64, best: f64, worst: f64) -> i32 {
        let ratio = ((value - best) / (worst - best)).clamp(0.0, 1.0);
        round_half_up_i32(100.0 * (1.0 - ratio))
    }
}
