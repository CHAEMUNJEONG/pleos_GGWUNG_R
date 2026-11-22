use serde::{Deserialize, Serialize};

use crate::models::GeoPoint;
use crate::stamp_book::distance_meters;

/// 내비가 알려 준 목적지 도착. wall_time_ms 는 실제 시각(System.currentTimeMillis).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ArrivedPlace {
    pub name: String,
    pub wall_time_ms: i64,
    #[serde(default)]
    pub point: Option<GeoPoint>,
}

/// Prototype policy: a recent nearby destination, then a fresh address, then StampBook's default.
pub struct StampNameResolver;

impl StampNameResolver {
    pub const MAX_AGE_MS: i64 = 10 * 60 * 1_000;
    pub const MAX_DISTANCE_METERS: f64 = 500.0;

    pub fn resolve(
        arrival: Option<&ArrivedPlace>,
        address: Option<&str>,
        address_updated_at_ms: Option<i64>,
        parked_at: GeoPoint,
        trip_start_ms: i64,
        now_ms: i64,
    ) -> Option<String> {
        if !Self::valid(parked_at) {
            return None;
        }
        let recent = |time: i64| -> bool {
            time >= trip_start_ms && time <= now_ms && now_ms - time <= Self::MAX_AGE_MS
        };
        if let Some(arr) = arrival {
            if let Some(arrived_at) = arr.point {
                if recent(arr.wall_time_ms)
                    && Self::valid(arrived_at)
                    && distance_meters(arrived_at, parked_at) <= Self::MAX_DISTANCE_METERS
                {
                    if let Some(cleaned) = Self::clean(Some(&arr.name)) {
                        return Some(cleaned);
                    }
                }
            }
        }
        if let Some(addr_time) = address_updated_at_ms {
            if recent(addr_time) {
                return Self::clean(address);
            }
        }
        None
    }

    fn clean(value: Option<&str>) -> Option<String> {
        let trimmed = value?.trim();
        if trimmed.is_empty() {
            return None;
        }
        Some(trimmed.chars().take(100).collect())
    }

    fn valid(point: GeoPoint) -> bool {
        point.latitude.is_finite()
            && point.longitude.is_finite()
            && (-90.0..=90.0).contains(&point.latitude)
            && (-180.0..=180.0).contains(&point.longitude)
    }
}
