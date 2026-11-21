use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr;
use std::sync::Mutex;

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jdouble, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use kkwongal_core::coaching::Coaching;
use kkwongal_core::diagnostics::{Channel, ChannelStatus, DiagnosticsBoard, SpeedDriveDetector};
use kkwongal_core::eco_habits::{EcoBreakdown, EcoConfig};
use kkwongal_core::egg_levels::EggLevels;
use kkwongal_core::energy::{Efficiency, EnergyMeter, EnergySnapshot};
use kkwongal_core::energy_text::energy_text;
use kkwongal_core::engine::KkwongalEngine;
use kkwongal_core::fake_vehicle::{DriveState, FakeVehicleEngine};
use kkwongal_core::korea_outline;
use kkwongal_core::league::{League, LeagueConfig};
use kkwongal_core::league_client::{LeagueClient, TripUploadDto};
use kkwongal_core::models::{GeoPoint, RoadType, Stamp, TripResult};
use kkwongal_core::stamp_book::{distance_meters, BadgeRules, StampBook};
use kkwongal_core::stamp_name_resolver::{ArrivedPlace, StampNameResolver};
use kkwongal_core::storage::{SavedData, Storage};
use kkwongal_core::trip_continuation::{OngoingTrip, TripContinuation};
use kkwongal_core::trip_recorder::{LiveEvent, ScoreCalculator, ScoreConfig, TripRecorder, TripSnapshot};
use serde::Serialize;
use serde_json::json;

fn get_opt_string(env: &mut JNIEnv, s: JString) -> Option<String> {
    if s.is_null() {
        return None;
    }
    env.get_string(&s).ok().map(|js| js.into())
}

fn get_string(env: &mut JNIEnv, s: JString) -> String {
    get_opt_string(env, s).unwrap_or_default()
}

fn to_jstring(env: &JNIEnv, value: &str) -> jstring {
    env.new_string(value)
        .map(|s| s.into_raw())
        .unwrap_or(ptr::null_mut())
}

fn to_opt_jstring(env: &JNIEnv, value: Option<String>) -> jstring {
    match value {
        Some(s) => to_jstring(env, &s),
        None => ptr::null_mut(),
    }
}

fn to_json_jstring<T: Serialize>(env: &JNIEnv, value: &T) -> jstring {
    match serde_json::to_string(value) {
        Ok(s) => to_jstring(env, &s),
        Err(_) => ptr::null_mut(),
    }
}

fn box_handle<T>(value: T) -> jlong {
    Box::into_raw(Box::new(Mutex::new(value))) as jlong
}

unsafe fn with_handle<T, R, F>(handle: jlong, f: F) -> Option<R>
where
    F: FnOnce(&mut T) -> R,
{
    if handle == 0 {
        return None;
    }
    let mutex = &*(handle as *const Mutex<T>);
    let mut guard = mutex.lock().ok()?;
    Some(f(&mut *guard))
}

unsafe fn free_handle<T>(handle: jlong) {
    if handle != 0 {
        drop(Box::from_raw(handle as *mut Mutex<T>));
    }
}

fn to_jbool(b: bool) -> jboolean {
    if b {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

fn from_jbool(b: jboolean) -> bool {
    b != JNI_FALSE
}

fn parse_score_config(json_opt: Option<String>) -> ScoreConfig {
    json_opt
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

fn parse_eco_config(json_opt: Option<String>) -> EcoConfig {
    json_opt
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

fn parse_league_config(json_opt: Option<String>) -> LeagueConfig {
    json_opt
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

fn parse_channel(name: &str) -> Option<Channel> {
    serde_json::from_str(&format!("\"{name}\"")).ok()
}

fn parse_channel_status(name: &str) -> Option<ChannelStatus> {
    serde_json::from_str(&format!("\"{name}\"")).ok()
}

fn parse_drive_state(name: &str) -> DriveState {
    serde_json::from_str(&format!("\"{name}\"")).unwrap_or(DriveState::Unknown)
}

// ============================================================================
// Health & Metadata
// ============================================================================

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeHealthCheck(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| to_jstring(&env, "kkwongal-rust-0.1.0")))
        .unwrap_or(ptr::null_mut())
}

// ============================================================================
// Stateless Core Calculations
// ============================================================================

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeScoreSafety(
    mut env: JNIEnv,
    _class: JClass,
    distance_km: jdouble,
    harsh_accel: jint,
    harsh_brake: jint,
    harsh_steer: jint,
    config_json: JString,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let cfg = parse_score_config(get_opt_string(&mut env, config_json));
        ScoreCalculator::score(distance_km, harsh_accel, harsh_brake, &cfg, harsh_steer)
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeScoreEco(
    mut env: JNIEnv,
    _class: JClass,
    pke: jdouble,
    config_json: JString,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let cfg = parse_score_config(get_opt_string(&mut env, config_json));
        ScoreCalculator::eco(pke, &cfg)
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeScoreTotal(
    mut env: JNIEnv,
    _class: JClass,
    safety: jint,
    eco: jint,
    config_json: JString,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let cfg = parse_score_config(get_opt_string(&mut env, config_json));
        ScoreCalculator::total(safety, eco, &cfg)
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEcoBreakdownScore(
    mut env: JNIEnv,
    _class: JClass,
    breakdown_json: JString,
    config_json: JString,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let b_str = get_string(&mut env, breakdown_json);
        let cfg = parse_eco_config(get_opt_string(&mut env, config_json));
        serde_json::from_str::<EcoBreakdown>(&b_str)
            .map(|b| b.score(&cfg))
            .unwrap_or(0)
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEcoBreakdownWeakest(
    mut env: JNIEnv,
    _class: JClass,
    breakdown_json: JString,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let b_str = get_string(&mut env, breakdown_json);
        let weakest = serde_json::from_str::<EcoBreakdown>(&b_str)
            .ok()
            .and_then(|b| b.weakest())
            .and_then(|item| serde_json::to_value(item).ok())
            .and_then(|v| v.as_str().map(String::from));
        to_opt_jstring(&env, weakest)
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeRoadTypeOf(
    env: JNIEnv,
    _class: JClass,
    avg_speed_kmh: jdouble,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let rt = RoadType::of(avg_speed_kmh);
        let name = serde_json::to_value(rt)
            .ok()
            .and_then(|v| v.as_str().map(String::from))
            .unwrap_or_else(|| "CITY".to_string());
        to_jstring(&env, &name)
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeRoadTypeOfLimit(
    env: JNIEnv,
    _class: JClass,
    limit_kmh: jint,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let rt = RoadType::of_limit(limit_kmh);
        let name = serde_json::to_value(rt)
            .ok()
            .and_then(|v| v.as_str().map(String::from))
            .unwrap_or_else(|| "CITY".to_string());
        to_jstring(&env, &name)
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEnergyMeasure(
    mut env: JNIEnv,
    _class: JClass,
    start_json: JString,
    end_json: JString,
    fallback_distance_km: jdouble,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let start: Option<EnergySnapshot> =
            get_opt_string(&mut env, start_json).and_then(|s| serde_json::from_str(&s).ok());
        let end: Option<EnergySnapshot> =
            get_opt_string(&mut env, end_json).and_then(|s| serde_json::from_str(&s).ok());
        let eff = EnergyMeter::measure(start.as_ref(), end.as_ref(), fallback_distance_km);
        to_opt_jstring(&env, eff.and_then(|e| serde_json::to_string(&e).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEnergyImprovement(
    mut env: JNIEnv,
    _class: JClass,
    eff_json: JString,
    prev_json: JString,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let eff: Option<Efficiency> = serde_json::from_str(&get_string(&mut env, eff_json)).ok();
        let prev: Vec<f64> =
            serde_json::from_str(&get_string(&mut env, prev_json)).unwrap_or_default();
        let imp = eff.and_then(|e| EnergyMeter::improvement_percent(&e, &prev));
        to_opt_jstring(&env, imp.map(|v| v.to_string()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEnergyEcoFromImprovement(
    _env: JNIEnv,
    _class: JClass,
    improvement_percent: jdouble,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        EnergyMeter::eco_from_improvement(improvement_percent)
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEnergyApply(
    mut env: JNIEnv,
    _class: JClass,
    result_json: JString,
    eff_json: JString,
    history_json: JString,
    config_json: JString,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let res_str = get_string(&mut env, result_json);
        let eff: Option<Efficiency> =
            get_opt_string(&mut env, eff_json).and_then(|s| serde_json::from_str(&s).ok());
        let history: Vec<TripResult> =
            serde_json::from_str(&get_string(&mut env, history_json)).unwrap_or_default();
        let cfg = parse_score_config(get_opt_string(&mut env, config_json));
        let Ok(result) = serde_json::from_str::<TripResult>(&res_str) else {
            return ptr::null_mut();
        };
        let applied = EnergyMeter::apply(&result, eff.as_ref(), &history, &cfg);
        to_json_jstring(&env, &applied)
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEnergyText(
    mut env: JNIEnv,
    _class: JClass,
    energy_json: JString,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let snap: EnergySnapshot =
            serde_json::from_str(&get_string(&mut env, energy_json)).unwrap_or_default();
        to_jstring(&env, &energy_text(&snap))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeCoachingEcoDelta(
    mut env: JNIEnv,
    _class: JClass,
    result_json: JString,
    history_json: JString,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let Ok(result) = serde_json::from_str::<TripResult>(&get_string(&mut env, result_json)) else {
            return ptr::null_mut();
        };
        let history: Vec<TripResult> =
            serde_json::from_str(&get_string(&mut env, history_json)).unwrap_or_default();
        let delta = Coaching::eco_delta(&result, &history).map(|d| d.to_string());
        to_opt_jstring(&env, delta)
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeCoachingDeltaText(
    env: JNIEnv,
    _class: JClass,
    delta: jint,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| to_jstring(&env, &Coaching::delta_text(delta))))
        .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeCoachingParkingSummary(
    mut env: JNIEnv,
    _class: JClass,
    result_json: JString,
    has_delta: jboolean,
    delta: jint,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let Ok(result) = serde_json::from_str::<TripResult>(&get_string(&mut env, result_json)) else {
            return ptr::null_mut();
        };
        let d = if from_jbool(has_delta) { Some(delta) } else { None };
        to_jstring(&env, &Coaching::parking_summary(&result, d))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeCoachingWithSubject(
    mut env: JNIEnv,
    _class: JClass,
    word: JString,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let w = get_string(&mut env, word);
        to_jstring(&env, &Coaching::with_subject(&w))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEggLevelInfo(
    env: JNIEnv,
    _class: JClass,
    eggs: jint,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let payload = json!({
            "current": EggLevels::current(eggs),
            "next": EggLevels::next(eggs),
            "remaining": EggLevels::remaining(eggs),
            "progress": EggLevels::progress(eggs),
        });
        to_json_jstring(&env, &payload)
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeDistanceMeters(
    _env: JNIEnv,
    _class: JClass,
    lat_a: jdouble,
    lon_a: jdouble,
    lat_b: jdouble,
    lon_b: jdouble,
) -> jdouble {
    catch_unwind(AssertUnwindSafe(|| {
        distance_meters(GeoPoint::new(lat_a, lon_a), GeoPoint::new(lat_b, lon_b))
    }))
    .unwrap_or(0.0)
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeBadgesEvaluate(
    mut env: JNIEnv,
    _class: JClass,
    trips_json: JString,
    stamps_json: JString,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let trips: Vec<TripResult> =
            serde_json::from_str(&get_string(&mut env, trips_json)).unwrap_or_default();
        let stamps: Vec<Stamp> =
            serde_json::from_str(&get_string(&mut env, stamps_json)).unwrap_or_default();
        let badges = BadgeRules::evaluate(&trips, &stamps);
        to_json_jstring(&env, &badges)
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeTripCanContinue(
    mut env: JNIEnv,
    _class: JClass,
    stopped_at_ms: jlong,
    now_ms: jlong,
    stopped_json: JString,
    here_json: JString,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let stopped: Option<GeoPoint> =
            get_opt_string(&mut env, stopped_json).and_then(|s| serde_json::from_str(&s).ok());
        let here: Option<GeoPoint> =
            get_opt_string(&mut env, here_json).and_then(|s| serde_json::from_str(&s).ok());
        to_jbool(TripContinuation::can_continue(stopped_at_ms, now_ms, stopped, here))
    }))
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeTripCanRecover(
    _env: JNIEnv,
    _class: JClass,
    saved_at_ms: jlong,
    now_ms: jlong,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        to_jbool(TripContinuation::can_recover(saved_at_ms, now_ms))
    }))
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeTripMarks(
    mut env: JNIEnv,
    _class: JClass,
    events_json: JString,
    start_wall_ms: jlong,
    end_wall_ms: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let events: Vec<LiveEvent> =
            serde_json::from_str(&get_string(&mut env, events_json)).unwrap_or_default();
        let marks = TripContinuation::marks(&events, start_wall_ms, end_wall_ms);
        to_json_jstring(&env, &marks)
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeLeagueWeekStartMs(
    mut env: JNIEnv,
    _class: JClass,
    time_ms: jlong,
    zone_id: JString,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let zone = get_string(&mut env, zone_id);
        League::week_start_ms(time_ms, &zone)
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeLeagueRewardFor(
    mut env: JNIEnv,
    _class: JClass,
    rank: jint,
    size: jint,
    config_json: JString,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let cfg = parse_league_config(get_opt_string(&mut env, config_json));
        League::reward_for(rank, size, &cfg)
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeLeagueStanding(
    mut env: JNIEnv,
    _class: JClass,
    trips_json: JString,
    now_ms: jlong,
    zone_id: JString,
    config_json: JString,
    simulated: jboolean,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let trips: Vec<TripResult> =
            serde_json::from_str(&get_string(&mut env, trips_json)).unwrap_or_default();
        let zone = get_string(&mut env, zone_id);
        let cfg = parse_league_config(get_opt_string(&mut env, config_json));
        let st = League::standing(&trips, now_ms, &zone, &cfg, from_jbool(simulated));
        to_json_jstring(&env, &st)
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeLeagueEarnedPoints(
    mut env: JNIEnv,
    _class: JClass,
    trips_json: JString,
    now_ms: jlong,
    zone_id: JString,
    config_json: JString,
    simulated: jboolean,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let trips: Vec<TripResult> =
            serde_json::from_str(&get_string(&mut env, trips_json)).unwrap_or_default();
        let zone = get_string(&mut env, zone_id);
        let cfg = parse_league_config(get_opt_string(&mut env, config_json));
        League::earned_points(&trips, now_ms, &zone, &cfg, from_jbool(simulated))
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeStampNameResolve(
    mut env: JNIEnv,
    _class: JClass,
    has_arrival: jboolean,
    arrival_name: JString,
    arrival_time_ms: jlong,
    has_arrival_point: jboolean,
    arrival_lat: jdouble,
    arrival_lon: jdouble,
    address: JString,
    has_addr_time: jboolean,
    addr_time: jlong,
    parked_lat: jdouble,
    parked_lon: jdouble,
    trip_start_ms: jlong,
    now_ms: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let arrival = if from_jbool(has_arrival) {
            Some(ArrivedPlace {
                name: get_string(&mut env, arrival_name),
                wall_time_ms: arrival_time_ms,
                point: if from_jbool(has_arrival_point) {
                    Some(GeoPoint::new(arrival_lat, arrival_lon))
                } else {
                    None
                },
            })
        } else {
            None
        };
        let addr = get_opt_string(&mut env, address);
        let addr_ts = if from_jbool(has_addr_time) {
            Some(addr_time)
        } else {
            None
        };
        let resolved = StampNameResolver::resolve(
            arrival.as_ref(),
            addr.as_deref(),
            addr_ts,
            GeoPoint::new(parked_lat, parked_lon),
            trip_start_ms,
            now_ms,
        );
        to_opt_jstring(&env, resolved)
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeKoreaOutlineJson(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let payload = json!({
            "minLon": korea_outline::MIN_LON,
            "maxLon": korea_outline::MAX_LON,
            "minLat": korea_outline::MIN_LAT,
            "maxLat": korea_outline::MAX_LAT,
            "dokdo": korea_outline::DOKDO,
            "polygons": korea_outline::POLYGONS,
        });
        to_json_jstring(&env, &payload)
    }))
    .unwrap_or(ptr::null_mut())
}

// ============================================================================
// League HTTP Client & DTO Helpers
// ============================================================================

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeTripUploadFromTrip(
    mut env: JNIEnv,
    _class: JClass,
    trip_json: JString,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let Ok(trip) = serde_json::from_str::<TripResult>(&get_string(&mut env, trip_json)) else {
            return ptr::null_mut();
        };
        let dto = TripUploadDto::from_trip(&trip);
        to_json_jstring(&env, &dto)
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeValidateLeagueBaseUrl(
    mut env: JNIEnv,
    _class: JClass,
    base_url: JString,
    allow_local_http: jboolean,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let url = get_string(&mut env, base_url);
        match LeagueClient::validate_base_url(&url, from_jbool(allow_local_http)) {
            Ok(_) => ptr::null_mut(),
            Err(e) => to_jstring(&env, &e),
        }
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeLeagueClientSubmit(
    mut env: JNIEnv,
    _class: JClass,
    base_url: JString,
    token: JString,
    allow_local_http: jboolean,
    trip_upload_json: JString,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let url = get_string(&mut env, base_url);
        let tok = get_opt_string(&mut env, token);
        let trip_str = get_string(&mut env, trip_upload_json);
        let Ok(dto) = serde_json::from_str::<TripUploadDto>(&trip_str) else {
            return to_json_jstring(
                &env,
                &json!({"kind": "failed", "reason": "INVALID_RESPONSE", "httpStatus": null}),
            );
        };
        let Ok(client) = LeagueClient::create(&url, tok, from_jbool(allow_local_http)) else {
            return to_json_jstring(
                &env,
                &json!({"kind": "failed", "reason": "INVALID_RESPONSE", "httpStatus": null}),
            );
        };
        let reply = client.submit(&dto);
        to_json_jstring(&env, &reply)
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeLeagueClientWeekly(
    mut env: JNIEnv,
    _class: JClass,
    base_url: JString,
    token: JString,
    allow_local_http: jboolean,
    week_start_ms: jlong,
    simulated: jboolean,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let url = get_string(&mut env, base_url);
        let tok = get_opt_string(&mut env, token);
        let Ok(client) = LeagueClient::create(&url, tok, from_jbool(allow_local_http)) else {
            return to_json_jstring(
                &env,
                &json!({"kind": "failed", "reason": "INVALID_RESPONSE", "httpStatus": null}),
            );
        };
        let reply = client.weekly(week_start_ms, from_jbool(simulated));
        to_json_jstring(&env, &reply)
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeValidateWeeklyResponse(
    mut env: JNIEnv,
    _class: JClass,
    body: JString,
    requested_week: jlong,
    requested_simulation: jboolean,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let b = get_string(&mut env, body);
        let reply = LeagueClient::validate_weekly(&b, requested_week, from_jbool(requested_simulation));
        to_json_jstring(&env, &reply)
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeValidateUploadReceipt(
    mut env: JNIEnv,
    _class: JClass,
    trip_upload_json: JString,
    body: JString,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let t_str = get_string(&mut env, trip_upload_json);
        let b = get_string(&mut env, body);
        let Ok(dto) = serde_json::from_str::<TripUploadDto>(&t_str) else {
            return to_json_jstring(
                &env,
                &json!({"kind": "failed", "reason": "INVALID_RESPONSE", "httpStatus": null}),
            );
        };
        let reply = LeagueClient::validate_receipt(&dto, &b);
        to_json_jstring(&env, &reply)
    }))
    .unwrap_or(ptr::null_mut())
}

// ============================================================================
// Stateful Handle: TripRecorder
// ============================================================================

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeRecorderNew(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let cfg = parse_score_config(get_opt_string(&mut env, config_json));
        box_handle(TripRecorder::new(cfg))
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeRecorderFree(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| unsafe { free_handle::<TripRecorder>(handle) }));
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeRecorderStart(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| unsafe {
        with_handle::<TripRecorder, _, _>(handle, |r| r.start());
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeRecorderResume(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    snapshot_json: JString,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let snap_str = get_string(&mut env, snapshot_json);
        if let Ok(snap) = serde_json::from_str::<TripSnapshot>(&snap_str) {
            unsafe {
                with_handle::<TripRecorder, _, _>(handle, |r| r.resume(&snap));
            }
        }
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeRecorderOnSpeed(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    time_ms: jlong,
    speed_kmh: jdouble,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let ev = unsafe {
            with_handle::<TripRecorder, _, _>(handle, |r| r.on_speed(time_ms, speed_kmh))
                .flatten()
        };
        to_opt_jstring(&env, ev.and_then(|e| serde_json::to_string(&e).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeRecorderOnSteering(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    time_ms: jlong,
    angle_deg: jdouble,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let ev = unsafe {
            with_handle::<TripRecorder, _, _>(handle, |r| r.on_steering(time_ms, angle_deg))
                .flatten()
        };
        to_opt_jstring(&env, ev.and_then(|e| serde_json::to_string(&e).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeRecorderOnBrakePedal(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    time_ms: jlong,
    pressed: jboolean,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| unsafe {
        with_handle::<TripRecorder, _, _>(handle, |r| r.on_brake_pedal(time_ms, from_jbool(pressed)));
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeRecorderOnSpeedLimit(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    time_ms: jlong,
    limit_kmh: jint,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| unsafe {
        with_handle::<TripRecorder, _, _>(handle, |r| r.on_speed_limit(time_ms, limit_kmh));
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeRecorderStateJson(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let payload = unsafe {
            with_handle::<TripRecorder, _, _>(handle, |r| {
                json!({
                    "active": r.is_active(),
                    "distanceKm": r.current_distance_km(),
                    "harshAccel": r.current_harsh_accel(),
                    "harshBrake": r.current_harsh_brake(),
                    "harshSteer": r.current_harsh_steer(),
                    "safetyScore": r.current_safety_score(),
                    "ecoScore": r.current_eco_score(),
                    "totalScore": r.current_total_score(),
                    "ecoBreakdown": r.current_eco_breakdown(),
                    "roadKm": r.current_road_km(),
                    "snapshot": r.snapshot(),
                })
            })
        };
        to_opt_jstring(&env, payload.and_then(|v| serde_json::to_string(&v).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeRecorderFinish(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    start_wall_ms: jlong,
    end_wall_ms: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let res = unsafe {
            with_handle::<TripRecorder, _, _>(handle, |r| r.finish(start_wall_ms, end_wall_ms))
        };
        to_opt_jstring(&env, res.and_then(|v| serde_json::to_string(&v).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

// ============================================================================
// Stateful Handle: StampBook
// ============================================================================

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeStampBookNew(
    mut env: JNIEnv,
    _class: JClass,
    initial_json: JString,
    radius_m: jdouble,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let initial: Vec<Stamp> = get_opt_string(&mut env, initial_json)
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default();
        box_handle(StampBook::new(initial, radius_m))
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeStampBookFree(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| unsafe { free_handle::<StampBook>(handle) }));
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeStampBookList(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let stamps = unsafe { with_handle::<StampBook, _, _>(handle, |b| b.stamps().to_vec()) }
            .unwrap_or_default();
        to_json_jstring(&env, &stamps)
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeStampBookFindNearby(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    lat: jdouble,
    lon: jdouble,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let found = unsafe {
            with_handle::<StampBook, _, _>(handle, |b| b.find_nearby(GeoPoint::new(lat, lon)).cloned())
                .flatten()
        };
        to_opt_jstring(&env, found.and_then(|s| serde_json::to_string(&s).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeStampBookTryAdd(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    lat: jdouble,
    lon: jdouble,
    time_ms: jlong,
    score: jint,
    name: JString,
    perfect: jboolean,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let opt_name = get_opt_string(&mut env, name);
        let added = unsafe {
            with_handle::<StampBook, _, _>(handle, |b| {
                b.try_add(
                    GeoPoint::new(lat, lon),
                    time_ms,
                    score,
                    opt_name.as_deref(),
                    from_jbool(perfect),
                )
            })
            .flatten()
        };
        to_opt_jstring(&env, added.and_then(|s| serde_json::to_string(&s).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeStampBookRename(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    id: jint,
    name: JString,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let n = get_string(&mut env, name);
        let renamed = unsafe {
            with_handle::<StampBook, _, _>(handle, |b| b.rename(id, &n)).flatten()
        };
        to_opt_jstring(&env, renamed.and_then(|s| serde_json::to_string(&s).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeStampBookUpdate(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    id: jint,
    name: JString,
    memo: JString,
    tags_json: JString,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let n = get_string(&mut env, name);
        let m = get_string(&mut env, memo);
        let tags: Vec<String> =
            serde_json::from_str(&get_string(&mut env, tags_json)).unwrap_or_default();
        let updated = unsafe {
            with_handle::<StampBook, _, _>(handle, |b| b.update(id, &n, &m, &tags)).flatten()
        };
        to_opt_jstring(&env, updated.and_then(|s| serde_json::to_string(&s).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeStampBookRemove(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    id: jint,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let removed =
            unsafe { with_handle::<StampBook, _, _>(handle, |b| b.remove(id)) }.unwrap_or(false);
        to_jbool(removed)
    }))
    .unwrap_or(JNI_FALSE)
}

// ============================================================================
// Stateful Handle: DiagnosticsBoard & SpeedDriveDetector
// ============================================================================

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeDiagNew(
    _env: JNIEnv,
    _class: JClass,
    min_interval_ms: jlong,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| box_handle(DiagnosticsBoard::new(min_interval_ms))))
        .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeDiagFree(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| unsafe { free_handle::<DiagnosticsBoard>(handle) }));
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeDiagCurrent(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let map = unsafe { with_handle::<DiagnosticsBoard, _, _>(handle, |b| b.current()) };
        to_opt_jstring(&env, map.and_then(|m| serde_json::to_string(&m).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeDiagSet(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    channel_name: JString,
    status_name: JString,
    detail: JString,
    now_ms: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(ch) = parse_channel(&get_string(&mut env, channel_name)) else {
            return ptr::null_mut();
        };
        let Some(st) = parse_channel_status(&get_string(&mut env, status_name)) else {
            return ptr::null_mut();
        };
        let det = get_string(&mut env, detail);
        let changed = unsafe {
            with_handle::<DiagnosticsBoard, _, _>(handle, |b| {
                let updated = match st {
                    ChannelStatus::Ok => b.ok(ch, det, now_ms),
                    ChannelStatus::Waiting => b.waiting(ch, det, now_ms),
                    ChannelStatus::Unavailable => b.unavailable(ch, det, now_ms),
                };
                if updated {
                    Some(b.current())
                } else {
                    None
                }
            })
            .flatten()
        };
        to_opt_jstring(&env, changed.and_then(|m| serde_json::to_string(&m).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeSpeedDetectorNew(
    _env: JNIEnv,
    _class: JClass,
    start_kmh: jdouble,
    stop_kmh: jdouble,
    park_after_ms: jlong,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        box_handle(SpeedDriveDetector::new(start_kmh, stop_kmh, park_after_ms))
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeSpeedDetectorFree(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| unsafe {
        free_handle::<SpeedDriveDetector>(handle)
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeSpeedDetectorOnSpeed(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    time_ms: jlong,
    kmh: jdouble,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let mode = unsafe {
            with_handle::<SpeedDriveDetector, _, _>(handle, |d| d.on_speed(time_ms, kmh))
        };
        let name = mode
            .and_then(|m| serde_json::to_value(m).ok())
            .and_then(|v| v.as_str().map(String::from))
            .unwrap_or_else(|| "UNKNOWN".to_string());
        to_jstring(&env, &name)
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeSpeedDetectorOnTick(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    time_ms: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let mode = unsafe { with_handle::<SpeedDriveDetector, _, _>(handle, |d| d.on_tick(time_ms)) };
        let name = mode
            .and_then(|m| serde_json::to_value(m).ok())
            .and_then(|v| v.as_str().map(String::from))
            .unwrap_or_else(|| "UNKNOWN".to_string());
        to_jstring(&env, &name)
    }))
    .unwrap_or(ptr::null_mut())
}

// ============================================================================
// Storage
// ============================================================================

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeStorageLoad(
    mut env: JNIEnv,
    _class: JClass,
    dir_path: JString,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let dir = get_string(&mut env, dir_path);
        let data = Storage::new(dir).load();
        to_json_jstring(&env, &data)
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeStorageSave(
    mut env: JNIEnv,
    _class: JClass,
    dir_path: JString,
    data_json: JString,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let dir = get_string(&mut env, dir_path);
        let json_str = get_string(&mut env, data_json);
        let Ok(data) = serde_json::from_str::<SavedData>(&json_str) else {
            return JNI_FALSE;
        };
        to_jbool(Storage::new(dir).save(&data).is_ok())
    }))
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeStorageLoadOngoing(
    mut env: JNIEnv,
    _class: JClass,
    dir_path: JString,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let dir = get_string(&mut env, dir_path);
        let ongoing = Storage::new(dir).load_ongoing();
        to_opt_jstring(&env, ongoing.and_then(|o| serde_json::to_string(&o).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeStorageSaveOngoing(
    mut env: JNIEnv,
    _class: JClass,
    dir_path: JString,
    ongoing_json: JString,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let dir = get_string(&mut env, dir_path);
        let json_str = get_string(&mut env, ongoing_json);
        let Ok(ongoing) = serde_json::from_str::<OngoingTrip>(&json_str) else {
            return JNI_FALSE;
        };
        to_jbool(Storage::new(dir).save_ongoing(&ongoing).is_ok())
    }))
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeStorageClearOngoing(
    mut env: JNIEnv,
    _class: JClass,
    dir_path: JString,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let dir = get_string(&mut env, dir_path);
        Storage::new(dir).clear_ongoing();
    }));
}

// ============================================================================
// Stateful Handle: FakeVehicleEngine
// ============================================================================

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeFakeNew(
    _env: JNIEnv,
    _class: JClass,
    real_ms: jlong,
    wall_ms: jlong,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        box_handle(FakeVehicleEngine::new(real_ms, wall_ms))
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeFakeFree(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| unsafe {
        free_handle::<FakeVehicleEngine>(handle)
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeFakeTick(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    real_ms: jlong,
    wall_ms: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let out = unsafe {
            with_handle::<FakeVehicleEngine, _, _>(handle, |f| f.tick(real_ms, wall_ms))
        };
        to_opt_jstring(&env, out.and_then(|v| serde_json::to_string(&v).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeFakeReadEnergy(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    wall_ms: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let out = unsafe {
            with_handle::<FakeVehicleEngine, _, _>(handle, |f| {
                let (energy, diagnostics) = f.read_energy(wall_ms);
                json!({ "energy": energy, "diagnostics": diagnostics })
            })
        };
        to_opt_jstring(&env, out.and_then(|v| serde_json::to_string(&v).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeFakeSetSpeed(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    kmh: jdouble,
    real_ms: jlong,
    wall_ms: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let out = unsafe {
            with_handle::<FakeVehicleEngine, _, _>(handle, |f| f.set_speed(kmh, real_ms, wall_ms))
        };
        to_opt_jstring(&env, out.and_then(|v| serde_json::to_string(&v).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeFakeSetParked(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    parked: jboolean,
    wall_ms: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let out = unsafe {
            with_handle::<FakeVehicleEngine, _, _>(handle, |f| {
                f.set_parked(from_jbool(parked), wall_ms)
            })
        };
        to_opt_jstring(&env, out.and_then(|v| serde_json::to_string(&v).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeFakeSetLocation(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    has_point: jboolean,
    lat: jdouble,
    lon: jdouble,
    name: JString,
    address: JString,
    wall_ms: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let pt = if from_jbool(has_point) {
            Some(GeoPoint::new(lat, lon))
        } else {
            None
        };
        let n = get_opt_string(&mut env, name);
        let a = get_opt_string(&mut env, address);
        let out = unsafe {
            with_handle::<FakeVehicleEngine, _, _>(handle, |f| f.set_location(pt, n, a, wall_ms))
        };
        to_opt_jstring(&env, out.and_then(|v| serde_json::to_string(&v).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeFakeJerkSteering(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    real_ms: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let out = unsafe {
            with_handle::<FakeVehicleEngine, _, _>(handle, |f| f.jerk_steering(real_ms))
        };
        to_opt_jstring(&env, out.and_then(|v| serde_json::to_string(&v).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeFakeSetDemoActive(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    active: jboolean,
    real_ms: jlong,
    wall_ms: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let out = unsafe {
            with_handle::<FakeVehicleEngine, _, _>(handle, |f| {
                f.set_demo_active(from_jbool(active), real_ms, wall_ms)
            })
        };
        to_opt_jstring(&env, out.and_then(|v| serde_json::to_string(&v).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeFakeCurrentState(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let out = unsafe { with_handle::<FakeVehicleEngine, _, _>(handle, |f| f.current_state()) };
        to_opt_jstring(&env, out.and_then(|v| serde_json::to_string(&v).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeFakeDemoScriptJson(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        to_json_jstring(&env, &FakeVehicleEngine::demo_script())
    }))
    .unwrap_or(ptr::null_mut())
}

// ============================================================================
// Stateful Handle: KkwongalEngine (ViewModel Core Brain)
// ============================================================================

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEngineNew(
    mut env: JNIEnv,
    _class: JClass,
    storage_dir: JString,
    remote_league: jboolean,
    voice_guide: jboolean,
    now_ms: jlong,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let dir = get_string(&mut env, storage_dir);
        box_handle(KkwongalEngine::new(
            dir,
            from_jbool(remote_league),
            from_jbool(voice_guide),
            now_ms,
        ))
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEngineFree(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| unsafe {
        free_handle::<KkwongalEngine>(handle)
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEngineSnapshot(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let snap = unsafe { with_handle::<KkwongalEngine, _, _>(handle, |e| e.snapshot()) };
        to_opt_jstring(&env, snap.and_then(|v| serde_json::to_string(&v).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEngineSetVoiceGuide(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    on: jboolean,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let snap = unsafe {
            with_handle::<KkwongalEngine, _, _>(handle, |e| e.set_voice_guide(from_jbool(on)))
        };
        to_opt_jstring(&env, snap.and_then(|v| serde_json::to_string(&v).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEngineSetSimulation(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    enabled: jboolean,
    fallback_notice: JString,
    now_ms: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let notice = get_opt_string(&mut env, fallback_notice);
        let snap = unsafe {
            with_handle::<KkwongalEngine, _, _>(handle, |e| {
                e.set_simulation_mode(from_jbool(enabled), notice, now_ms)
            })
        };
        to_opt_jstring(&env, snap.and_then(|v| serde_json::to_string(&v).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEngineClearForDemo(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let snap = unsafe { with_handle::<KkwongalEngine, _, _>(handle, |e| e.clear_for_demo()) };
        to_opt_jstring(&env, snap.and_then(|v| serde_json::to_string(&v).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEngineRefreshLocalLeague(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    now_ms: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let snap = unsafe {
            with_handle::<KkwongalEngine, _, _>(handle, |e| e.refresh_local_league(now_ms))
        };
        to_opt_jstring(&env, snap.and_then(|v| serde_json::to_string(&v).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEngineOnSpeed(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    mono_ms: jlong,
    speed_kmh: jdouble,
    wall_ms: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let step = unsafe {
            with_handle::<KkwongalEngine, _, _>(handle, |e| e.on_speed(mono_ms, speed_kmh, wall_ms))
        };
        to_opt_jstring(&env, step.and_then(|v| serde_json::to_string(&v).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEngineOnSteering(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    mono_ms: jlong,
    angle_deg: jdouble,
    wall_ms: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let step = unsafe {
            with_handle::<KkwongalEngine, _, _>(handle, |e| {
                e.on_steering(mono_ms, angle_deg, wall_ms)
            })
        };
        to_opt_jstring(&env, step.and_then(|v| serde_json::to_string(&v).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEngineOnBrakePedal(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    mono_ms: jlong,
    pressed: jboolean,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| unsafe {
        with_handle::<KkwongalEngine, _, _>(handle, |e| {
            e.on_brake_pedal(mono_ms, from_jbool(pressed))
        });
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEngineOnSpeedLimit(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    mono_ms: jlong,
    limit_kmh: jint,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| unsafe {
        with_handle::<KkwongalEngine, _, _>(handle, |e| e.on_speed_limit(mono_ms, limit_kmh));
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEngineOnDriveState(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    state_name: JString,
    wall_ms: jlong,
    has_loc: jboolean,
    lat: jdouble,
    lon: jdouble,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let state = parse_drive_state(&get_string(&mut env, state_name));
        let loc = if from_jbool(has_loc) {
            Some(GeoPoint::new(lat, lon))
        } else {
            None
        };
        let step = unsafe {
            with_handle::<KkwongalEngine, _, _>(handle, |e| e.on_drive_state(state, wall_ms, loc))
        };
        to_opt_jstring(&env, step.and_then(|v| serde_json::to_string(&v).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEngineSetStartEnergy(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    energy_json: JString,
    wall_ms: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let energy: Option<EnergySnapshot> =
            get_opt_string(&mut env, energy_json).and_then(|s| serde_json::from_str(&s).ok());
        unsafe {
            with_handle::<KkwongalEngine, _, _>(handle, |e| e.set_start_energy(energy, wall_ms));
        }
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEngineCompleteFinish(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    end_energy_json: JString,
    has_arrival: jboolean,
    arrival_name: JString,
    arrival_time_ms: jlong,
    has_arrival_point: jboolean,
    arrival_lat: jdouble,
    arrival_lon: jdouble,
    address: JString,
    has_addr_time: jboolean,
    addr_time: jlong,
    now_ms: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let end_energy: Option<EnergySnapshot> =
            get_opt_string(&mut env, end_energy_json).and_then(|s| serde_json::from_str(&s).ok());
        let arrival = if from_jbool(has_arrival) {
            Some(ArrivedPlace {
                name: get_string(&mut env, arrival_name),
                wall_time_ms: arrival_time_ms,
                point: if from_jbool(has_arrival_point) {
                    Some(GeoPoint::new(arrival_lat, arrival_lon))
                } else {
                    None
                },
            })
        } else {
            None
        };
        let addr = get_opt_string(&mut env, address);
        let addr_ts = if from_jbool(has_addr_time) {
            Some(addr_time)
        } else {
            None
        };
        let step = unsafe {
            with_handle::<KkwongalEngine, _, _>(handle, |e| {
                e.complete_finish(end_energy, arrival.as_ref(), addr.as_deref(), addr_ts, now_ms)
            })
        };
        to_opt_jstring(&env, step.and_then(|v| serde_json::to_string(&v).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEngineSaveOngoing(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    wall_ms: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| unsafe {
        with_handle::<KkwongalEngine, _, _>(handle, |e| e.save_ongoing(wall_ms));
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEngineSaveStamp(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    id: jint,
    name: JString,
    memo: JString,
    tags_json: JString,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let n = get_string(&mut env, name);
        let m = get_string(&mut env, memo);
        let tags: Vec<String> =
            serde_json::from_str(&get_string(&mut env, tags_json)).unwrap_or_default();
        let snap = unsafe {
            with_handle::<KkwongalEngine, _, _>(handle, |e| e.save_stamp(id, &n, &m, &tags))
        };
        to_opt_jstring(&env, snap.and_then(|v| serde_json::to_string(&v).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEngineOpenTrip(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    start_ms: jlong,
    end_ms: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let snap = unsafe {
            with_handle::<KkwongalEngine, _, _>(handle, |e| e.open_trip(start_ms, end_ms))
        };
        to_opt_jstring(&env, snap.and_then(|v| serde_json::to_string(&v).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEngineDeleteTrip(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    start_ms: jlong,
    end_ms: jlong,
    now_ms: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let snap = unsafe {
            with_handle::<KkwongalEngine, _, _>(handle, |e| e.delete_trip(start_ms, end_ms, now_ms))
        };
        to_opt_jstring(&env, snap.and_then(|v| serde_json::to_string(&v).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEngineCloseTrip(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let snap = unsafe { with_handle::<KkwongalEngine, _, _>(handle, |e| e.close_trip()) };
        to_opt_jstring(&env, snap.and_then(|v| serde_json::to_string(&v).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEngineDismissResult(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let snap = unsafe { with_handle::<KkwongalEngine, _, _>(handle, |e| e.dismiss_result()) };
        to_opt_jstring(&env, snap.and_then(|v| serde_json::to_string(&v).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEngineSetNotice(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    notice: JString,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let n = get_opt_string(&mut env, notice);
        let snap = unsafe { with_handle::<KkwongalEngine, _, _>(handle, |e| e.set_notice(n)) };
        to_opt_jstring(&env, snap.and_then(|v| serde_json::to_string(&v).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEngineNavigateMessage(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    stamp_id: jint,
    sent: jboolean,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let snap = unsafe {
            with_handle::<KkwongalEngine, _, _>(handle, |e| {
                e.navigate_message(stamp_id, from_jbool(sent))
            })
        };
        to_opt_jstring(&env, snap.and_then(|v| serde_json::to_string(&v).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEngineValidateToken(
    mut env: JNIEnv,
    _class: JClass,
    token: JString,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let t = get_string(&mut env, token);
        to_opt_jstring(&env, KkwongalEngine::validate_token(&t))
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_kkwongal_drive_rust_RustBridge_nativeEngineLatestTripUpload(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    now_ms: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let payload = unsafe {
            with_handle::<KkwongalEngine, _, _>(handle, |e| match e.latest_trip_for_upload(now_ms) {
                Ok(Some(dto)) => json!({ "status": "ok", "trip": dto }),
                Ok(None) => json!({ "status": "empty" }),
                Err(msg) => json!({ "status": "error", "message": msg }),
            })
        };
        to_opt_jstring(&env, payload.and_then(|v| serde_json::to_string(&v).ok()))
    }))
    .unwrap_or(ptr::null_mut())
}
