use serde::{Deserialize, Serialize};

use crate::eco_habits::{EcoBreakdown, RoadKm};
use crate::trip_recorder::LiveEvent;

/// 위도·경도 한 점.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GeoPoint {
    pub latitude: f64,
    pub longitude: f64,
}

impl GeoPoint {
    pub fn new(latitude: f64, longitude: f64) -> Self {
        Self { latitude, longitude }
    }
}

/// 도로 종류. 내비 제한속도로 나누고, 모르면 평균 속도(정차 포함)로 추정한다.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RoadType {
    City,
    Mixed,
    Highway,
}

impl RoadType {
    pub fn label(self) -> &'static str {
        match self {
            RoadType::City => "시내",
            RoadType::Mixed => "국도",
            RoadType::Highway => "고속도로",
        }
    }

    pub fn of(average_speed_kmh: f64) -> Self {
        if average_speed_kmh < 30.0 {
            RoadType::City
        } else if average_speed_kmh < 65.0 {
            RoadType::Mixed
        } else {
            RoadType::Highway
        }
    }

    /// 제한속도로 나눈다. 90 이상 고속도로(자동차전용도로 포함), 60~80 국도·간선, 50 이하 시내.
    pub fn of_limit(limit_kmh: i32) -> Self {
        if limit_kmh >= 90 {
            RoadType::Highway
        } else if limit_kmh >= 60 {
            RoadType::Mixed
        } else {
            RoadType::City
        }
    }
}

/// 주행 한 번의 결과.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TripResult {
    pub start_time_ms: i64,
    pub end_time_ms: i64,
    pub distance_km: f64,
    pub harsh_accel_count: i32,
    pub harsh_brake_count: i32,
    pub max_speed_kmh: f64,
    pub score: i32,
    pub safety_score: i32,
    pub eco_score: i32,
    #[serde(default)]
    pub harsh_steer_count: i32,
    #[serde(default)]
    pub efficiency: Option<f64>,
    #[serde(default)]
    pub efficiency_kind: Option<String>,
    #[serde(default)]
    pub efficiency_improvement: Option<f64>,
    #[serde(default)]
    pub eco_detail: Option<EcoBreakdown>,
    #[serde(default)]
    pub road_km: RoadKm,
    #[serde(default)]
    pub simulated: bool,
    #[serde(default)]
    pub events: Vec<LiveEvent>,
    #[serde(default)]
    pub hidden: bool,
}

impl TripResult {
    pub fn new(
        start_time_ms: i64,
        end_time_ms: i64,
        distance_km: f64,
        harsh_accel_count: i32,
        harsh_brake_count: i32,
        max_speed_kmh: f64,
        score: i32,
    ) -> Self {
        Self {
            start_time_ms,
            end_time_ms,
            distance_km,
            harsh_accel_count,
            harsh_brake_count,
            max_speed_kmh,
            score,
            safety_score: score,
            eco_score: score,
            harsh_steer_count: 0,
            efficiency: None,
            efficiency_kind: None,
            efficiency_improvement: None,
            eco_detail: None,
            road_km: RoadKm::default(),
            simulated: false,
            events: Vec::new(),
            hidden: false,
        }
    }

    pub fn duration_min(&self) -> i32 {
        ((self.end_time_ms - self.start_time_ms) / 60_000) as i32
    }

    /// 정차 시간까지 포함한 평균 속도 (km/h).
    pub fn average_speed_kmh(&self) -> f64 {
        let hours = (self.end_time_ms - self.start_time_ms) as f64 / 3_600_000.0;
        if hours > 0.0 {
            self.distance_km / hours
        } else {
            0.0
        }
    }

    /// 도로 종류를 제한속도로 정했으면 true, 평균 속도로 추정했으면 false.
    pub fn road_type_from_limit(&self) -> bool {
        self.distance_km > 0.0 && self.road_km.total() >= self.distance_km * 0.5
    }

    /// 이번 주행의 도로 종류.
    pub fn road_type(&self) -> RoadType {
        if self.road_type_from_limit() {
            if let Some(dom) = self.road_km.dominant() {
                return dom;
            }
        }
        RoadType::of(self.average_speed_kmh())
    }
}

/// 처음 가 본 장소에 찍히는 스탬프.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Stamp {
    pub id: i32,
    pub name: String,
    pub latitude: f64,
    pub longitude: f64,
    pub time_ms: i64,
    pub score: i32,
    #[serde(default)]
    pub memo: String,
    #[serde(default)]
    pub tags: Vec<String>,
    #[serde(default)]
    pub perfect: bool,
}

impl Stamp {
    pub fn point(&self) -> GeoPoint {
        GeoPoint::new(self.latitude, self.longitude)
    }
}

/// 스탬프에 붙일 수 있는 태그.
pub struct StampTags;

impl StampTags {
    pub const ALL: [&'static str; 6] = [
        "주차가 편해요",
        "가는 길이 예뻐요",
        "다시 올래요",
        "경치가 좋아요",
        "조용해요",
        "아이와 가기 좋아요",
    ];

    pub const MAX_MEMO_LENGTH: usize = 200;
}

/// 배지. 조건은 BadgeRules 에 있다.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Badge {
    FirstStamp,
    #[serde(rename = "EXPLORER_5")]
    Explorer5,
    #[serde(rename = "SMOOTH_3")]
    Smooth3,
    Perfect,
}

impl Badge {
    pub const ALL: [Badge; 4] = [
        Badge::FirstStamp,
        Badge::Explorer5,
        Badge::Smooth3,
        Badge::Perfect,
    ];

    pub fn title(self) -> &'static str {
        match self {
            Badge::FirstStamp => "첫 발자국",
            Badge::Explorer5 => "탐험가",
            Badge::Smooth3 => "부드러운 손길",
            Badge::Perfect => "무결점 주행",
        }
    }

    pub fn description(self) -> &'static str {
        match self {
            Badge::FirstStamp => "첫 스탬프를 찍었어요",
            Badge::Explorer5 => "스탬프 5개를 모았어요",
            Badge::Smooth3 => "90점 이상 주행을 3번 했어요",
            Badge::Perfect => "급가속·급제동·급조향 없이 주행을 마쳤어요",
        }
    }
}
