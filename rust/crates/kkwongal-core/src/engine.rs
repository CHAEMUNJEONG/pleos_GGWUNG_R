use std::collections::BTreeSet;
use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::coaching::Coaching;
use crate::energy::{EnergyMeter, EnergySnapshot};
use crate::fake_vehicle::DriveState;
use crate::league::{League, LeagueConfig, LeagueStanding};
use crate::league_client::TripUploadDto;
use crate::models::{Badge, GeoPoint, Stamp, TripResult};
use crate::stamp_book::{BadgeRules, StampBook};
use crate::stamp_name_resolver::{ArrivedPlace, StampNameResolver};
use crate::storage::{SavedData, Storage};
use crate::trip_continuation::{OngoingTrip, TripContinuation, TripMark};
use crate::trip_recorder::{HarshEvent, LiveEvent, ScoreConfig, TripRecorder, TripSnapshot};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PastReport {
    pub result: TripResult,
    pub marks: Vec<TripMark>,
    pub eco_delta: Option<i32>,
    pub place_name: Option<String>,
}

/// Snapshot of core domain state exposed to the Android/Compose UI layer.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EngineSnapshot {
    pub simulation: bool,
    pub trip_active: bool,
    pub live_safety: i32,
    pub live_total: i32,
    pub live_harsh_accel: i32,
    pub live_harsh_brake: i32,
    pub live_harsh_steer: i32,
    pub last_harsh_event: Option<HarshEvent>,
    pub live_events: Vec<LiveEvent>,
    pub harsh_event_seq: i32,
    pub last_result: Option<TripResult>,
    pub new_stamp: Option<Stamp>,
    pub notice: Option<String>,
    pub trips: Vec<TripResult>,
    pub stamps: Vec<Stamp>,
    pub badges: BTreeSet<Badge>,
    pub league: Option<LeagueStanding>,
    pub earned_points: i32,
    pub remote_league: bool,
    pub last_trip_marks: Vec<TripMark>,
    pub last_trip_events: Vec<LiveEvent>,
    pub last_eco_delta: Option<i32>,
    pub past_report: Option<PastReport>,
    pub voice_guide: bool,
}

/// Action directives emitted by engine transitions for the thin platform shell (TTS / async energy I/O).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EngineStepResult {
    pub snapshot: EngineSnapshot,
    pub speak: Option<String>,
    pub needs_start_energy: bool,
    pub needs_finish_energy: bool,
}

#[derive(Debug, Clone)]
struct FinishedTrip {
    result: Option<TripResult>,
    snapshot: TripSnapshot,
    start_wall_ms: i64,
    end_wall_ms: i64,
    start_energy: Option<EnergySnapshot>,
    events: Vec<LiveEvent>,
    stamp: Option<Stamp>,
    stopped_at: Option<GeoPoint>,
}

#[derive(Debug, Clone)]
struct PendingFinish {
    measured: TripResult,
    snapshot: TripSnapshot,
    start_wall_ms: i64,
    end_wall_ms: i64,
    events: Vec<LiveEvent>,
    stopped_at: Option<GeoPoint>,
}

/// 앱의 두뇌: 주행 생명주기, 점수 계산, 자동 저장/복구, 이어 붙이기, 알(스탬프), 코칭, 주간 리그를
/// 모두 순수 Rust 로 관리한다.
pub struct KkwongalEngine {
    storage: Storage,
    stamp_book: StampBook,
    trips: Vec<TripResult>,
    recorder: TripRecorder,
    trip_start_wall_ms: i64,
    trip_start_energy: Option<EnergySnapshot>,
    last_ongoing_save_ms: i64,
    pending_recovery: Option<OngoingTrip>,
    last_finished: Option<FinishedTrip>,
    pending_finish: Option<PendingFinish>,
    simulation_mode: bool,
    remote_league: bool,
    voice_guide: bool,
    live_safety: i32,
    live_total: i32,
    live_harsh_accel: i32,
    live_harsh_brake: i32,
    live_harsh_steer: i32,
    last_harsh_event: Option<HarshEvent>,
    live_events: Vec<LiveEvent>,
    harsh_event_seq: i32,
    last_result: Option<TripResult>,
    last_trip_events: Vec<LiveEvent>,
    last_trip_marks: Vec<TripMark>,
    new_stamp: Option<Stamp>,
    last_eco_delta: Option<i32>,
    past_report: Option<PastReport>,
    notice: Option<String>,
    league: Option<LeagueStanding>,
    earned_points: i32,
}

impl KkwongalEngine {
    pub const MIN_TRIP_KM: f64 = 0.3;
    pub const ONGOING_SAVE_INTERVAL_MS: i64 = 30_000;
    pub const SEOUL_ZONE: &'static str = "Asia/Seoul";

    pub fn new(
        storage_dir: impl AsRef<Path>,
        remote_league: bool,
        voice_guide: bool,
        now_ms: i64,
    ) -> Self {
        let storage = Storage::new(storage_dir);
        let saved = storage.load();
        let pending_recovery = storage.load_ongoing();
        let stamp_book = StampBook::new(saved.stamps, 500.0);
        let mut engine = Self {
            storage,
            stamp_book,
            trips: saved.trips,
            recorder: TripRecorder::default(),
            trip_start_wall_ms: 0,
            trip_start_energy: None,
            last_ongoing_save_ms: 0,
            pending_recovery,
            last_finished: None,
            pending_finish: None,
            simulation_mode: false,
            remote_league,
            voice_guide,
            live_safety: 100,
            live_total: 100,
            live_harsh_accel: 0,
            live_harsh_brake: 0,
            live_harsh_steer: 0,
            last_harsh_event: None,
            live_events: Vec::new(),
            harsh_event_seq: 0,
            last_result: None,
            last_trip_events: Vec::new(),
            last_trip_marks: Vec::new(),
            new_stamp: None,
            last_eco_delta: None,
            past_report: None,
            notice: None,
            league: None,
            earned_points: 0,
        };
        engine.recompute_local_league(now_ms);
        engine
    }

    pub fn is_trip_active(&self) -> bool {
        self.recorder.is_active()
    }

    pub fn penalty_message(label: &str, penalty: i32) -> String {
        format!("{label}, {penalty}점 감점")
    }

    pub fn snapshot(&self) -> EngineSnapshot {
        EngineSnapshot {
            simulation: self.simulation_mode,
            trip_active: self.recorder.is_active(),
            live_safety: self.live_safety,
            live_total: self.live_total,
            live_harsh_accel: self.live_harsh_accel,
            live_harsh_brake: self.live_harsh_brake,
            live_harsh_steer: self.live_harsh_steer,
            last_harsh_event: self.last_harsh_event,
            live_events: self.live_events.clone(),
            harsh_event_seq: self.harsh_event_seq,
            last_result: self.last_result.clone(),
            new_stamp: self.new_stamp.clone(),
            notice: self.notice.clone(),
            trips: self.trips.clone(),
            stamps: self.stamp_book.stamps().to_vec(),
            badges: BadgeRules::evaluate(&self.trips, self.stamp_book.stamps()),
            league: self.league.clone(),
            earned_points: self.earned_points,
            remote_league: self.remote_league,
            last_trip_marks: self.last_trip_marks.clone(),
            last_trip_events: self.last_trip_events.clone(),
            last_eco_delta: self.last_eco_delta,
            past_report: self.past_report.clone(),
            voice_guide: self.voice_guide,
        }
    }

    fn step_result(&self, speak: Option<String>, needs_start_energy: bool, needs_finish_energy: bool) -> EngineStepResult {
        EngineStepResult {
            snapshot: self.snapshot(),
            speak,
            needs_start_energy,
            needs_finish_energy,
        }
    }

    pub fn set_voice_guide(&mut self, on: bool) -> EngineSnapshot {
        self.voice_guide = on;
        self.snapshot()
    }

    pub fn set_simulation_mode(
        &mut self,
        enabled: bool,
        fallback_notice: Option<String>,
        now_ms: i64,
    ) -> EngineSnapshot {
        if self.recorder.is_active() {
            return self.snapshot();
        }
        self.last_finished = None;
        self.simulation_mode = enabled;
        self.notice = fallback_notice;
        if self.remote_league {
            self.league = None;
            self.earned_points = 0;
        } else {
            self.recompute_local_league(now_ms);
        }
        self.snapshot()
    }

    pub fn clear_for_demo(&mut self) -> EngineSnapshot {
        self.last_finished = None;
        self.last_result = None;
        self.new_stamp = None;
        self.notice = None;
        self.snapshot()
    }

    pub fn refresh_local_league(&mut self, now_ms: i64) -> EngineSnapshot {
        if !self.remote_league {
            self.recompute_local_league(now_ms);
        }
        self.snapshot()
    }

    fn recompute_local_league(&mut self, now_ms: i64) {
        if self.remote_league {
            self.league = None;
            self.earned_points = 0;
        } else {
            let cfg = LeagueConfig::default();
            self.league = Some(League::standing(
                &self.trips,
                now_ms,
                Self::SEOUL_ZONE,
                &cfg,
                self.simulation_mode,
            ));
            self.earned_points = League::earned_points(
                &self.trips,
                now_ms,
                Self::SEOUL_ZONE,
                &cfg,
                self.simulation_mode,
            );
        }
    }

    pub fn on_speed(&mut self, mono_ms: i64, speed_kmh: f64, wall_ms: i64) -> EngineStepResult {
        let mut speak = None;
        if let Some(ev) = self.recorder.on_speed(mono_ms, speed_kmh) {
            speak = self.apply_harsh_event(ev, wall_ms);
        } else if self.recorder.is_active() {
            let safety = self.recorder.current_safety_score();
            let total = self.recorder.current_total_score();
            if safety != self.live_safety || total != self.live_total {
                self.live_safety = safety;
                self.live_total = total;
            }
        }
        if self.recorder.is_active()
            && wall_ms - self.last_ongoing_save_ms >= Self::ONGOING_SAVE_INTERVAL_MS
        {
            self.save_ongoing(wall_ms);
        }
        self.step_result(speak, false, false)
    }

    pub fn on_steering(&mut self, mono_ms: i64, angle_deg: f64, wall_ms: i64) -> EngineStepResult {
        let speak = self
            .recorder
            .on_steering(mono_ms, angle_deg)
            .and_then(|ev| self.apply_harsh_event(ev, wall_ms));
        self.step_result(speak, false, false)
    }

    pub fn on_brake_pedal(&mut self, mono_ms: i64, pressed: bool) {
        self.recorder.on_brake_pedal(mono_ms, pressed);
    }

    pub fn on_speed_limit(&mut self, mono_ms: i64, limit_kmh: i32) {
        self.recorder.on_speed_limit(mono_ms, limit_kmh);
    }

    fn apply_harsh_event(&mut self, event: HarshEvent, wall_ms: i64) -> Option<String> {
        let speak = if self.voice_guide {
            Some(Self::penalty_message(event.kind.label(), event.penalty))
        } else {
            None
        };
        self.live_safety = event.safety_after;
        self.live_total = self.recorder.current_total_score();
        self.live_harsh_accel = self.recorder.current_harsh_accel();
        self.live_harsh_brake = self.recorder.current_harsh_brake();
        self.live_harsh_steer = self.recorder.current_harsh_steer();
        self.last_harsh_event = Some(event);
        self.live_events.push(LiveEvent {
            kind: event.kind,
            penalty: event.penalty,
            score_after: event.safety_after,
            wall_time_ms: wall_ms,
            km: self.recorder.current_distance_km(),
        });
        self.harsh_event_seq += 1;
        self.save_ongoing(wall_ms);
        speak
    }

    pub fn on_drive_state(
        &mut self,
        state: DriveState,
        wall_ms: i64,
        location: Option<GeoPoint>,
    ) -> EngineStepResult {
        match state {
            DriveState::Driving => {
                if !self.recorder.is_active() {
                    self.on_depart(wall_ms, location)
                } else {
                    self.step_result(None, false, false)
                }
            }
            DriveState::Parked => {
                if let Some(rec) = self.pending_recovery.take() {
                    self.save_recovered(rec, wall_ms);
                }
                if self.recorder.is_active() {
                    self.begin_finish(wall_ms, location)
                } else {
                    self.step_result(None, false, false)
                }
            }
            DriveState::Unknown => self.step_result(None, false, false),
        }
    }

    fn on_depart(&mut self, wall_ms: i64, location: Option<GeoPoint>) -> EngineStepResult {
        let recovery = self.pending_recovery.take();
        let previous = self.last_finished.take();

        if let Some(rec) = recovery {
            if TripContinuation::can_recover(rec.saved_at_ms, wall_ms) {
                self.resume_trip(
                    rec.start_wall_ms,
                    &rec.snapshot,
                    rec.start_energy,
                    rec.events,
                    "앱이 다시 켜져서 하던 주행을 이어서 기록해요".to_string(),
                    wall_ms,
                );
                return self.step_result(None, false, false);
            } else {
                // Expired recovery trip: save if long enough, then check previous or start new
                if let Some(prev) = previous {
                    if TripContinuation::can_continue(prev.end_wall_ms, wall_ms, prev.stopped_at, location) {
                        self.undo_finished(&prev, wall_ms);
                        self.resume_trip(
                            prev.start_wall_ms,
                            &prev.snapshot,
                            prev.start_energy,
                            prev.events,
                            "잠깐 멈췄다 다시 출발해서 앞 주행에 이어서 기록해요".to_string(),
                            wall_ms,
                        );
                        return self.step_result(None, false, false);
                    }
                }
                self.save_recovered(rec, wall_ms);
                self.start_new_trip(wall_ms);
                return self.step_result(None, true, false);
            }
        }

        if let Some(prev) = previous {
            if TripContinuation::can_continue(prev.end_wall_ms, wall_ms, prev.stopped_at, location) {
                self.undo_finished(&prev, wall_ms);
                self.resume_trip(
                    prev.start_wall_ms,
                    &prev.snapshot,
                    prev.start_energy,
                    prev.events,
                    "잠깐 멈췄다 다시 출발해서 앞 주행에 이어서 기록해요".to_string(),
                    wall_ms,
                );
                return self.step_result(None, false, false);
            }
        }

        self.start_new_trip(wall_ms);
        self.step_result(None, true, false)
    }

    fn start_new_trip(&mut self, wall_ms: i64) {
        self.recorder.start();
        self.trip_start_wall_ms = wall_ms;
        self.trip_start_energy = None;
        self.show_trip_started(Vec::new(), None, wall_ms);
    }

    fn resume_trip(
        &mut self,
        start_wall_ms: i64,
        snapshot: &TripSnapshot,
        start_energy: Option<EnergySnapshot>,
        events: Vec<LiveEvent>,
        notice: String,
        wall_ms: i64,
    ) {
        self.recorder.resume(snapshot);
        self.trip_start_wall_ms = start_wall_ms;
        self.trip_start_energy = start_energy;
        self.show_trip_started(events, Some(notice), wall_ms);
    }

    fn show_trip_started(
        &mut self,
        events: Vec<LiveEvent>,
        notice: Option<String>,
        wall_ms: i64,
    ) {
        self.last_result = None;
        self.past_report = None;
        self.new_stamp = None;
        self.notice = notice;
        self.live_safety = self.recorder.current_safety_score();
        self.live_total = self.recorder.current_total_score();
        self.live_harsh_accel = self.recorder.current_harsh_accel();
        self.live_harsh_brake = self.recorder.current_harsh_brake();
        self.live_harsh_steer = self.recorder.current_harsh_steer();
        self.last_harsh_event = None;
        self.live_events = events;
        self.save_ongoing(wall_ms);
    }

    pub fn set_start_energy(&mut self, energy: Option<EnergySnapshot>, wall_ms: i64) {
        self.trip_start_energy = energy;
        self.save_ongoing(wall_ms);
    }

    pub fn save_ongoing(&mut self, wall_ms: i64) {
        if !self.recorder.is_active() {
            return;
        }
        self.last_ongoing_save_ms = wall_ms;
        let _ = self.storage.save_ongoing(&OngoingTrip {
            start_wall_ms: self.trip_start_wall_ms,
            saved_at_ms: wall_ms,
            snapshot: self.recorder.snapshot(),
            start_energy: self.trip_start_energy,
            events: self.live_events.clone(),
        });
    }

    fn same_mode(&self, simulated: bool) -> Vec<TripResult> {
        self.trips
            .iter()
            .filter(|t| t.simulated == simulated)
            .cloned()
            .collect()
    }

    fn save_recovered(&mut self, trip: OngoingTrip, now_ms: i64) {
        self.storage.clear_ongoing();
        let mut r = TripRecorder::default();
        r.resume(&trip.snapshot);
        let mut measured = r.finish(trip.start_wall_ms, trip.saved_at_ms);
        measured.simulated = self.simulation_mode;
        measured.events = trip.events;
        if measured.distance_km < Self::MIN_TRIP_KM {
            return;
        }
        let history = self.same_mode(measured.simulated);
        let applied = EnergyMeter::apply(&measured, None, &history, &ScoreConfig::default());
        self.trips.push(applied);
        self.persist();
        self.notice = Some("앱이 꺼져 있던 동안 끝난 주행을 기록에 남겼어요".to_string());
        self.recompute_local_league(now_ms);
    }

    fn undo_finished(&mut self, previous: &FinishedTrip, now_ms: i64) {
        if let Some(ref res) = previous.result {
            if let Some(idx) = self
                .trips
                .iter()
                .rposition(|t| t.start_time_ms == res.start_time_ms && t.end_time_ms == res.end_time_ms)
            {
                self.trips.remove(idx);
            }
        }
        if let Some(ref stamp) = previous.stamp {
            if let Some(current) = self.stamp_book.stamps().iter().find(|s| s.id == stamp.id) {
                if current.name == stamp.name
                    && current.memo.trim().is_empty()
                    && current.tags.is_empty()
                {
                    self.stamp_book.remove(stamp.id);
                }
            }
        }
        self.persist();
        self.recompute_local_league(now_ms);
    }

    fn begin_finish(&mut self, end_wall_ms: i64, stopped_at: Option<GeoPoint>) -> EngineStepResult {
        let start_wall_ms = self.trip_start_wall_ms;
        let mut measured = self.recorder.finish(start_wall_ms, end_wall_ms);
        measured.simulated = self.simulation_mode;
        let snapshot = self.recorder.snapshot();
        let events = self.live_events.clone();
        self.storage.clear_ongoing();
        if measured.distance_km < Self::MIN_TRIP_KM {
            self.last_finished = Some(FinishedTrip {
                result: None,
                snapshot,
                start_wall_ms,
                end_wall_ms,
                start_energy: self.trip_start_energy,
                events,
                stamp: None,
                stopped_at,
            });
            self.notice = Some("주행 거리가 너무 짧아 기록하지 않았습니다".to_string());
            return self.step_result(None, false, false);
        }
        self.pending_finish = Some(PendingFinish {
            measured,
            snapshot,
            start_wall_ms,
            end_wall_ms,
            events,
            stopped_at,
        });
        self.step_result(None, false, true)
    }

    pub fn complete_finish(
        &mut self,
        end_energy: Option<EnergySnapshot>,
        arrival: Option<&ArrivedPlace>,
        address: Option<&str>,
        address_updated_at_ms: Option<i64>,
        now_ms: i64,
    ) -> EngineStepResult {
        let Some(pending) = self.pending_finish.take() else {
            return self.step_result(None, false, false);
        };
        let start_energy = self.trip_start_energy;
        let efficiency = EnergyMeter::measure(
            start_energy.as_ref(),
            end_energy.as_ref(),
            pending.measured.distance_km,
        );
        let history = self.same_mode(pending.measured.simulated);
        let mut result = EnergyMeter::apply(
            &pending.measured,
            efficiency.as_ref(),
            &history,
            &ScoreConfig::default(),
        );
        result.events = pending.events.clone();
        let eco_delta = Coaching::eco_delta(&result, &history);
        self.trips.push(result.clone());

        let stamp = pending.stopped_at.and_then(|pt| {
            let perfect =
                result.harsh_accel_count + result.harsh_brake_count + result.harsh_steer_count == 0;
            let place_name = StampNameResolver::resolve(
                arrival,
                address,
                address_updated_at_ms,
                pt,
                pending.start_wall_ms,
                now_ms,
            );
            self.stamp_book.try_add(
                pt,
                result.end_time_ms,
                result.score,
                place_name.as_deref(),
                perfect,
            )
        });
        self.persist();
        self.last_finished = Some(FinishedTrip {
            result: Some(result.clone()),
            snapshot: pending.snapshot,
            start_wall_ms: pending.start_wall_ms,
            end_wall_ms: pending.end_wall_ms,
            start_energy,
            events: pending.events.clone(),
            stamp: stamp.clone(),
            stopped_at: pending.stopped_at,
        });
        let marks = TripContinuation::marks(&pending.events, pending.start_wall_ms, pending.end_wall_ms);
        self.last_result = Some(result.clone());
        self.last_trip_events = pending.events;
        self.last_trip_marks = marks;
        self.new_stamp = stamp;
        self.last_eco_delta = eco_delta;
        self.past_report = None;
        self.recompute_local_league(now_ms);

        let speak = if self.voice_guide && !self.recorder.is_active() {
            Some(Coaching::parking_summary(&result, eco_delta))
        } else {
            None
        };
        self.step_result(speak, false, false)
    }

    pub fn save_stamp(&mut self, id: i32, name: &str, memo: &str, tags: &[String]) -> EngineSnapshot {
        if let Some(updated) = self.stamp_book.update(id, name, memo, tags) {
            self.persist();
            if self.new_stamp.as_ref().map(|s| s.id) == Some(id) {
                self.new_stamp = Some(updated);
            }
        }
        self.snapshot()
    }

    pub fn open_trip(&mut self, start_time_ms: i64, end_time_ms: i64) -> EngineSnapshot {
        if let Some(index) = self
            .trips
            .iter()
            .position(|t| t.start_time_ms == start_time_ms && t.end_time_ms == end_time_ms)
        {
            let trip = self.trips[index].clone();
            let before: Vec<TripResult> = self.trips[..index]
                .iter()
                .filter(|t| t.simulated == trip.simulated)
                .cloned()
                .collect();
            let place_name = self
                .stamp_book
                .stamps()
                .iter()
                .find(|s| s.time_ms == trip.end_time_ms)
                .map(|s| s.name.clone());
            self.past_report = Some(PastReport {
                marks: TripContinuation::marks(&trip.events, trip.start_time_ms, trip.end_time_ms),
                eco_delta: Coaching::eco_delta(&trip, &before),
                place_name,
                result: trip,
            });
        }
        self.snapshot()
    }

    pub fn delete_trip(&mut self, start_time_ms: i64, end_time_ms: i64, now_ms: i64) -> EngineSnapshot {
        if let Some(index) = self
            .trips
            .iter()
            .position(|t| t.start_time_ms == start_time_ms && t.end_time_ms == end_time_ms)
        {
            self.trips[index].hidden = true;
            self.persist();
            self.past_report = None;
            self.notice = Some("주행 기록을 지웠어요 (평균 점수와 리그에는 그대로 남아요)".to_string());
            self.recompute_local_league(now_ms);
        }
        self.snapshot()
    }

    pub fn close_trip(&mut self) -> EngineSnapshot {
        self.past_report = None;
        self.snapshot()
    }

    pub fn dismiss_result(&mut self) -> EngineSnapshot {
        self.last_result = None;
        self.new_stamp = None;
        self.notice = None;
        self.snapshot()
    }

    pub fn set_notice(&mut self, notice: Option<String>) -> EngineSnapshot {
        self.notice = notice;
        self.snapshot()
    }

    pub fn navigate_message(&mut self, stamp_id: i32, sent: bool) -> EngineSnapshot {
        if let Some(stamp) = self.stamp_book.stamps().iter().find(|s| s.id == stamp_id) {
            let msg = if !sent {
                "내비에 연결되지 않아 길 안내를 시작하지 못했어요".to_string()
            } else if self.simulation_mode {
                format!(
                    "시뮬레이션: 목적지를 {}(으)로 정했어요. 출발 후 주차하면 도착해요",
                    stamp.name
                )
            } else {
                format!("{}(으)로 길 안내를 요청했어요", stamp.name)
            };
            self.notice = Some(msg);
        }
        self.snapshot()
    }

    pub fn validate_token(token: &str) -> Option<String> {
        let value = token.trim();
        if value.is_empty()
            || value.chars().count() > 200
            || value.chars().any(|c| !matches!(c as u32, 33..=126))
        {
            None
        } else {
            Some(value.to_string())
        }
    }

    pub fn latest_trip_for_upload(&self, now_ms: i64) -> Result<Option<TripUploadDto>, &'static str> {
        let Some(trip) = self
            .trips
            .iter()
            .rev()
            .find(|t| t.simulated == self.simulation_mode)
        else {
            return Ok(None);
        };
        if League::week_start_ms(trip.end_time_ms, Self::SEOUL_ZONE)
            != League::week_start_ms(now_ms, Self::SEOUL_ZONE)
        {
            return Err("이번 주에 끝난 주행만 전송할 수 있어요");
        }
        Ok(Some(TripUploadDto::from_trip(trip)))
    }

    fn persist(&self) {
        let _ = self.storage.save(&SavedData {
            trips: self.trips.clone(),
            stamps: self.stamp_book.stamps().to_vec(),
        });
    }
}
