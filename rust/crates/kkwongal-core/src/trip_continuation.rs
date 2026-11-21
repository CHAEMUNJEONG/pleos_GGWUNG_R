use serde::{Deserialize, Serialize};

use crate::energy::EnergySnapshot;
use crate::models::GeoPoint;
use crate::stamp_book::distance_meters;
use crate::trip_recorder::{HarshType, LiveEvent, TripSnapshot};

/// 진행 중이던 주행. 앱이 갑자기 꺼져도 주행이 사라지지 않도록 주기적으로 저장한다.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OngoingTrip {
    pub start_wall_ms: i64,
    pub saved_at_ms: i64,
    pub snapshot: TripSnapshot,
    #[serde(default)]
    pub start_energy: Option<EnergySnapshot>,
    #[serde(default)]
    pub events: Vec<LiveEvent>,
}

/// 주행 중 급조작이 일어난 시점. fraction 은 주행 시간 중 위치 (0 = 출발, 1 = 도착).
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TripMark {
    #[serde(rename = "type")]
    pub kind: HarshType,
    pub fraction: f32,
}

/// 주행을 이어 붙일지 정하는 규칙.
pub struct TripContinuation;

impl TripContinuation {
    pub const RESUME_WINDOW_MS: i64 = 3 * 60_000;
    pub const RESUME_RADIUS_M: f64 = 300.0;
    pub const RECOVER_WINDOW_MS: i64 = 10 * 60_000;

    /// 잠깐 정차 후 다시 출발했을 때 앞 주행에 이어 붙일지. 위치를 모르면 시간만 본다.
    pub fn can_continue(
        stopped_at_ms: i64,
        now_ms: i64,
        stopped_at: Option<GeoPoint>,
        here: Option<GeoPoint>,
    ) -> bool {
        let gap = now_ms - stopped_at_ms;
        if gap < 0 || gap > Self::RESUME_WINDOW_MS {
            return false;
        }
        if let (Some(a), Some(b)) = (stopped_at, here) {
            if distance_meters(a, b) > Self::RESUME_RADIUS_M {
                return false;
            }
        }
        true
    }

    /// 앱이 다시 켜졌을 때 저장해 둔 주행을 이어 갈지.
    pub fn can_recover(saved_at_ms: i64, now_ms: i64) -> bool {
        let gap = now_ms - saved_at_ms;
        (0..=Self::RECOVER_WINDOW_MS).contains(&gap)
    }

    /// 감점 기록을 리포트 시간 막대의 점으로 바꾼다.
    pub fn marks(events: &[LiveEvent], start_wall_ms: i64, end_wall_ms: i64) -> Vec<TripMark> {
        let duration = (end_wall_ms - start_wall_ms).max(1) as f32;
        events
            .iter()
            .map(|e| TripMark {
                kind: e.kind,
                fraction: ((e.wall_time_ms - start_wall_ms) as f32 / duration).clamp(0.0, 1.0),
            })
            .collect()
    }
}
