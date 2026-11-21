use std::fs;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

use crate::eco_habits::{EcoBreakdown, EcoState, RoadKm};
use crate::energy::EnergySnapshot;
use crate::models::{Stamp, TripResult};
use crate::trip_continuation::OngoingTrip;
use crate::trip_recorder::{HarshType, LiveEvent, TripSnapshot};

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SavedData {
    #[serde(default)]
    pub trips: Vec<TripResult>,
    #[serde(default)]
    pub stamps: Vec<Stamp>,
}

/// 주행 기록과 스탬프를 기기 안의 파일 하나(JSON)에 저장한다.
/// 기존 Kotlin Storage.kt 의 JSON 스키마(kkwongal.json, ongoing.json)와 100% 호환된다.
#[derive(Debug, Clone)]
pub struct Storage {
    file: PathBuf,
    ongoing_file: PathBuf,
}

impl Storage {
    pub fn new(dir: impl AsRef<Path>) -> Self {
        let dir = dir.as_ref();
        Self {
            file: dir.join("kkwongal.json"),
            ongoing_file: dir.join("ongoing.json"),
        }
    }

    pub fn load(&self) -> SavedData {
        if !self.file.exists() {
            return SavedData::default();
        }
        let Ok(text) = fs::read_to_string(&self.file) else {
            return SavedData::default();
        };
        Self::parse_saved_data(&text).unwrap_or_default()
    }

    pub fn save(&self, data: &SavedData) -> Result<(), String> {
        let text = Self::serialize_saved_data(data)?;
        Self::write_safely(&self.file, &text)
    }

    pub fn load_ongoing(&self) -> Option<OngoingTrip> {
        if !self.ongoing_file.exists() {
            return None;
        }
        let text = match fs::read_to_string(&self.ongoing_file) {
            Ok(t) => t,
            Err(_) => {
                self.clear_ongoing();
                return None;
            }
        };
        match Self::parse_ongoing(&text) {
            Some(trip) => Some(trip),
            None => {
                self.clear_ongoing();
                None
            }
        }
    }

    pub fn save_ongoing(&self, trip: &OngoingTrip) -> Result<(), String> {
        let text = Self::serialize_ongoing(trip)?;
        Self::write_safely(&self.ongoing_file, &text)
    }

    pub fn clear_ongoing(&self) {
        let _ = fs::remove_file(&self.ongoing_file);
    }

    pub fn parse_saved_data(text: &str) -> Option<SavedData> {
        let root: Value = serde_json::from_str(text).ok()?;
        let obj = root.as_object()?;

        let mut trips = Vec::new();
        if let Some(arr) = obj.get("trips").and_then(|v| v.as_array()) {
            for item in arr {
                let o = item.as_object()?;
                let score = get_i32(o, "score")?;
                let eff_kind = o
                    .get("effKind")
                    .and_then(|v| v.as_str())
                    .filter(|s| !s.trim().is_empty())
                    .map(|s| s.to_string());
                let events = match o.get("events").and_then(|v| v.as_array()) {
                    Some(earr) => {
                        let mut out = Vec::with_capacity(earr.len());
                        for ev in earr {
                            out.push(read_event(ev.as_object()?)?);
                        }
                        out
                    }
                    None => Vec::new(),
                };
                trips.push(TripResult {
                    start_time_ms: get_i64(o, "start")?,
                    end_time_ms: get_i64(o, "end")?,
                    distance_km: get_f64(o, "distanceKm")?,
                    harsh_accel_count: get_i32(o, "accel")?,
                    harsh_brake_count: get_i32(o, "brake")?,
                    max_speed_kmh: get_f64(o, "maxSpeed")?,
                    score,
                    safety_score: opt_i32(o, "safety").unwrap_or(score),
                    eco_score: opt_i32(o, "eco").unwrap_or(score),
                    harsh_steer_count: opt_i32(o, "steer").unwrap_or(0),
                    efficiency: opt_f64(o, "eff"),
                    efficiency_kind: eff_kind,
                    efficiency_improvement: opt_f64(o, "effImp"),
                    eco_detail: o
                        .get("ecoDetail")
                        .and_then(|v| v.as_object())
                        .and_then(read_eco_detail),
                    road_km: o
                        .get("roadKm")
                        .and_then(|v| v.as_object())
                        .map(read_road_km)
                        .unwrap_or_default(),
                    simulated: opt_bool(o, "sim").unwrap_or(false),
                    events,
                    hidden: opt_bool(o, "hidden").unwrap_or(false),
                });
            }
        }

        let mut stamps = Vec::new();
        if let Some(arr) = obj.get("stamps").and_then(|v| v.as_array()) {
            for item in arr {
                let o = item.as_object()?;
                let tags = o
                    .get("tags")
                    .and_then(|v| v.as_array())
                    .map(|a| {
                        a.iter()
                            .filter_map(|x| x.as_str().map(|s| s.to_string()))
                            .collect()
                    })
                    .unwrap_or_default();
                stamps.push(Stamp {
                    id: get_i32(o, "id")?,
                    name: o.get("name")?.as_str()?.to_string(),
                    latitude: get_f64(o, "lat")?,
                    longitude: get_f64(o, "lon")?,
                    time_ms: get_i64(o, "time")?,
                    score: get_i32(o, "score")?,
                    memo: o
                        .get("memo")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string(),
                    perfect: opt_bool(o, "perfect").unwrap_or(false),
                    tags,
                });
            }
        }

        Some(SavedData { trips, stamps })
    }

    pub fn serialize_saved_data(data: &SavedData) -> Result<String, String> {
        let trips: Vec<Value> = data
            .trips
            .iter()
            .map(|t| {
                let mut m = Map::new();
                m.insert("start".into(), Value::from(t.start_time_ms));
                m.insert("end".into(), Value::from(t.end_time_ms));
                m.insert("distanceKm".into(), Value::from(t.distance_km));
                m.insert("accel".into(), Value::from(t.harsh_accel_count));
                m.insert("brake".into(), Value::from(t.harsh_brake_count));
                m.insert("maxSpeed".into(), Value::from(t.max_speed_kmh));
                m.insert("score".into(), Value::from(t.score));
                m.insert("safety".into(), Value::from(t.safety_score));
                m.insert("eco".into(), Value::from(t.eco_score));
                m.insert("steer".into(), Value::from(t.harsh_steer_count));
                if let Some(v) = t.efficiency {
                    m.insert("eff".into(), Value::from(v));
                }
                if let Some(ref v) = t.efficiency_kind {
                    m.insert("effKind".into(), Value::from(v.clone()));
                }
                if let Some(v) = t.efficiency_improvement {
                    m.insert("effImp".into(), Value::from(v));
                }
                if let Some(ref d) = t.eco_detail {
                    m.insert("ecoDetail".into(), write_eco_detail(d));
                }
                m.insert("roadKm".into(), write_road_km(&t.road_km));
                if t.simulated {
                    m.insert("sim".into(), Value::from(true));
                }
                if t.hidden {
                    m.insert("hidden".into(), Value::from(true));
                }
                if !t.events.is_empty() {
                    let evs: Vec<Value> = t.events.iter().map(write_event).collect();
                    m.insert("events".into(), Value::Array(evs));
                }
                Value::Object(m)
            })
            .collect();

        let stamps: Vec<Value> = data
            .stamps
            .iter()
            .map(|s| {
                let mut m = Map::new();
                m.insert("id".into(), Value::from(s.id));
                m.insert("name".into(), Value::from(s.name.clone()));
                m.insert("lat".into(), Value::from(s.latitude));
                m.insert("lon".into(), Value::from(s.longitude));
                m.insert("time".into(), Value::from(s.time_ms));
                m.insert("score".into(), Value::from(s.score));
                m.insert("memo".into(), Value::from(s.memo.clone()));
                m.insert("perfect".into(), Value::from(s.perfect));
                m.insert(
                    "tags".into(),
                    Value::Array(s.tags.iter().cloned().map(Value::from).collect()),
                );
                Value::Object(m)
            })
            .collect();

        let mut root = Map::new();
        root.insert("trips".into(), Value::Array(trips));
        root.insert("stamps".into(), Value::Array(stamps));
        serde_json::to_string(&Value::Object(root)).map_err(|e| e.to_string())
    }

    pub fn parse_ongoing(text: &str) -> Option<OngoingTrip> {
        let root: Value = serde_json::from_str(text).ok()?;
        let o = root.as_object()?;
        let snap = o.get("snapshot")?.as_object()?;
        let snapshot = TripSnapshot {
            distance_km: get_f64(snap, "distanceKm")?,
            max_speed_kmh: get_f64(snap, "maxSpeed")?,
            harsh_accel: get_i32(snap, "accel")?,
            harsh_brake: get_i32(snap, "brake")?,
            harsh_steer: get_i32(snap, "steer")?,
            positive_kinetic_sum: get_f64(snap, "pke")?,
            eco: snap
                .get("eco")
                .and_then(|v| v.as_object())
                .map(read_eco_state)
                .unwrap_or_default(),
        };
        let start_energy = o.get("energy").and_then(|v| v.as_object()).map(|e| EnergySnapshot {
            ev_battery_wh: opt_f64(e, "evWh"),
            fuel_percent: opt_f64(e, "fuelPct"),
            fuel_capacity_l: opt_f64(e, "fuelCapL"),
            odometer_km: opt_f64(e, "odoKm"),
            vehicle_ev_km_per_kwh: None,
            vehicle_fuel_km_per_l: None,
        });
        let events = match o.get("events").and_then(|v| v.as_array()) {
            Some(earr) => {
                let mut out = Vec::with_capacity(earr.len());
                for ev in earr {
                    out.push(read_event(ev.as_object()?)?);
                }
                out
            }
            None => Vec::new(),
        };
        Some(OngoingTrip {
            start_wall_ms: get_i64(o, "start")?,
            saved_at_ms: get_i64(o, "savedAt")?,
            snapshot,
            start_energy,
            events,
        })
    }

    pub fn serialize_ongoing(trip: &OngoingTrip) -> Result<String, String> {
        let s = &trip.snapshot;
        let mut snap = Map::new();
        snap.insert("distanceKm".into(), Value::from(s.distance_km));
        snap.insert("maxSpeed".into(), Value::from(s.max_speed_kmh));
        snap.insert("accel".into(), Value::from(s.harsh_accel));
        snap.insert("brake".into(), Value::from(s.harsh_brake));
        snap.insert("steer".into(), Value::from(s.harsh_steer));
        snap.insert("pke".into(), Value::from(s.positive_kinetic_sum));
        snap.insert("eco".into(), write_eco_state(&s.eco));

        let mut root = Map::new();
        root.insert("start".into(), Value::from(trip.start_wall_ms));
        root.insert("savedAt".into(), Value::from(trip.saved_at_ms));
        root.insert("snapshot".into(), Value::Object(snap));
        root.insert(
            "events".into(),
            Value::Array(trip.events.iter().map(write_event).collect()),
        );
        if let Some(ref e) = trip.start_energy {
            let mut em = Map::new();
            if let Some(v) = e.ev_battery_wh {
                em.insert("evWh".into(), Value::from(v));
            }
            if let Some(v) = e.fuel_percent {
                em.insert("fuelPct".into(), Value::from(v));
            }
            if let Some(v) = e.fuel_capacity_l {
                em.insert("fuelCapL".into(), Value::from(v));
            }
            if let Some(v) = e.odometer_km {
                em.insert("odoKm".into(), Value::from(v));
            }
            root.insert("energy".into(), Value::Object(em));
        }
        serde_json::to_string(&Value::Object(root)).map_err(|e| e.to_string())
    }

    fn write_safely(target: &Path, text: &str) -> Result<(), String> {
        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        let mut tmp_name = target
            .file_name()
            .ok_or_else(|| "invalid target path".to_string())?
            .to_os_string();
        tmp_name.push(".tmp");
        let tmp = target.with_file_name(tmp_name);
        fs::write(&tmp, text).map_err(|e| e.to_string())?;
        if fs::rename(&tmp, target).is_err() {
            fs::write(target, text).map_err(|e| e.to_string())?;
            let _ = fs::remove_file(&tmp);
        }
        Ok(())
    }
}

fn get_i64(o: &Map<String, Value>, key: &str) -> Option<i64> {
    o.get(key)?.as_i64()
}

fn get_i32(o: &Map<String, Value>, key: &str) -> Option<i32> {
    let v = o.get(key)?.as_i64()?;
    i32::try_from(v).ok()
}

fn opt_i32(o: &Map<String, Value>, key: &str) -> Option<i32> {
    o.get(key).and_then(|v| v.as_i64()).and_then(|v| i32::try_from(v).ok())
}

fn get_f64(o: &Map<String, Value>, key: &str) -> Option<f64> {
    o.get(key)?.as_f64()
}

fn opt_f64(o: &Map<String, Value>, key: &str) -> Option<f64> {
    o.get(key).and_then(|v| v.as_f64())
}

fn opt_bool(o: &Map<String, Value>, key: &str) -> Option<bool> {
    o.get(key).and_then(|v| v.as_bool())
}

fn read_event(e: &Map<String, Value>) -> Option<LiveEvent> {
    Some(LiveEvent {
        kind: HarshType::from_name(e.get("type")?.as_str()?)?,
        penalty: get_i32(e, "penalty")?,
        score_after: get_i32(e, "after")?,
        wall_time_ms: get_i64(e, "time")?,
        km: get_f64(e, "km")?,
    })
}

fn write_event(e: &LiveEvent) -> Value {
    let mut m = Map::new();
    m.insert("type".into(), Value::from(e.kind.as_str()));
    m.insert("penalty".into(), Value::from(e.penalty));
    m.insert("after".into(), Value::from(e.score_after));
    m.insert("time".into(), Value::from(e.wall_time_ms));
    m.insert("km".into(), Value::from(e.km));
    Value::Object(m)
}

fn read_eco_detail(o: &Map<String, Value>) -> Option<EcoBreakdown> {
    Some(EcoBreakdown {
        accel: get_i32(o, "accel")?,
        steady: opt_i32(o, "steady"),
        speed: opt_i32(o, "speed"),
        coast: opt_i32(o, "coast"),
        idle: opt_i32(o, "idle"),
        over_speed_percent: opt_i32(o, "overPct"),
        coast_percent: opt_i32(o, "coastPct"),
        idle_min: opt_f64(o, "idleMin").unwrap_or(0.0),
    })
}

fn write_eco_detail(d: &EcoBreakdown) -> Value {
    let mut m = Map::new();
    m.insert("accel".into(), Value::from(d.accel));
    m.insert("idleMin".into(), Value::from(d.idle_min));
    if let Some(v) = d.steady {
        m.insert("steady".into(), Value::from(v));
    }
    if let Some(v) = d.speed {
        m.insert("speed".into(), Value::from(v));
    }
    if let Some(v) = d.coast {
        m.insert("coast".into(), Value::from(v));
    }
    if let Some(v) = d.idle {
        m.insert("idle".into(), Value::from(v));
    }
    if let Some(v) = d.over_speed_percent {
        m.insert("overPct".into(), Value::from(v));
    }
    if let Some(v) = d.coast_percent {
        m.insert("coastPct".into(), Value::from(v));
    }
    Value::Object(m)
}

fn read_road_km(o: &Map<String, Value>) -> RoadKm {
    RoadKm {
        city: opt_f64(o, "city").unwrap_or(0.0),
        road: opt_f64(o, "road").unwrap_or(0.0),
        highway: opt_f64(o, "highway").unwrap_or(0.0),
    }
}

fn write_road_km(r: &RoadKm) -> Value {
    let mut m = Map::new();
    m.insert("city".into(), Value::from(r.city));
    m.insert("road".into(), Value::from(r.road));
    m.insert("highway".into(), Value::from(r.highway));
    Value::Object(m)
}

fn read_eco_state(o: &Map<String, Value>) -> EcoState {
    EcoState {
        cruise_sec: opt_f64(o, "cruiseSec").unwrap_or(0.0),
        cruise_change: opt_f64(o, "cruiseChange").unwrap_or(0.0),
        over_speed_km: opt_f64(o, "overKm").unwrap_or(0.0),
        coast_energy: opt_f64(o, "coast").unwrap_or(0.0),
        brake_energy: opt_f64(o, "brake").unwrap_or(0.0),
        idle_sec: opt_f64(o, "idleSec").unwrap_or(0.0),
        road_km: o
            .get("roadKm")
            .and_then(|v| v.as_object())
            .map(read_road_km)
            .unwrap_or_default(),
    }
}

fn write_eco_state(e: &EcoState) -> Value {
    let mut m = Map::new();
    m.insert("cruiseSec".into(), Value::from(e.cruise_sec));
    m.insert("cruiseChange".into(), Value::from(e.cruise_change));
    m.insert("overKm".into(), Value::from(e.over_speed_km));
    m.insert("coast".into(), Value::from(e.coast_energy));
    m.insert("brake".into(), Value::from(e.brake_energy));
    m.insert("idleSec".into(), Value::from(e.idle_sec));
    m.insert("roadKm".into(), write_road_km(&e.road_km));
    Value::Object(m)
}
