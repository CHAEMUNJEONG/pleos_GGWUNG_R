use std::collections::BTreeSet;

use crate::models::{Badge, GeoPoint, Stamp, StampTags, TripResult};

/// 두 지점 사이의 거리(미터). 하버사인 공식.
pub fn distance_meters(a: GeoPoint, b: GeoPoint) -> f64 {
    const EARTH_RADIUS_M: f64 = 6_371_000.0;
    let d_lat = (b.latitude - a.latitude).to_radians();
    let d_lon = (b.longitude - a.longitude).to_radians();
    let h = (d_lat / 2.0).sin().powi(2)
        + a.latitude.to_radians().cos()
            * b.latitude.to_radians().cos()
            * (d_lon / 2.0).sin().powi(2);
    2.0 * EARTH_RADIUS_M * h.clamp(0.0, 1.0).sqrt().asin()
}

/// 스탬프 모음. 이미 찍힌 스탬프에서 반경 new_place_radius_m 안이면 같은 장소로 본다.
#[derive(Debug, Clone)]
pub struct StampBook {
    items: Vec<Stamp>,
    new_place_radius_m: f64,
}

impl Default for StampBook {
    fn default() -> Self {
        Self::new(Vec::new(), 500.0)
    }
}

impl StampBook {
    pub fn new(initial: Vec<Stamp>, new_place_radius_m: f64) -> Self {
        Self {
            items: initial,
            new_place_radius_m,
        }
    }

    pub fn stamps(&self) -> &[Stamp] {
        &self.items
    }

    /// 이 위치 근처에 이미 찍힌 스탬프. 없으면 None.
    pub fn find_nearby(&self, point: GeoPoint) -> Option<&Stamp> {
        self.items
            .iter()
            .find(|s| distance_meters(s.point(), point) <= self.new_place_radius_m)
    }

    /// 처음 온 곳이면 스탬프를 찍고 돌려준다. 이미 온 곳이면 None.
    pub fn try_add(
        &mut self,
        point: GeoPoint,
        time_ms: i64,
        score: i32,
        name: Option<&str>,
        perfect: bool,
    ) -> Option<Stamp> {
        if self.find_nearby(point).is_some() {
            return None;
        }
        let id = self.items.iter().map(|s| s.id).max().unwrap_or(0) + 1;
        let resolved_name = match name {
            Some(n) if !n.trim().is_empty() => n.to_string(),
            _ => format!("새로운 장소 {id}"),
        };
        let stamp = Stamp {
            id,
            name: resolved_name,
            latitude: point.latitude,
            longitude: point.longitude,
            time_ms,
            score,
            memo: String::new(),
            tags: Vec::new(),
            perfect,
        };
        self.items.push(stamp.clone());
        Some(stamp)
    }

    /// 스탬프 이름을 바꾼다. 없는 id 거나 빈 이름이면 None.
    pub fn rename(&mut self, id: i32, name: &str) -> Option<Stamp> {
        if name.trim().is_empty() {
            return None;
        }
        let current = self.items.iter().find(|s| s.id == id)?.clone();
        self.update(id, name, &current.memo, &current.tags)
    }

    /// 스탬프를 지운다. 없으면 false.
    pub fn remove(&mut self, id: i32) -> bool {
        let before = self.items.len();
        self.items.retain(|s| s.id != id);
        self.items.len() != before
    }

    /// 스탬프의 이름·메모·태그를 한 번에 고친다.
    pub fn update(
        &mut self,
        id: i32,
        name: &str,
        memo: &str,
        tags: &[String],
    ) -> Option<Stamp> {
        let idx = self.items.iter().position(|s| s.id == id)?;
        let current = &self.items[idx];
        let trimmed_name = name.trim();
        let new_name = if trimmed_name.is_empty() {
            current.name.clone()
        } else {
            trimmed_name.to_string()
        };
        let new_memo: String = memo.trim().chars().take(StampTags::MAX_MEMO_LENGTH).collect();
        let new_tags: Vec<String> = StampTags::ALL
            .iter()
            .filter(|&&allowed| tags.iter().any(|t| t == allowed))
            .map(|&s| s.to_string())
            .collect();
        let updated = Stamp {
            name: new_name,
            memo: new_memo,
            tags: new_tags,
            ..current.clone()
        };
        self.items[idx] = updated.clone();
        Some(updated)
    }
}

pub struct BadgeRules;

impl BadgeRules {
    pub fn evaluate(trips: &[TripResult], stamps: &[Stamp]) -> BTreeSet<Badge> {
        let mut earned = BTreeSet::new();
        if !stamps.is_empty() {
            earned.insert(Badge::FirstStamp);
        }
        if stamps.len() >= 5 {
            earned.insert(Badge::Explorer5);
        }
        if trips.iter().filter(|t| t.score >= 90).count() >= 3 {
            earned.insert(Badge::Smooth3);
        }
        if trips.iter().any(|t| {
            t.harsh_accel_count == 0 && t.harsh_brake_count == 0 && t.harsh_steer_count == 0
        }) {
            earned.insert(Badge::Perfect);
        }
        earned
    }
}
