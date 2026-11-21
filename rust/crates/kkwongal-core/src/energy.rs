use serde::{Deserialize, Serialize};

use crate::models::TripResult;
use crate::round_half_up_i32;
use crate::trip_recorder::{ScoreCalculator, ScoreConfig};

/// 어떤 에너지로 달리는 차인가.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum EnergyKind {
    Ev,
    Fuel,
}

impl EnergyKind {
    pub fn name(self) -> &'static str {
        match self {
            EnergyKind::Ev => "EV",
            EnergyKind::Fuel => "FUEL",
        }
    }

    pub fn unit(self) -> &'static str {
        match self {
            EnergyKind::Ev => "km/kWh",
            EnergyKind::Fuel => "km/L",
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            EnergyKind::Ev => "전비",
            EnergyKind::Fuel => "연비",
        }
    }
}

/// 한 시점의 에너지 상태. 차량에서 읽을 수 없는 값은 None.
#[derive(Debug, Clone, Copy, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EnergySnapshot {
    #[serde(default)]
    pub ev_battery_wh: Option<f64>,
    #[serde(default)]
    pub fuel_percent: Option<f64>,
    #[serde(default)]
    pub fuel_capacity_l: Option<f64>,
    #[serde(default)]
    pub odometer_km: Option<f64>,
    #[serde(default)]
    pub vehicle_ev_km_per_kwh: Option<f64>,
    #[serde(default)]
    pub vehicle_fuel_km_per_l: Option<f64>,
}

/// 실제로 측정한 연비(전비). value 단위는 kind.unit().
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Efficiency {
    pub kind: EnergyKind,
    pub value: f64,
    pub distance_km: f64,
    pub used: f64,
}

pub struct EnergyMeter;

impl EnergyMeter {
    pub const MIN_DISTANCE_KM: f64 = 0.3;
    pub const MIN_EV_KWH: f64 = 0.05;
    pub const MIN_FUEL_L: f64 = 0.3;
    pub const BASELINE_TRIPS: usize = 10;

    /// 주행 시작과 끝의 에너지 상태로 실제 연비(전비)를 계산한다.
    pub fn measure(
        start: Option<&EnergySnapshot>,
        end: Option<&EnergySnapshot>,
        fallback_distance_km: f64,
    ) -> Option<Efficiency> {
        let odo_delta = match (
            start.and_then(|s| s.odometer_km),
            end.and_then(|e| e.odometer_km),
        ) {
            (Some(s), Some(e)) => Some(e - s),
            _ => None,
        };
        let distance = match odo_delta {
            Some(d) if d > 0.0 => d,
            _ => fallback_distance_km,
        };
        if distance < Self::MIN_DISTANCE_KM {
            return None;
        }
        Self::measure_from_levels(start, end, distance).or_else(|| Self::from_vehicle(end, distance))
    }

    fn measure_from_levels(
        start: Option<&EnergySnapshot>,
        end: Option<&EnergySnapshot>,
        distance: f64,
    ) -> Option<Efficiency> {
        let (start, end) = (start?, end?);
        if let (Some(s_wh), Some(e_wh)) = (start.ev_battery_wh, end.ev_battery_wh) {
            let used_kwh = (s_wh - e_wh) / 1000.0;
            if used_kwh < Self::MIN_EV_KWH {
                return None;
            }
            return Some(Efficiency {
                kind: EnergyKind::Ev,
                value: distance / used_kwh,
                distance_km: distance,
                used: used_kwh,
            });
        }
        let capacity = end.fuel_capacity_l.or(start.fuel_capacity_l);
        if let (Some(s_pct), Some(e_pct), Some(cap)) = (start.fuel_percent, end.fuel_percent, capacity) {
            if cap > 0.0 {
                let used_l = (s_pct - e_pct) / 100.0 * cap;
                if used_l < Self::MIN_FUEL_L {
                    return None;
                }
                return Some(Efficiency {
                    kind: EnergyKind::Fuel,
                    value: distance / used_l,
                    distance_km: distance,
                    used: used_l,
                });
            }
        }
        None
    }

    fn from_vehicle(end: Option<&EnergySnapshot>, distance: f64) -> Option<Efficiency> {
        let end = end?;
        if let Some(v) = end.vehicle_ev_km_per_kwh {
            if (0.5..=20.0).contains(&v) {
                return Some(Efficiency {
                    kind: EnergyKind::Ev,
                    value: v,
                    distance_km: distance,
                    used: distance / v,
                });
            }
        }
        if let Some(v) = end.vehicle_fuel_km_per_l {
            if (1.0..=60.0).contains(&v) {
                return Some(Efficiency {
                    kind: EnergyKind::Fuel,
                    value: v,
                    distance_km: distance,
                    used: distance / v,
                });
            }
        }
        None
    }

    /// 내 차의 평소 기록과 비교한 개선률(%). 이전 측정이 2번 이상 있어야 계산한다.
    pub fn improvement_percent(current: &Efficiency, previous: &[f64]) -> Option<f64> {
        if previous.len() < 2 {
            return None;
        }
        let start_idx = previous.len().saturating_sub(Self::BASELINE_TRIPS);
        let baseline = Self::median(&previous[start_idx..]);
        if baseline <= 0.0 {
            return None;
        }
        Some((current.value / baseline - 1.0) * 100.0)
    }

    fn median(values: &[f64]) -> f64 {
        let mut sorted = values.to_vec();
        sorted.sort_by(f64::total_cmp);
        let mid = sorted.len() / 2;
        if sorted.len() % 2 == 1 {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }

    /// 개선률을 에코 점수로 바꾼다. 평소와 같으면 75점, 20% 좋으면 100점, 40% 나쁘면 25점.
    pub fn eco_from_improvement(improvement_percent: f64) -> i32 {
        round_half_up_i32(75.0 + improvement_percent * 1.25).clamp(0, 100)
    }

    /// 실제 측정값을 주행 결과에 반영한다.
    pub fn apply(
        result: &TripResult,
        efficiency: Option<&Efficiency>,
        history: &[TripResult],
        config: &ScoreConfig,
    ) -> TripResult {
        let Some(eff) = efficiency else {
            return result.clone();
        };
        let kind_name = eff.kind.name();
        let same_kind: Vec<&TripResult> = history
            .iter()
            .filter(|t| t.efficiency_kind.as_deref() == Some(kind_name) && t.efficiency.is_some())
            .collect();
        let result_road = result.road_type();
        let same_road: Vec<&TripResult> = same_kind
            .iter()
            .copied()
            .filter(|t| t.road_type() == result_road)
            .collect();
        let source = if same_road.len() >= 2 {
            &same_road
        } else {
            &same_kind
        };
        let previous: Vec<f64> = source.iter().filter_map(|t| t.efficiency).collect();
        let improvement = Self::improvement_percent(eff, &previous);
        let eco = match improvement {
            None => result.eco_score,
            Some(imp) => {
                round_half_up_i32((result.eco_score as f64 + Self::eco_from_improvement(imp) as f64) / 2.0)
            }
        };
        let mut updated = result.clone();
        updated.eco_score = eco;
        updated.score = ScoreCalculator::total(result.safety_score, eco, config);
        updated.efficiency = Some(eff.value);
        updated.efficiency_kind = Some(kind_name.to_string());
        updated.efficiency_improvement = improvement;
        updated
    }
}
