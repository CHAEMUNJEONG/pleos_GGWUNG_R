use std::collections::BTreeSet;
use std::io::{Read, Write};
use std::net::TcpListener;
use std::thread;

use chrono::{TimeZone, Utc};
use chrono_tz::Asia::Seoul;
use kkwongal_core::coaching::Coaching;
use kkwongal_core::diagnostics::{
    Channel, ChannelStatus, DiagnosticsBoard, DriveMode, SpeedDriveDetector,
};
use kkwongal_core::eco_habits::{EcoBreakdown, EcoConfig, EcoItem, RoadKm};
use kkwongal_core::egg_levels::EggLevels;
use kkwongal_core::energy::{Efficiency, EnergyKind, EnergyMeter, EnergySnapshot};
use kkwongal_core::energy_text::energy_text;
use kkwongal_core::engine::KkwongalEngine;
use kkwongal_core::fake_vehicle::{DemoAction, FakeVehicleEngine};
use kkwongal_core::korea_outline;
use kkwongal_core::league::{League, LeagueConfig};
use kkwongal_core::league_client::{
    ApiFailure, LeagueClient, LeagueReply, TripUploadDto, UploadReply,
};
use kkwongal_core::models::{Badge, GeoPoint, RoadType, Stamp, TripResult};
use kkwongal_core::stamp_book::{distance_meters, BadgeRules, StampBook};
use kkwongal_core::stamp_name_resolver::{ArrivedPlace, StampNameResolver};
use kkwongal_core::storage::{SavedData, Storage};
use kkwongal_core::trip_continuation::{OngoingTrip, TripContinuation};
use kkwongal_core::trip_recorder::{
    HarshEvent, HarshType, LiveEvent, ScoreCalculator, ScoreConfig, TripRecorder,
};

fn feed(recorder: &mut TripRecorder, start_ms: i64, speeds: &[f64]) -> i64 {
    let mut t = start_ms;
    for &s in speeds {
        recorder.on_speed(t, s);
        t += 200;
    }
    t
}

fn ramp(from: f64, to: f64, seconds: f64) -> Vec<f64> {
    let steps = (seconds * 5.0) as usize;
    (0..=steps)
        .map(|i| from + (to - from) * (i as f64) / (steps as f64))
        .collect()
}

fn hold(speed: f64, seconds: f64) -> Vec<f64> {
    vec![speed; (seconds * 5.0) as usize]
}

fn concat(parts: &[Vec<f64>]) -> Vec<f64> {
    let mut out = Vec::new();
    for p in parts {
        out.extend_from_slice(p);
    }
    out
}

fn at_seoul(year: i32, month: u32, day: u32, hour: u32) -> i64 {
    Seoul
        .with_ymd_and_hms(year, month, day, hour, 0, 0)
        .single()
        .unwrap()
        .with_timezone(&Utc)
        .timestamp_millis()
}

fn trip_at(end_ms: i64, score: i32, km: f64) -> TripResult {
    TripResult::new(end_ms - 600_000, end_ms, km, 0, 0, 60.0, score)
}

#[test]
fn gentle_drive_has_no_events_and_full_score() {
    let mut r = TripRecorder::default();
    r.start();
    let speeds = concat(&[
        ramp(0.0, 60.0, 20.0),
        hold(60.0, 600.0),
        ramp(60.0, 0.0, 20.0),
    ]);
    feed(&mut r, 0, &speeds);
    let result = r.finish(0, 640_000);
    assert_eq!(0, result.harsh_accel_count);
    assert_eq!(0, result.harsh_brake_count);
    assert_eq!(100, result.score);
    assert!((result.distance_km - 10.33).abs() <= 0.05);
    assert!((result.max_speed_kmh - 60.0).abs() <= 0.001);
}

#[test]
fn harsh_accel_and_brake_are_counted_once_each() {
    let mut r = TripRecorder::default();
    r.start();
    let speeds = concat(&[
        hold(20.0, 5.0),
        ramp(20.0, 60.0, 3.0),
        hold(60.0, 30.0),
        ramp(60.0, 10.0, 4.0),
        hold(10.0, 5.0),
    ]);
    feed(&mut r, 0, &speeds);
    let result = r.finish(0, 47_000);
    assert_eq!(1, result.harsh_accel_count);
    assert_eq!(1, result.harsh_brake_count);
    assert!(result.score < 100);
}

#[test]
fn two_separate_brakes_are_counted_twice() {
    let mut r = TripRecorder::default();
    r.start();
    let speeds = concat(&[
        hold(80.0, 5.0),
        ramp(80.0, 40.0, 3.0),
        hold(40.0, 5.0),
        ramp(40.0, 80.0, 20.0),
        ramp(80.0, 30.0, 4.0),
        hold(30.0, 3.0),
    ]);
    feed(&mut r, 0, &speeds);
    let result = r.finish(0, 40_000);
    assert_eq!(2, result.harsh_brake_count);
    assert_eq!(0, result.harsh_accel_count);
}

#[test]
fn long_gap_is_not_treated_as_harsh_event() {
    let mut r = TripRecorder::default();
    r.start();
    r.on_speed(0, 10.0);
    r.on_speed(200, 10.0);
    r.on_speed(60_000, 90.0);
    r.on_speed(60_200, 90.0);
    r.on_speed(61_200, 90.0);
    let result = r.finish(0, 61_200);
    assert_eq!(0, result.harsh_accel_count);
    assert!(result.distance_km < 0.1);
}

#[test]
fn score_is_normalized_by_distance() {
    let cfg = ScoreConfig::default();
    assert_eq!(90, ScoreCalculator::score(5.0, 0, 2, &cfg, 0));
    assert_eq!(98, ScoreCalculator::score(50.0, 0, 2, &cfg, 0));
    assert_eq!(95, ScoreCalculator::score(1.0, 0, 1, &cfg, 0));
    assert_eq!(95, ScoreCalculator::score(10.0, 0, 1, &cfg, 0));
    assert_eq!(0, ScoreCalculator::score(1.0, 30, 30, &cfg, 0));
}

#[test]
fn stamp_only_for_new_places() {
    let mut book = StampBook::default();
    let gangneung = GeoPoint::new(37.7519, 128.8761);
    let near_gangneung = GeoPoint::new(37.7530, 128.8770);
    let busan = GeoPoint::new(35.1587, 129.1604);

    let first = book.try_add(gangneung, 1, 92, Some("강릉 카페"), false).unwrap();
    assert_eq!("강릉 카페", first.name);
    assert!(book.try_add(near_gangneung, 2, 80, None, false).is_none());
    let second = book.try_add(busan, 3, 85, None, false).unwrap();
    assert_eq!("새로운 장소 2", second.name);
    assert_eq!(2, book.stamps().len());

    assert_eq!("해운대", book.rename(second.id, " 해운대 ").unwrap().name);
    assert!(book.rename(99, "없음").is_none());
}

#[test]
fn distance_between_seoul_and_busan_is_about_325km() {
    let seoul = GeoPoint::new(37.5665, 126.9780);
    let busan = GeoPoint::new(35.1796, 129.0756);
    assert!((distance_meters(seoul, busan) - 325_000.0).abs() <= 5_000.0);
}

#[test]
fn badges() {
    let trip = TripResult::new(0, 600_000, 10.0, 0, 0, 60.0, 100);
    let mut rough = trip.clone();
    rough.harsh_brake_count = 3;
    rough.score = 85;
    let stamps: Vec<Stamp> = (1..=5)
        .map(|i| Stamp {
            id: i,
            name: format!("s{i}"),
            latitude: 35.0 + i as f64,
            longitude: 127.0,
            time_ms: 0,
            score: 90,
            memo: String::new(),
            tags: Vec::new(),
            perfect: false,
        })
        .collect();

    assert_eq!(BTreeSet::new(), BadgeRules::evaluate(&[rough], &[]));
    assert_eq!(
        BTreeSet::from([Badge::FirstStamp, Badge::Perfect]),
        BadgeRules::evaluate(&[trip.clone()], &stamps[..1])
    );
    assert_eq!(
        BTreeSet::from(Badge::ALL),
        BadgeRules::evaluate(&[trip.clone(), trip.clone(), trip], &stamps)
    );
}

#[test]
fn eco_score_follows_acceleration_energy() {
    let cfg = ScoreConfig::default();
    assert_eq!(100, ScoreCalculator::eco(0.05, &cfg));
    assert_eq!(100, ScoreCalculator::eco(0.20, &cfg));
    assert_eq!(50, ScoreCalculator::eco(0.40, &cfg));
    assert_eq!(0, ScoreCalculator::eco(0.60, &cfg));
    assert_eq!(0, ScoreCalculator::eco(1.50, &cfg));
    assert_eq!(70, ScoreCalculator::total(100, 50, &cfg));
    assert_eq!(80, ScoreCalculator::total(50, 100, &cfg));
}

#[test]
fn stop_and_go_driving_gets_lower_eco_than_steady_driving() {
    let mut steady = TripRecorder::default();
    steady.start();
    let s = concat(&[
        ramp(0.0, 50.0, 15.0),
        hold(50.0, 300.0),
        ramp(50.0, 0.0, 15.0),
    ]);
    feed(&mut steady, 0, &s);
    let steady_result = steady.finish(0, 330_000);

    let mut stop_go = TripRecorder::default();
    stop_go.start();
    let cycle = concat(&[
        ramp(0.0, 50.0, 8.0),
        hold(50.0, 6.0),
        ramp(50.0, 0.0, 8.0),
        hold(0.0, 3.0),
    ]);
    let all: Vec<f64> = (0..13).flat_map(|_| cycle.iter().copied()).collect();
    feed(&mut stop_go, 0, &all);
    let stop_go_result = stop_go.finish(0, 325_000);

    assert_eq!(0, stop_go_result.harsh_accel_count);
    assert_eq!(100, steady_result.eco_score);
    assert_eq!(100, stop_go_result.safety_score);
    assert!(stop_go_result.eco_score < 40);
    assert!(stop_go_result.score < steady_result.score);
}

#[test]
fn week_starts_on_monday_midnight() {
    let monday = at_seoul(2026, 11, 9, 0);
    assert_eq!(monday, League::week_start_ms(at_seoul(2026, 11, 14, 21), "Asia/Seoul"));
    assert_eq!(monday, League::week_start_ms(monday, "Asia/Seoul"));
    assert_eq!(monday, League::week_start_ms(at_seoul(2026, 11, 15, 23), "Asia/Seoul"));
    assert_eq!(
        at_seoul(2026, 11, 16, 0),
        League::week_start_ms(at_seoul(2026, 11, 16, 0), "Asia/Seoul")
    );
}

#[test]
fn league_ranks_by_average_score() {
    let now = at_seoul(2026, 11, 14, 12);
    let cfg = LeagueConfig::default();

    let empty = League::standing(&[], now, "Asia/Seoul", &cfg, false);
    assert!(empty.my_rank.is_none());
    assert_eq!(19, empty.entries.len());
    assert_eq!(0, empty.reward_points);

    let best = League::standing(&[trip_at(now, 100, 200.0)], now, "Asia/Seoul", &cfg, false);
    assert_eq!(Some(1), best.my_rank);
    assert_eq!(20, best.entries.len());
    assert_eq!(500, best.reward_points);
    assert!(best.entries[0].is_me);

    let worst = League::standing(&[trip_at(now, 0, 200.0)], now, "Asia/Seoul", &cfg, false);
    assert_eq!(Some(20), worst.my_rank);
    assert_eq!(50, worst.reward_points);

    let mixed = League::standing(
        &[trip_at(now, 100, 30.0), trip_at(now, 0, 10.0)],
        now,
        "Asia/Seoul",
        &cfg,
        false,
    );
    assert!((mixed.my_average.unwrap() - 75.0).abs() <= 0.001);
    assert_eq!(2, mixed.my_trip_count);
    assert!((mixed.my_league_score.unwrap() - 76.7).abs() <= 0.001);

    let same_week = League::standing(
        &[trip_at(now, 100, 200.0)],
        at_seoul(2026, 11, 10, 12),
        "Asia/Seoul",
        &cfg,
        false,
    );
    assert_eq!(best.entries, same_week.entries);
}

#[test]
fn league_needs_minimum_distance_and_ignores_other_weeks() {
    let now = at_seoul(2026, 11, 14, 12);
    let cfg = LeagueConfig::default();
    let short = League::standing(&[trip_at(now, 100, 0.5)], now, "Asia/Seoul", &cfg, false);
    assert!(short.my_rank.is_none());
    assert_eq!(0, short.my_trip_count);
    assert!((short.my_distance_km - 0.0).abs() <= 0.001);

    let last_week = League::standing(
        &[trip_at(at_seoul(2026, 11, 5, 12), 100, 10.0)],
        now,
        "Asia/Seoul",
        &cfg,
        false,
    );
    assert!(last_week.my_rank.is_none());
    assert_eq!(0, last_week.my_trip_count);
}

#[test]
fn reward_tiers_and_earned_points() {
    let cfg = LeagueConfig::default();
    assert_eq!(500, League::reward_for(2, 20, &cfg));
    assert_eq!(200, League::reward_for(3, 20, &cfg));
    assert_eq!(200, League::reward_for(6, 20, &cfg));
    assert_eq!(50, League::reward_for(7, 20, &cfg));

    let now = at_seoul(2026, 11, 14, 12);
    let trips = vec![
        trip_at(at_seoul(2026, 10, 28, 12), 100, 200.0),
        trip_at(at_seoul(2026, 11, 5, 12), 0, 200.0),
        trip_at(now, 100, 200.0),
    ];
    assert_eq!(550, League::earned_points(&trips, now, "Asia/Seoul", &cfg, false));
    assert_eq!(
        0,
        League::earned_points(&[trip_at(now, 100, 10.0)], now, "Asia/Seoul", &cfg, false)
    );
}

#[test]
fn stamp_memo_and_tags() {
    let mut book = StampBook::default();
    let stamp = book
        .try_add(GeoPoint::new(37.7519, 128.8761), 1, 92, None, false)
        .unwrap();
    assert_eq!("", stamp.memo);
    assert!(stamp.tags.is_empty());

    let updated = book
        .update(
            stamp.id,
            " 강릉 카페 ",
            "  바다가 보이는 주차장이 좋았어요 ",
            &[
                "다시 올래요".into(),
                "없는 태그".into(),
                "주차가 편해요".into(),
                "다시 올래요".into(),
            ],
        )
        .unwrap();
    assert_eq!("강릉 카페", updated.name);
    assert_eq!("바다가 보이는 주차장이 좋았어요", updated.memo);
    assert_eq!(vec!["주차가 편해요", "다시 올래요"], updated.tags);
    assert_eq!(&updated, &book.stamps()[0]);

    let long_memo = "가".repeat(300);
    let again = book.update(stamp.id, "  ", &long_memo, &[]).unwrap();
    assert_eq!("강릉 카페", again.name);
    assert_eq!(200, again.memo.chars().count());
    assert!(again.tags.is_empty());

    book.update(stamp.id, "강릉", "좋았어요", &["경치가 좋아요".into()]);
    let renamed = book.rename(stamp.id, "강릉 바다").unwrap();
    assert_eq!("좋았어요", renamed.memo);
    assert_eq!(vec!["경치가 좋아요"], renamed.tags);
    assert!(book.update(99, "x", "y", &[]).is_none());
}

#[test]
fn harsh_events_are_reported_live_with_penalty() {
    let mut r = TripRecorder::default();
    r.start();
    let mut events = Vec::new();
    let mut time = 0_i64;
    fn drive(r: &mut TripRecorder, events: &mut Vec<HarshEvent>, time: &mut i64, speeds: &[f64]) {
        for &v in speeds {
            if let Some(ev) = r.on_speed(*time, v) {
                events.push(ev);
            }
            *time += 200;
        }
    }
    drive(&mut r, &mut events, &mut time, &hold(30.0, 5.0));
    assert_eq!(100, r.current_safety_score());
    drive(
        &mut r,
        &mut events,
        &mut time,
        &concat(&[ramp(30.0, 60.0, 2.0), hold(60.0, 5.0)]),
    );
    drive(
        &mut r,
        &mut events,
        &mut time,
        &concat(&[ramp(60.0, 20.0, 3.0), hold(20.0, 5.0)]),
    );

    let types: Vec<HarshType> = events.iter().map(|e| e.kind).collect();
    assert_eq!(vec![HarshType::Accel, HarshType::Brake], types);
    assert_eq!(4, events[0].penalty);
    assert_eq!(96, events[0].safety_after);
    assert_eq!(5, events[1].penalty);
    assert_eq!(91, events[1].safety_after);
    assert_eq!(91, r.current_safety_score());
    assert_eq!(91, r.finish(0, time).safety_score);
}

fn drive_with_steering(
    r: &mut TripRecorder,
    speed_kmh: f64,
    angles: &[f64],
    start_ms: i64,
) -> Vec<HarshEvent> {
    let mut events = Vec::new();
    let mut time = start_ms;
    for &a in angles {
        if let Some(ev) = r.on_speed(time, speed_kmh) {
            events.push(ev);
        }
        if let Some(ev) = r.on_steering(time, a) {
            events.push(ev);
        }
        time += 200;
    }
    events
}

fn steady_angle(angle: f64, seconds: f64) -> Vec<f64> {
    vec![angle; (seconds * 5.0) as usize]
}

#[test]
fn jerk_of_steering_wheel_counts_once_at_speed() {
    let mut r = TripRecorder::default();
    r.start();
    let angles = concat(&[
        steady_angle(0.0, 5.0),
        steady_angle(120.0, 0.6),
        steady_angle(0.0, 5.0),
    ]);
    let events = drive_with_steering(&mut r, 50.0, &angles, 0);
    assert_eq!(vec![HarshType::Steer], events.iter().map(|e| e.kind).collect::<Vec<_>>());
    assert_eq!(4, events[0].penalty);
    assert_eq!(96, r.current_safety_score());
    let result = r.finish(0, 10_000);
    assert_eq!(1, result.harsh_steer_count);
    assert_eq!(96, result.safety_score);
}

#[test]
fn gentle_curves_and_parking_maneuvers_are_not_harsh() {
    let mut curve = TripRecorder::default();
    curve.start();
    let gentle = concat(&[
        steady_angle(0.0, 3.0),
        (0..=20).map(|i| i as f64 * 2.0).collect(),
        steady_angle(40.0, 3.0),
    ]);
    assert!(drive_with_steering(&mut curve, 90.0, &gentle, 0).is_empty());

    let mut parking = TripRecorder::default();
    parking.start();
    let big_turn = concat(&[
        steady_angle(0.0, 3.0),
        steady_angle(360.0, 2.0),
        steady_angle(0.0, 3.0),
    ]);
    assert!(drive_with_steering(&mut parking, 10.0, &big_turn, 0).is_empty());
    assert_eq!(0, parking.current_harsh_steer());
}

#[test]
fn two_separate_swerves_count_twice_and_perfect_badge_needs_no_steer() {
    let mut r = TripRecorder::default();
    r.start();
    let swerve = concat(&[
        steady_angle(0.0, 3.0),
        steady_angle(-90.0, 0.6),
        steady_angle(0.0, 3.0),
    ]);
    let both = concat(&[swerve.clone(), swerve]);
    let events = drive_with_steering(&mut r, 60.0, &both, 0);
    assert_eq!(2, events.iter().filter(|e| e.kind == HarshType::Steer).count());

    let clean = TripResult::new(0, 600_000, 10.0, 0, 0, 60.0, 100);
    assert_eq!(
        BTreeSet::from([Badge::Perfect]),
        BadgeRules::evaluate(&[clean.clone()], &[])
    );
    let mut with_steer = clean;
    with_steer.harsh_steer_count = 1;
    assert_eq!(BTreeSet::new(), BadgeRules::evaluate(&[with_steer], &[]));
    assert_eq!(96, ScoreCalculator::score(10.0, 0, 0, &ScoreConfig::default(), 1));
}

#[test]
fn measures_ev_and_fuel_efficiency() {
    let ev = EnergyMeter::measure(
        Some(&EnergySnapshot {
            ev_battery_wh: Some(50_000.0),
            odometer_km: Some(1000.0),
            ..Default::default()
        }),
        Some(&EnergySnapshot {
            ev_battery_wh: Some(48_000.0),
            odometer_km: Some(1012.0),
            ..Default::default()
        }),
        11.5,
    )
    .unwrap();
    assert_eq!(EnergyKind::Ev, ev.kind);
    assert!((ev.value - 6.0).abs() <= 0.001);
    assert!((ev.distance_km - 12.0).abs() <= 0.001);

    let fuel = EnergyMeter::measure(
        Some(&EnergySnapshot {
            fuel_percent: Some(80.0),
            fuel_capacity_l: Some(50.0),
            ..Default::default()
        }),
        Some(&EnergySnapshot {
            fuel_percent: Some(78.0),
            fuel_capacity_l: Some(50.0),
            ..Default::default()
        }),
        15.0,
    )
    .unwrap();
    assert_eq!(EnergyKind::Fuel, fuel.kind);
    assert!((fuel.value - 15.0).abs() <= 0.001);

    assert!(EnergyMeter::measure(
        Some(&EnergySnapshot {
            fuel_percent: Some(80.0),
            fuel_capacity_l: Some(50.0),
            ..Default::default()
        }),
        Some(&EnergySnapshot {
            fuel_percent: Some(80.0),
            fuel_capacity_l: Some(50.0),
            ..Default::default()
        }),
        3.0
    )
    .is_none());
    assert!(EnergyMeter::measure(
        Some(&EnergySnapshot {
            ev_battery_wh: Some(50_000.0),
            ..Default::default()
        }),
        Some(&EnergySnapshot {
            ev_battery_wh: Some(49_990.0),
            ..Default::default()
        }),
        2.0
    )
    .is_none());
    assert!(EnergyMeter::measure(
        None,
        Some(&EnergySnapshot {
            ev_battery_wh: Some(1.0),
            ..Default::default()
        }),
        5.0
    )
    .is_none());
    assert!(EnergyMeter::measure(
        Some(&EnergySnapshot {
            fuel_percent: Some(80.0),
            ..Default::default()
        }),
        Some(&EnergySnapshot {
            fuel_percent: Some(70.0),
            ..Default::default()
        }),
        50.0
    )
    .is_none());
}

#[test]
fn measured_efficiency_adjusts_eco_against_my_own_baseline() {
    let cfg = ScoreConfig::default();
    let mut base = TripResult::new(0, 600_000, 10.0, 0, 0, 60.0, 90);
    base.safety_score = 100;
    base.eco_score = 75;
    let eff = Efficiency {
        kind: EnergyKind::Ev,
        value: 6.6,
        distance_km: 10.0,
        used: 1.5,
    };

    let mut one_ev = base.clone();
    one_ev.efficiency = Some(6.0);
    one_ev.efficiency_kind = Some("EV".into());
    let first = EnergyMeter::apply(&base, Some(&eff), &[one_ev], &cfg);
    assert_eq!(75, first.eco_score);
    assert!((first.efficiency.unwrap() - 6.6).abs() <= 0.001);
    assert!(first.efficiency_improvement.is_none());

    let mut history: Vec<TripResult> = [5.0, 6.0]
        .iter()
        .map(|&v| {
            let mut t = base.clone();
            t.efficiency = Some(v);
            t.efficiency_kind = Some("EV".into());
            t
        })
        .collect();
    let mut fuel_hist = base.clone();
    fuel_hist.efficiency = Some(15.0);
    fuel_hist.efficiency_kind = Some("FUEL".into());
    history.push(fuel_hist);

    let applied = EnergyMeter::apply(&base, Some(&eff), &history, &cfg);
    assert!((applied.efficiency_improvement.unwrap() - 20.0).abs() <= 0.001);
    assert_eq!(88, applied.eco_score);
    assert_eq!(ScoreCalculator::total(100, 88, &cfg), applied.score);

    assert_eq!(75, EnergyMeter::eco_from_improvement(0.0));
    assert_eq!(100, EnergyMeter::eco_from_improvement(30.0));
    assert_eq!(25, EnergyMeter::eco_from_improvement(-40.0));
    assert_eq!(base, EnergyMeter::apply(&base, None, &history, &cfg));
}

#[test]
fn egg_levels_grow_with_eggs() {
    assert_eq!("알", EggLevels::current(0).name);
    assert_eq!("아기 꿩", EggLevels::next(0).unwrap().name);
    assert_eq!(5, EggLevels::remaining(0));
    assert_eq!(2, EggLevels::remaining(3));
    assert_eq!("아기 꿩", EggLevels::current(5).name);
    assert!((EggLevels::progress(3) as f64 - 0.6).abs() <= 0.001);
    assert_eq!("황금 꿩", EggLevels::current(40).name);
    assert!(EggLevels::next(40).is_none());
    assert!((EggLevels::progress(40) as f64 - 1.0).abs() <= 0.001);
}

#[test]
fn perfect_drive_gives_golden_egg() {
    let mut book = StampBook::default();
    let gold = book
        .try_add(GeoPoint::new(37.77, 128.94), 1_000, 95, Some("강릉"), true)
        .unwrap();
    assert!(gold.perfect);
    let plain = book
        .try_add(GeoPoint::new(35.15, 129.16), 2_000, 80, Some("부산"), false)
        .unwrap();
    assert!(!plain.perfect);
}

#[test]
fn resumed_trip_keeps_totals_and_does_not_invent_events() {
    let mut r = TripRecorder::default();
    r.start();
    let mut t = feed(
        &mut r,
        0,
        &concat(&[
            hold(30.0, 3.0),
            ramp(30.0, 60.0, 2.0),
            hold(60.0, 60.0),
        ]),
    );
    let first = r.snapshot();
    assert_eq!(1, first.harsh_accel);
    r.finish(0, t);

    let mut resumed = TripRecorder::default();
    resumed.resume(&first);
    assert!(resumed.is_active());
    t += 120_000;
    t = feed(&mut resumed, t, &hold(60.0, 60.0));
    let result = resumed.finish(0, t);
    assert_eq!(1, result.harsh_accel_count);
    assert!((result.distance_km - (first.distance_km + 1.0)).abs() <= 0.05);
    assert_eq!(96, result.safety_score);
}

#[test]
fn short_stop_continues_only_when_soon_and_nearby() {
    let here = GeoPoint::new(37.5665, 126.9780);
    let near = GeoPoint::new(37.5675, 126.9780);
    let far = GeoPoint::new(37.5765, 126.9780);
    assert!(TripContinuation::can_continue(0, 60_000, Some(here), Some(near)));
    assert!(TripContinuation::can_continue(0, 60_000, None, None));
    assert!(!TripContinuation::can_continue(0, 4 * 60_000, Some(here), Some(near)));
    assert!(!TripContinuation::can_continue(0, 60_000, Some(here), Some(far)));
    assert!(!TripContinuation::can_continue(60_000, 0, Some(here), Some(here)));

    assert!(TripContinuation::can_recover(0, 9 * 60_000));
    assert!(!TripContinuation::can_recover(0, 11 * 60_000));
}

#[test]
fn marks_and_stamp_removal() {
    let events = vec![
        LiveEvent {
            kind: HarshType::Brake,
            penalty: 5,
            score_after: 95,
            wall_time_ms: 1_000 + 30_000,
            km: 1.0,
        },
        LiveEvent {
            kind: HarshType::Accel,
            penalty: 4,
            score_after: 91,
            wall_time_ms: 1_000 + 90_000,
            km: 3.0,
        },
    ];
    let marks = TripContinuation::marks(&events, 1_000, 1_000 + 120_000);
    assert_eq!(
        vec![HarshType::Brake, HarshType::Accel],
        marks.iter().map(|m| m.kind).collect::<Vec<_>>()
    );
    assert!((marks[0].fraction as f64 - 0.25).abs() <= 0.001);
    assert!((marks[1].fraction as f64 - 0.75).abs() <= 0.001);

    let mut book = StampBook::default();
    let a = book.try_add(GeoPoint::new(37.0, 127.0), 1, 90, None, false).unwrap();
    assert!(book.remove(a.id));
    assert!(!book.remove(a.id));
    assert_eq!(0, book.stamps().len());
    assert!(book.try_add(GeoPoint::new(37.0, 127.0), 2, 90, None, false).is_some());
}

#[test]
fn short_perfect_trip_cannot_win_the_league() {
    let now = at_seoul(2026, 11, 14, 12);
    let cfg = LeagueConfig::default();
    let short = League::standing(&[trip_at(now, 100, 3.0)], now, "Asia/Seoul", &cfg, false);
    assert!((short.my_league_score.unwrap() - 82.6).abs() <= 0.001);
    assert!((short.my_average.unwrap() - 100.0).abs() <= 0.001);
    assert!(short.my_rank.unwrap() > 1);
    let long = League::standing(
        &[trip_at(now, 100, 3.0), trip_at(now, 95, 120.0)],
        now,
        "Asia/Seoul",
        &cfg,
        false,
    );
    assert!(long.my_rank.unwrap() < short.my_rank.unwrap());
}

#[test]
fn efficiency_is_compared_within_the_same_road_type() {
    let cfg = ScoreConfig::default();
    let city = |eff: Option<f64>| {
        let mut t = TripResult::new(0, 1_800_000, 10.0, 0, 0, 50.0, 90);
        t.safety_score = 100;
        t.eco_score = 75;
        t.efficiency = eff;
        t.efficiency_kind = eff.map(|_| "EV".into());
        t
    };
    let highway = |eff: Option<f64>| {
        let mut t = TripResult::new(0, 3_600_000, 100.0, 0, 0, 110.0, 90);
        t.safety_score = 100;
        t.eco_score = 75;
        t.efficiency = eff;
        t.efficiency_kind = eff.map(|_| "EV".into());
        t
    };
    assert_eq!(RoadType::City, city(None).road_type());
    assert_eq!(RoadType::Highway, highway(None).road_type());
    assert_eq!(RoadType::Mixed, RoadType::of(45.0));

    let history = vec![city(Some(6.0)), city(Some(6.0)), highway(Some(4.0)), highway(Some(4.0))];
    let applied = EnergyMeter::apply(
        &city(None),
        Some(&Efficiency {
            kind: EnergyKind::Ev,
            value: 6.6,
            distance_km: 10.0,
            used: 1.5,
        }),
        &history,
        &cfg,
    );
    assert!((applied.efficiency_improvement.unwrap() - 10.0).abs() <= 0.001);

    let few = vec![city(Some(6.0)), highway(Some(4.0)), highway(Some(5.0))];
    let fallback = EnergyMeter::apply(
        &city(None),
        Some(&Efficiency {
            kind: EnergyKind::Ev,
            value: 5.5,
            distance_km: 10.0,
            used: 1.5,
        }),
        &few,
        &cfg,
    );
    assert!((fallback.efficiency_improvement.unwrap() - 10.0).abs() <= 0.001);

    let outlier = vec![city(Some(6.0)), city(Some(6.0)), city(Some(30.0))];
    let out_applied = EnergyMeter::apply(
        &city(None),
        Some(&Efficiency {
            kind: EnergyKind::Ev,
            value: 6.6,
            distance_km: 10.0,
            used: 1.5,
        }),
        &outlier,
        &cfg,
    );
    assert!((out_applied.efficiency_improvement.unwrap() - 10.0).abs() <= 0.001);
}

#[test]
fn speed_decides_driving_when_gear_is_unknown() {
    let mut d = SpeedDriveDetector::default();
    assert_eq!(DriveMode::Unknown, d.on_speed(0, 0.0));
    assert_eq!(DriveMode::Driving, d.on_speed(1_000, 20.0));
    assert_eq!(DriveMode::Driving, d.on_speed(2_000, 0.0));
    assert_eq!(DriveMode::Driving, d.on_tick(62_000));
    assert_eq!(DriveMode::Driving, d.on_speed(63_000, 30.0));
    d.on_speed(70_000, 0.0);
    assert_eq!(DriveMode::Driving, d.on_tick(150_000));
    assert_eq!(DriveMode::Parked, d.on_tick(190_000));
    assert_eq!(DriveMode::Driving, d.on_speed(200_000, 8.0));
}

#[test]
fn diagnostics_board_throttles_same_status_but_not_changes() {
    let mut board = DiagnosticsBoard::default();
    let mut changes = 0;
    assert_eq!(ChannelStatus::Waiting, board.current()[&Channel::Speed].status);
    if board.ok(Channel::Speed, "10 km/h", 0) {
        changes += 1;
    }
    assert_eq!(1, changes);
    if board.ok(Channel::Speed, "12 km/h", 300) {
        changes += 1;
    }
    assert_eq!(1, changes);
    assert_eq!("10 km/h", board.current()[&Channel::Speed].detail);
    if board.ok(Channel::Speed, "15 km/h", 1_200) {
        changes += 1;
    }
    assert_eq!(2, changes);
    if board.unavailable(Channel::Speed, "끊김", 1_300) {
        changes += 1;
    }
    assert_eq!(3, changes);
    assert_eq!(ChannelStatus::Unavailable, board.current()[&Channel::Speed].status);
    assert_eq!(Some(1_300), board.current()[&Channel::Speed].updated_at_ms);
}

#[test]
fn brake_pedal_signal_separates_regen_from_harsh_braking() {
    let profile = concat(&[hold(60.0, 3.0), ramp(60.0, 36.0, 2.0), hold(36.0, 3.0)]);

    let mut regen = TripRecorder::default();
    regen.start();
    regen.on_brake_pedal(0, false);
    feed(&mut regen, 0, &profile);
    assert_eq!(0, regen.current_harsh_brake());

    let mut pedal = TripRecorder::default();
    pedal.start();
    pedal.on_brake_pedal(0, false);
    pedal.on_brake_pedal(3_000, true);
    feed(&mut pedal, 0, &profile);
    assert_eq!(1, pedal.current_harsh_brake());

    let mut hard = TripRecorder::default();
    hard.start();
    hard.on_brake_pedal(0, false);
    feed(
        &mut hard,
        0,
        &concat(&[hold(60.0, 3.0), ramp(60.0, 30.0, 2.0), hold(30.0, 3.0)]),
    );
    assert_eq!(1, hard.current_harsh_brake());

    let mut unknown = TripRecorder::default();
    unknown.start();
    feed(&mut unknown, 0, &profile);
    assert_eq!(1, unknown.current_harsh_brake());
}

#[test]
fn vehicle_efficiency_is_used_when_tank_levels_cannot_measure() {
    let start = EnergySnapshot {
        fuel_percent: Some(80.0),
        fuel_capacity_l: Some(50.0),
        ..Default::default()
    };
    let end = EnergySnapshot {
        fuel_percent: Some(80.0),
        fuel_capacity_l: Some(50.0),
        vehicle_fuel_km_per_l: Some(14.5),
        ..Default::default()
    };
    let eff = EnergyMeter::measure(Some(&start), Some(&end), 3.0).unwrap();
    assert_eq!(EnergyKind::Fuel, eff.kind);
    assert!((eff.value - 14.5).abs() <= 0.001);

    assert!(
        (EnergyMeter::measure(
            None,
            Some(&EnergySnapshot {
                vehicle_ev_km_per_kwh: Some(5.8),
                ..Default::default()
            }),
            4.0
        )
        .unwrap()
        .value
            - 5.8)
            .abs()
            <= 0.001
    );

    let ev = EnergyMeter::measure(
        Some(&EnergySnapshot {
            ev_battery_wh: Some(50_000.0),
            ..Default::default()
        }),
        Some(&EnergySnapshot {
            ev_battery_wh: Some(48_000.0),
            vehicle_ev_km_per_kwh: Some(9.9),
            ..Default::default()
        }),
        10.0,
    )
    .unwrap();
    assert!((ev.value - 5.0).abs() <= 0.001);

    assert!(EnergyMeter::measure(
        None,
        Some(&EnergySnapshot {
            vehicle_ev_km_per_kwh: Some(0.0),
            ..Default::default()
        }),
        4.0
    )
    .is_none());
}

#[test]
fn live_total_matches_final_total() {
    let mut r = TripRecorder::default();
    r.start();
    assert_eq!(100, r.current_total_score());
    let t = feed(
        &mut r,
        0,
        &concat(&[
            ramp(0.0, 60.0, 20.0),
            hold(60.0, 120.0),
            ramp(60.0, 0.0, 20.0),
        ]),
    );
    let live_total = r.current_total_score();
    let live_eco = r.current_eco_score();
    let result = r.finish(0, t);
    assert_eq!(result.score, live_total);
    assert_eq!(result.eco_score, live_eco);
    assert_eq!(
        ScoreCalculator::total(result.safety_score, result.eco_score, &ScoreConfig::default()),
        result.score
    );
}

fn feed_with_limit(recorder: &mut TripRecorder, start_ms: i64, speeds: &[f64], limit: i32) -> i64 {
    let mut t = start_ms;
    for (i, &s) in speeds.iter().enumerate() {
        if i % 5 == 0 {
            recorder.on_speed_limit(t, limit);
        }
        recorder.on_speed(t, s);
        t += 200;
    }
    t
}

#[test]
fn long_stop_counts_idle_beyond_grace() {
    let mut r = TripRecorder::default();
    r.start();
    let t = feed(&mut r, 0, &concat(&[ramp(0.0, 40.0, 10.0), hold(40.0, 120.0)]));
    feed(&mut r, t, &concat(&[ramp(40.0, 0.0, 10.0), hold(0.0, 300.0)]));
    let d = r.current_eco_breakdown();
    assert!((d.idle_min - 3.0).abs() <= 0.1);
    assert_eq!(Some(70), d.idle);
}

#[test]
fn traffic_light_stop_is_not_idle() {
    let mut r = TripRecorder::default();
    r.start();
    let t = feed(&mut r, 0, &concat(&[ramp(0.0, 40.0, 10.0), hold(40.0, 120.0)]));
    feed(
        &mut r,
        t,
        &concat(&[ramp(40.0, 0.0, 10.0), hold(0.0, 90.0), ramp(0.0, 40.0, 10.0)]),
    );
    assert_eq!(Some(100), r.current_eco_breakdown().idle);
}

#[test]
fn steady_cruise_beats_wavy_cruise() {
    let mut steady = TripRecorder::default();
    steady.start();
    feed(&mut steady, 0, &concat(&[ramp(0.0, 90.0, 30.0), hold(90.0, 300.0)]));

    let mut wavy = TripRecorder::default();
    wavy.start();
    let wave: Vec<f64> = (0..30)
        .flat_map(|_| concat(&[ramp(90.0, 100.0, 5.0), ramp(100.0, 90.0, 5.0)]))
        .collect();
    feed(&mut wavy, 0, &concat(&[ramp(0.0, 90.0, 30.0), wave]));
    assert_eq!(Some(100), steady.current_eco_breakdown().steady);
    assert!(wavy.current_eco_breakdown().steady.unwrap() <= 40);
}

#[test]
fn short_cruise_has_no_steady_item() {
    let mut r = TripRecorder::default();
    r.start();
    feed(
        &mut r,
        0,
        &concat(&[ramp(0.0, 70.0, 20.0), hold(70.0, 30.0), ramp(70.0, 0.0, 20.0)]),
    );
    assert!(r.current_eco_breakdown().steady.is_none());
}

#[test]
fn over_speed_uses_road_speed_limit() {
    let mut city = TripRecorder::default();
    city.start();
    feed_with_limit(
        &mut city,
        0,
        &concat(&[ramp(0.0, 70.0, 20.0), hold(70.0, 120.0)]),
        50,
    );
    assert_eq!(Some(0), city.current_eco_breakdown().speed);

    let mut highway = TripRecorder::default();
    highway.start();
    feed_with_limit(
        &mut highway,
        0,
        &concat(&[ramp(0.0, 105.0, 30.0), hold(105.0, 120.0)]),
        100,
    );
    assert_eq!(Some(100), highway.current_eco_breakdown().speed);
}

#[test]
fn over_speed_falls_back_to_110_without_limit() {
    let mut r = TripRecorder::default();
    r.start();
    feed(&mut r, 0, &concat(&[ramp(0.0, 130.0, 30.0), hold(130.0, 120.0)]));
    assert!(r.current_eco_breakdown().over_speed_percent.unwrap() >= 80);
    assert_eq!(Some(0), r.current_eco_breakdown().speed);
}

#[test]
fn stale_speed_limit_is_ignored() {
    let mut r = TripRecorder::default();
    r.start();
    r.on_speed_limit(0, 50);
    feed(&mut r, 0, &concat(&[ramp(0.0, 70.0, 20.0), hold(70.0, 120.0)]));
    assert_eq!(Some(100), r.current_eco_breakdown().speed);
    assert!(r.current_road_km().total() < 0.05);
}

#[test]
fn coasting_without_pedal_scores_high() {
    let mut coast = TripRecorder::default();
    coast.start();
    coast.on_brake_pedal(0, false);
    let mut t = 0_i64;
    for _ in 0..4 {
        t = feed(
            &mut coast,
            t,
            &concat(&[ramp(0.0, 50.0, 10.0), hold(50.0, 20.0), ramp(50.0, 0.0, 15.0)]),
        );
    }
    assert_eq!(Some(100), coast.current_eco_breakdown().coast);

    let mut brake = TripRecorder::default();
    brake.start();
    t = 0;
    for _ in 0..4 {
        t = feed(&mut brake, t, &concat(&[ramp(0.0, 50.0, 10.0), hold(50.0, 20.0)]));
        brake.on_brake_pedal(t, true);
        t = feed(&mut brake, t, &ramp(50.0, 0.0, 15.0));
        brake.on_brake_pedal(t, false);
    }
    assert_eq!(Some(0), brake.current_eco_breakdown().coast);
}

#[test]
fn coast_item_needs_pedal_signal() {
    let mut r = TripRecorder::default();
    r.start();
    let mut t = 0_i64;
    for _ in 0..4 {
        t = feed(
            &mut r,
            t,
            &concat(&[ramp(0.0, 50.0, 10.0), hold(50.0, 20.0), ramp(50.0, 0.0, 15.0)]),
        );
    }
    assert!(r.current_eco_breakdown().coast.is_none());
}

#[test]
fn road_type_comes_from_speed_limit() {
    let mut r = TripRecorder::default();
    r.start();
    let t = feed_with_limit(
        &mut r,
        0,
        &concat(&[ramp(0.0, 25.0, 10.0), hold(25.0, 600.0)]),
        100,
    );
    let result = r.finish(0, t);
    assert_eq!(RoadType::City, RoadType::of(result.average_speed_kmh()));
    assert_eq!(RoadType::Highway, result.road_type());
    assert!(result.road_type_from_limit());
    assert_eq!(RoadType::Mixed, RoadType::of_limit(80));
    assert_eq!(RoadType::City, RoadType::of_limit(50));
}

#[test]
fn eco_breakdown_skips_missing_items() {
    let cfg = EcoConfig::default();
    assert_eq!(80, EcoBreakdown::with_accel(80).score(&cfg));
    let mut with_idle = EcoBreakdown::with_accel(80);
    with_idle.idle = Some(100);
    assert_eq!(84, with_idle.score(&cfg));

    let mut weak = EcoBreakdown::with_accel(70);
    weak.idle = Some(100);
    weak.steady = Some(95);
    assert_eq!(Some(EcoItem::Accel), weak.weakest());

    let mut all_good = EcoBreakdown::with_accel(95);
    all_good.idle = Some(100);
    assert!(all_good.weakest().is_none());
}

#[test]
fn resume_keeps_eco_habits_and_finish_stores_detail() {
    let mut r = TripRecorder::default();
    r.start();
    feed(
        &mut r,
        0,
        &concat(&[
            ramp(0.0, 40.0, 10.0),
            hold(40.0, 60.0),
            ramp(40.0, 0.0, 10.0),
            hold(0.0, 300.0),
        ]),
    );
    let before = r.current_eco_breakdown();
    let mut again = TripRecorder::default();
    again.resume(&r.snapshot());
    assert!((before.idle_min - again.current_eco_breakdown().idle_min).abs() <= 0.001);
    assert_eq!(before.idle, again.current_eco_breakdown().idle);

    let mut r2 = TripRecorder::default();
    r2.start();
    let t = feed(
        &mut r2,
        0,
        &concat(&[ramp(0.0, 60.0, 20.0), hold(60.0, 120.0), ramp(60.0, 0.0, 20.0)]),
    );
    let live = r2.current_eco_breakdown();
    let result = r2.finish(0, t);
    assert_eq!(Some(live), result.eco_detail);
    assert_eq!(live.score(&EcoConfig::default()), result.eco_score);
}

#[test]
fn league_fairness_and_hidden_trips() {
    let now = at_seoul(2026, 11, 14, 12);
    let cfg = LeagueConfig::default();
    let gaming: Vec<TripResult> = (0..10)
        .map(|i| trip_at(now - i * 60_000, 100, 0.5))
        .collect();
    assert!(League::standing(&gaming, now, "Asia/Seoul", &cfg, false).my_rank.is_none());

    let mut mixed_trips = gaming;
    mixed_trips.push(trip_at(now, 70, 10.0));
    let mixed = League::standing(&mixed_trips, now, "Asia/Seoul", &cfg, false);
    assert_eq!(1, mixed.my_trip_count);
    assert!((mixed.my_average.unwrap() - 70.0).abs() <= 0.001);

    let real = trip_at(now, 70, 30.0);
    let mut sim = trip_at(now, 100, 30.0);
    sim.simulated = true;
    let real_league = League::standing(&[real.clone(), sim.clone()], now, "Asia/Seoul", &cfg, false);
    let sim_league = League::standing(&[real, sim], now, "Asia/Seoul", &cfg, true);
    assert!((real_league.my_average.unwrap() - 70.0).abs() <= 0.001);
    assert!((sim_league.my_average.unwrap() - 100.0).abs() <= 0.001);

    let trips = vec![trip_at(now, 90, 30.0), trip_at(now - 60_000, 70, 30.0)];
    let mut hidden = trips.clone();
    hidden[0].hidden = true;
    assert_eq!(
        League::standing(&trips, now, "Asia/Seoul", &cfg, false).my_league_score,
        League::standing(&hidden, now, "Asia/Seoul", &cfg, false).my_league_score
    );
    assert_eq!(2, League::standing(&hidden, now, "Asia/Seoul", &cfg, false).my_trip_count);
}

#[test]
fn coaching_delta_and_parking_summary() {
    let history: Vec<TripResult> = [70, 80, 90, 60, 70, 80]
        .iter()
        .map(|&s| TripResult::new(0, 1, 10.0, 0, 0, 60.0, s))
        .collect();
    let now = TripResult::new(0, 1, 10.0, 0, 0, 60.0, 82);
    assert_eq!(Some(6), Coaching::eco_delta(&now, &history));
    assert!(Coaching::eco_delta(&now, &history[..1]).is_none());
    assert_eq!("평소보다 에코 +6점", Coaching::delta_text(6));
    assert_eq!("평소보다 에코 -3점", Coaching::delta_text(-3));

    let mut detail = EcoBreakdown::with_accel(95);
    detail.idle = Some(60);
    detail.coast = Some(90);
    let mut result = TripResult::new(0, 1, 10.0, 0, 0, 60.0, 82);
    result.eco_score = 85;
    result.eco_detail = Some(detail);
    assert_eq!(
        "주행 끝. 종합 82점이에요. 에코는 평소보다 6점 높아요. 공회전이 아쉬웠어요.",
        Coaching::parking_summary(&result, Some(6))
    );
    let mut no_weak = result.clone();
    no_weak.eco_detail = Some(EcoBreakdown::with_accel(95));
    assert_eq!(
        "주행 끝. 종합 82점이에요. 운전 습관이 모두 좋았어요.",
        Coaching::parking_summary(&no_weak, None)
    );
    assert_eq!("적정 속도가", Coaching::with_subject("적정 속도"));
    assert_eq!("미리 감속이", Coaching::with_subject("미리 감속"));
}

#[test]
fn stamp_name_resolver_all_cases() {
    let point = GeoPoint::new(37.5665, 126.978);
    let now = 1_000_000_i64;
    let start = now - 1000;

    let name = StampNameResolver::resolve(
        Some(&ArrivedPlace {
            name: " 서울 ".into(),
            wall_time_ms: now,
            point: Some(point),
        }),
        Some("현재 주소"),
        Some(now),
        point,
        start,
        now,
    );
    assert_eq!(
        "서울",
        StampBook::default()
            .try_add(point, now, 90, name.as_deref(), false)
            .unwrap()
            .name
    );

    assert_eq!(
        Some("현재 주소".to_string()),
        StampNameResolver::resolve(None, Some("현재 주소"), Some(now), point, start, now)
    );
    assert!(StampNameResolver::resolve(None, Some("  "), Some(now), point, start, now).is_none());

    for time in [now - 1001, now + 1, now - StampNameResolver::MAX_AGE_MS - 1] {
        assert_eq!(
            Some("현재 주소".to_string()),
            StampNameResolver::resolve(
                Some(&ArrivedPlace {
                    name: "오래된 목적지".into(),
                    wall_time_ms: time,
                    point: Some(point),
                }),
                Some("현재 주소"),
                Some(now),
                point,
                start,
                now
            )
        );
    }

    for loc in [
        Some(GeoPoint::new(35.1796, 129.0756)),
        Some(GeoPoint::new(f64::NAN, 127.0)),
        Some(GeoPoint::new(91.0, 127.0)),
        None,
    ] {
        assert_eq!(
            Some("현재 주소".to_string()),
            StampNameResolver::resolve(
                Some(&ArrivedPlace {
                    name: "다른 장소".into(),
                    wall_time_ms: now,
                    point: loc,
                }),
                Some("현재 주소"),
                Some(now),
                point,
                start,
                now
            )
        );
    }
    assert!(StampNameResolver::resolve(
        None,
        Some("현재 주소"),
        Some(now),
        GeoPoint::new(f64::NAN, 127.0),
        start,
        now
    )
    .is_none());

    for addr_at in [
        Some(now - 1001),
        Some(now + 1),
        Some(now - StampNameResolver::MAX_AGE_MS - 1),
        None,
    ] {
        assert!(
            StampNameResolver::resolve(None, Some("현재 주소"), addr_at, point, start, now)
                .is_none()
        );
    }

    assert_eq!(
        Some("현재 주소".to_string()),
        StampNameResolver::resolve(
            Some(&ArrivedPlace {
                name: " ".into(),
                wall_time_ms: now,
                point: Some(point),
            }),
            Some("현재 주소"),
            Some(now),
            point,
            start,
            now
        )
    );
    let long = "가".repeat(110);
    assert_eq!(
        100,
        StampNameResolver::resolve(
            Some(&ArrivedPlace {
                name: long,
                wall_time_ms: now,
                point: Some(point),
            }),
            Some("현재 주소"),
            Some(now),
            point,
            start,
            now
        )
        .unwrap()
        .chars()
        .count()
    );

    let boundary = now - StampNameResolver::MAX_AGE_MS;
    assert_eq!(
        Some("서울".to_string()),
        StampNameResolver::resolve(
            Some(&ArrivedPlace {
                name: "서울".into(),
                wall_time_ms: boundary,
                point: Some(point),
            }),
            Some("현재 주소"),
            Some(now),
            point,
            boundary,
            now
        )
    );
    assert_eq!(
        Some("현재 주소".to_string()),
        StampNameResolver::resolve(
            Some(&ArrivedPlace {
                name: "서울".into(),
                wall_time_ms: boundary - 1,
                point: Some(point),
            }),
            Some("현재 주소"),
            Some(now),
            point,
            boundary - 1,
            now
        )
    );
}

fn spawn_one_shot_http(status_line: &'static str, body: String) -> (String, thread::JoinHandle<String>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let handle = thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap();
        let mut buf = [0u8; 4096];
        let n = stream.read(&mut buf).unwrap_or(0);
        let req_text = String::from_utf8_lossy(&buf[..n]).to_string();
        let response = format!(
            "HTTP/1.1 {status_line}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
            body.len(),
            body
        );
        let _ = stream.write_all(response.as_bytes());
        req_text
    });
    (format!("http://127.0.0.1:{port}/"), handle)
}

#[test]
fn league_client_validation_and_http_cases() {
    let week = 1_794_841_200_000_i64;
    let valid_json = format!(
        r#"{{"weekStartMs":{week},"simulated":false,"entries":[{{"name":"나","averageScore":81.2,"isMe":true}}],"myRank":1,"myAverage":91.0,"myLeagueScore":81.2,"myDistanceKm":2.5,"myTripCount":1,"rewardPoints":500,"earnedPoints":0}}"#
    );

    let (base, h) = spawn_one_shot_http("200 OK", valid_json.clone());
    let client = LeagueClient::create(&base, Some("test-token".into()), true).unwrap();
    let LeagueReply::Success { snapshot } = client.weekly(week, false) else {
        panic!("expected success");
    };
    assert_eq!(Some(91.0), snapshot.standing.my_average);
    assert_eq!(Some(81.2), snapshot.standing.my_league_score);
    let req = h.join().unwrap();
    assert!(req.starts_with(&format!("GET /v1/league/weekly?weekStartMs={week}&simulated=false HTTP/1.1")));
    assert!(req.contains("Authorization: Bearer test-token"));

    // Mismatched simulation mode
    assert_eq!(
        LeagueReply::Failed {
            reason: ApiFailure::InvalidResponse,
            http_status: None
        },
        LeagueClient::validate_weekly(&valid_json, week, true)
    );

    // 401 unauthorized
    let (base401, h401) = spawn_one_shot_http("401 Unauthorized", r#"{"error":"invalid_token"}"#.into());
    let c401 = LeagueClient::create(&base401, Some("bad".into()), true).unwrap();
    assert_eq!(
        LeagueReply::Failed {
            reason: ApiFailure::Http,
            http_status: Some(401)
        },
        c401.weekly(week, false)
    );
    let _ = h401.join();

    // 204 empty body
    let (base204, h204) = spawn_one_shot_http("204 No Content", String::new());
    let c204 = LeagueClient::create(&base204, Some("tok".into()), true).unwrap();
    assert_eq!(
        LeagueReply::Failed {
            reason: ApiFailure::InvalidResponse,
            http_status: None
        },
        c204.weekly(week, false)
    );
    let _ = h204.join();

    // Invalid payloads
    for bad in [
        "not json".to_string(),
        "{}".to_string(),
        valid_json.replace(&format!("\"weekStartMs\":{week}"), "\"weekStartMs\":0"),
        valid_json.replace("\"myRank\":1", "\"myRank\":9"),
        valid_json.replace("81.2", "101.0"),
        valid_json.replace("\"myLeagueScore\":81.2", "\"myLeagueScore\":null"),
    ] {
        assert_eq!(
            LeagueReply::Failed {
                reason: ApiFailure::InvalidResponse,
                http_status: None
            },
            LeagueClient::validate_weekly(&bad, week, false)
        );
    }

    // Empty league
    let empty_json = format!(
        r#"{{"weekStartMs":{week},"simulated":false,"entries":[],"myRank":null,"myAverage":null,"myLeagueScore":null,"myDistanceKm":0,"myTripCount":0,"rewardPoints":0,"earnedPoints":0}}"#
    );
    let LeagueReply::Success { snapshot: empty_snap } =
        LeagueClient::validate_weekly(&empty_json, week, false)
    else {
        panic!("expected empty success");
    };
    assert!(empty_snap.standing.entries.is_empty());
    assert!(empty_snap.standing.my_rank.is_none());

    // URL validation rules
    for bad_url in [
        "http://example.com/",
        "https://user:pass@example.com/",
        "https://example.com/api",
        "https://example.com/?x=1",
        "https://example.com/#fragment",
    ] {
        assert!(LeagueClient::create(bad_url, None, true).is_err(), "{bad_url}");
    }
    for local_url in ["http://127.0.0.1:8765/", "http://10.0.2.2:8765/"] {
        assert!(LeagueClient::create(local_url, None, false).is_err());
        assert!(LeagueClient::create(local_url, None, true).is_ok());
    }

    // Stable trip ID & receipt check
    let mut trip = TripResult::new(10, 20, 2.0, 0, 0, 80.0, 98);
    trip.harsh_steer_count = 1;
    let dto = TripUploadDto::from_trip(&trip);
    assert_eq!(dto.trip_id, TripUploadDto::from_trip(&trip).trip_id);
    let mut sim_trip = trip.clone();
    sim_trip.simulated = true;
    assert_ne!(dto.trip_id, TripUploadDto::from_trip(&sim_trip).trip_id);
    let mut end_trip = trip.clone();
    end_trip.end_time_ms = 21;
    assert_ne!(dto.trip_id, TripUploadDto::from_trip(&end_trip).trip_id);
    assert_eq!(1, dto.harsh_steer_count);
    assert_eq!("prototype-v2", dto.scoring_version);

    assert_eq!(
        UploadReply::Failed {
            reason: ApiFailure::InvalidResponse,
            http_status: None
        },
        LeagueClient::validate_receipt(
            &dto,
            r#"{"tripId":"another-trip","accepted":true,"duplicate":false,"score":98}"#
        )
    );
}

#[test]
fn storage_roundtrip_and_kotlin_schema_compatibility() {
    let dir = tempfile::tempdir().unwrap();
    let storage = Storage::new(dir.path());

    // Simulate a legacy Kotlin-written kkwongal.json without safety/eco/steer fields
    let legacy_json = r#"{"trips":[{"start":1000,"end":61000,"distanceKm":5.5,"accel":1,"brake":0,"maxSpeed":72.5,"score":96}],"stamps":[{"id":1,"name":"강릉","lat":37.77,"lon":128.94,"time":61000,"score":96}]}"#;
    std::fs::write(dir.path().join("kkwongal.json"), legacy_json).unwrap();
    let loaded = storage.load();
    assert_eq!(1, loaded.trips.len());
    assert_eq!(96, loaded.trips[0].safety_score);
    assert_eq!(96, loaded.trips[0].eco_score);
    assert_eq!(0, loaded.trips[0].harsh_steer_count);
    assert_eq!("강릉", loaded.stamps[0].name);

    // Full roundtrip with all fields
    let mut trip = TripResult::new(1000, 601_000, 12.3, 1, 2, 105.0, 84);
    trip.safety_score = 86;
    trip.eco_score = 83;
    trip.harsh_steer_count = 1;
    trip.efficiency = Some(6.2);
    trip.efficiency_kind = Some("EV".into());
    trip.efficiency_improvement = Some(8.5);
    trip.eco_detail = Some(EcoBreakdown {
        accel: 85,
        steady: Some(90),
        speed: Some(75),
        coast: Some(80),
        idle: Some(100),
        over_speed_percent: Some(12),
        coast_percent: Some(45),
        idle_min: 0.0,
    });
    trip.road_km = RoadKm::new(2.0, 4.0, 6.3);
    trip.simulated = true;
    trip.hidden = true;
    trip.events = vec![LiveEvent {
        kind: HarshType::Brake,
        penalty: 5,
        score_after: 95,
        wall_time_ms: 120_000,
        km: 3.1,
    }];

    let saved = SavedData {
        trips: vec![trip.clone()],
        stamps: vec![Stamp {
            id: 1,
            name: "강릉 안목해변".into(),
            latitude: 37.7719,
            longitude: 128.9473,
            time_ms: 601_000,
            score: 84,
            memo: "바다".into(),
            tags: vec!["경치가 좋아요".into()],
            perfect: false,
        }],
    };
    storage.save(&saved).unwrap();
    assert_eq!(saved, storage.load());

    // Ongoing roundtrip
    let ongoing = OngoingTrip {
        start_wall_ms: 1000,
        saved_at_ms: 31_000,
        snapshot: TripRecorder::default().snapshot(),
        start_energy: Some(EnergySnapshot {
            ev_battery_wh: Some(72_000.0),
            odometer_km: Some(12_000.0),
            ..Default::default()
        }),
        events: trip.events,
    };
    storage.save_ongoing(&ongoing).unwrap();
    assert_eq!(Some(ongoing), storage.load_ongoing());
    storage.clear_ongoing();
    assert!(storage.load_ongoing().is_none());
}

#[test]
fn fake_vehicle_demo_and_engine_full_lifecycle() {
    let dir = tempfile::tempdir().unwrap();
    let mut wall = at_seoul(2026, 11, 18, 12);
    let mut engine = KkwongalEngine::new(dir.path(), false, true, wall);
    engine.set_simulation_mode(true, None, wall);

    let mut fake = FakeVehicleEngine::new(0, wall);
    let mut real_ms = 0_i64;
    let mut demo_steps = 0;

    fake.set_demo_active(true, real_ms, wall);
    for action in FakeVehicleEngine::demo_script() {
        match action {
            DemoAction::Step { .. } => {
                demo_steps += 1;
            }
            DemoAction::SetLocation {
                point,
                name,
                address,
            } => {
                fake.set_location(Some(point), Some(name), Some(address), wall);
            }
            DemoAction::SetParked { parked } => {
                let ctrl = fake.set_parked(parked, wall);
                let step = engine.on_drive_state(ctrl.drive_state, wall, ctrl.location);
                if step.needs_start_energy {
                    let (e, _) = fake.read_energy(wall);
                    engine.set_start_energy(Some(e), wall);
                }
                if step.needs_finish_energy {
                    let (e, _) = fake.read_energy(wall);
                    let finish_step = engine.complete_finish(
                        Some(e),
                        ctrl.arrived_place.as_ref(),
                        ctrl.address.as_deref(),
                        ctrl.address_updated_at_ms,
                        wall,
                    );
                    assert!(finish_step.speak.is_some());
                }
            }
            DemoAction::SetSpeed { kmh } => {
                let ctrl = fake.set_speed(kmh, real_ms, wall);
                for b in ctrl.brake_events {
                    engine.on_brake_pedal(b.time_ms, b.pressed);
                }
            }
            DemoAction::RampTo { target, rate } => {
                let mut sp = fake.current_state().speed_kmh;
                while (target - sp).abs() > 0.01 {
                    sp = if target > sp {
                        target.min(sp + rate)
                    } else {
                        target.max(sp - rate)
                    };
                    let ctrl = fake.set_speed(sp, real_ms, wall);
                    for b in ctrl.brake_events {
                        engine.on_brake_pedal(b.time_ms, b.pressed);
                    }
                    real_ms += 200;
                    wall += 200;
                    let tick = fake.tick(real_ms, wall);
                    for b in tick.brake_events {
                        engine.on_brake_pedal(b.time_ms, b.pressed);
                    }
                    engine.on_speed_limit(tick.speed_limit_sample.time_ms, tick.speed_limit_sample.limit_kmh);
                    engine.on_speed(tick.speed_sample.time_ms, tick.speed_sample.speed_kmh, wall);
                    engine.on_steering(tick.steering_sample.time_ms, tick.steering_sample.angle_deg, wall);
                }
            }
            DemoAction::Hold { virtual_seconds } => {
                // 1 virtual second = 200ms real at 5x = 1 tick
                for _ in 0..virtual_seconds {
                    real_ms += 200;
                    wall += 200;
                    let tick = fake.tick(real_ms, wall);
                    for b in tick.brake_events {
                        engine.on_brake_pedal(b.time_ms, b.pressed);
                    }
                    engine.on_speed_limit(tick.speed_limit_sample.time_ms, tick.speed_limit_sample.limit_kmh);
                    engine.on_speed(tick.speed_sample.time_ms, tick.speed_sample.speed_kmh, wall);
                    engine.on_steering(tick.steering_sample.time_ms, tick.steering_sample.angle_deg, wall);
                }
            }
        }
    }
    fake.set_demo_active(false, real_ms, wall);

    assert_eq!(10, demo_steps);
    let snap = engine.snapshot();
    assert!(!snap.trip_active);
    assert_eq!(1, snap.trips.len());
    assert_eq!(1, snap.stamps.len());
    assert_eq!("강릉 안목해변", snap.stamps[0].name);
    let res = snap.last_result.as_ref().unwrap();
    assert!(res.distance_km > 3.0);
    assert_eq!(1, res.harsh_accel_count);
    assert_eq!(1, res.harsh_brake_count);
    assert!(res.efficiency.is_some());
    assert_eq!(Some("EV".to_string()), res.efficiency_kind);
    assert!(res.eco_detail.is_some());
}

#[test]
fn energy_text_and_korea_outline_sanity() {
    let e = EnergySnapshot {
        ev_battery_wh: Some(72_500.0),
        fuel_percent: Some(80.0),
        odometer_km: Some(12_345.0),
        ..Default::default()
    };
    assert_eq!("배터리 72.5 kWh · 연료 80% · 누적 12,345 km", energy_text(&e));
    assert_eq!("값 없음", energy_text(&EnergySnapshot::default()));
    assert_eq!(44, korea_outline::POLYGONS.len());
    assert!((korea_outline::DOKDO[0] - 131.861).abs() < 0.001);
}
