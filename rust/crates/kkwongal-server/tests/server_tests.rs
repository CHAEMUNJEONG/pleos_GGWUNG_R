use std::io::{Read, Write};
use std::net::TcpStream;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::Arc;
use std::thread;

use kkwongal_core::league::League;
use kkwongal_core::league_client::{LeagueClient, LeagueReply, TripUploadDto, UploadReply};
use kkwongal_core::models::TripResult;
use kkwongal_core::trip_recorder::{ScoreCalculator, ScoreConfig};
use kkwongal_server::{reward, week_start, LeagueHttpServer, Store};
use serde_json::{json, Value};

struct TestEnv {
    _dir: tempfile::TempDir,
    now: Arc<AtomicI64>,
    store: Arc<Store>,
    server: LeagueHttpServer,
    first: Value,
    second: Value,
}

impl TestEnv {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        // 2026-11-18 12:00:00 KST = 2026-11-18 03:00:00 UTC
        // 2026-11-18T03:00:00Z epoch millis:
        let initial_now = 1_794_970_800_000_i64;
        let now = Arc::new(AtomicI64::new(initial_now));
        let clock_ref = Arc::clone(&now);
        let store = Arc::new(
            Store::open(
                dir.path().join("league.sqlite3"),
                Arc::new(move || clock_ref.load(Ordering::SeqCst)),
            )
            .unwrap(),
        );
        let first = store.create_user("첫째(개발)").unwrap();
        let second = store.create_user("둘째(개발)").unwrap();
        let server = LeagueHttpServer::start(Arc::clone(&store), 0).unwrap();
        Self {
            _dir: dir,
            now,
            store,
            server,
            first,
            second,
        }
    }

    fn now_ms(&self) -> i64 {
        self.now.load(Ordering::SeqCst)
    }

    fn advance_ms(&self, delta: i64) {
        self.now.fetch_add(delta, Ordering::SeqCst);
    }

    fn request(
        &self,
        method: &str,
        path: &str,
        data: Option<&Value>,
        account: Option<&Value>,
        raw: Option<&str>,
    ) -> (u16, Value) {
        let mut stream = TcpStream::connect(("127.0.0.1", self.server.port())).unwrap();
        let body_str = match (raw, data) {
            (Some(r), _) => Some(r.to_string()),
            (None, Some(d)) => Some(serde_json::to_string(d).unwrap()),
            _ => None,
        };
        let mut req = format!("{method} {path} HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n");
        if let Some(acc) = account {
            let token = acc["token"].as_str().unwrap();
            req.push_str(&format!("Authorization: Bearer {token}\r\n"));
        }
        if let Some(ref b) = body_str {
            req.push_str(&format!("Content-Length: {}\r\n", b.len()));
        }
        req.push_str("Connection: close\r\n\r\n");
        stream.write_all(req.as_bytes()).unwrap();
        if let Some(ref b) = body_str {
            stream.write_all(b.as_bytes()).unwrap();
        }
        let mut resp = String::new();
        stream.read_to_string(&mut resp).unwrap();
        let (head, body) = resp.split_once("\r\n\r\n").unwrap();
        let status: u16 = head
            .lines()
            .next()
            .unwrap()
            .split_whitespace()
            .nth(1)
            .unwrap()
            .parse()
            .unwrap();
        let parsed: Value = serde_json::from_str(body).unwrap();
        (status, parsed)
    }

    fn trip(&self, trip_id: &str, km: f64, eco: i64, simulated: bool) -> Value {
        let score = (40.0 + eco as f64 * 0.6 + 0.5).floor() as i64;
        let now = self.now_ms();
        json!({
            "tripId": trip_id,
            "startTimeMs": now - 3_600_000,
            "endTimeMs": now - 1_000,
            "distanceKm": km,
            "harshAccelCount": 0,
            "harshBrakeCount": 0,
            "harshSteerCount": 0,
            "simulated": simulated,
            "maxSpeedKmh": 80.0,
            "ecoScore": eco,
            "score": score,
            "scoringVersion": "prototype-v2"
        })
    }

    fn weekly(&self, account: &Value, simulated: bool) -> (u16, Value) {
        let path = format!(
            "/v1/league/weekly?weekStartMs={}&simulated={}",
            week_start(self.now_ms()),
            if simulated { "true" } else { "false" }
        );
        self.request("GET", &path, None, Some(account), None)
    }
}

#[test]
fn test_health_and_authentication() {
    let env = TestEnv::new();
    assert_eq!(200, env.request("GET", "/health", None, None, None).0);
    assert_eq!(401, env.request("GET", "/v1/league/weekly", None, None, None).0);
    assert_eq!(401, env.weekly(&json!({"token": "wrong"}), false).0);
}

#[test]
fn test_empty_then_two_real_users_rank_by_average() {
    let env = TestEnv::new();
    assert_eq!(json!([]), env.weekly(&env.first, false).1["entries"]);
    assert_eq!(201, env.request("POST", "/v1/trips", Some(&env.trip("trip-1", 2.0, 100, false)), Some(&env.first), None).0);
    assert_eq!(201, env.request("POST", "/v1/trips", Some(&env.trip("trip-1", 2.0, 0, false)), Some(&env.second), None).0);
    let first = env.weekly(&env.first, false).1;
    let second = env.weekly(&env.second, false).1;
    assert_eq!(1, first["myRank"]);
    assert_eq!(2, second["myRank"]);
    assert_eq!(true, second["entries"][1]["isMe"]);
    assert_eq!(false, second["entries"][0]["isMe"]);
    assert_eq!(500, first["rewardPoints"]);
    assert_eq!(0, first["earnedPoints"]);
}

#[test]
fn test_duplicate_and_conflicting_payload() {
    let env = TestEnv::new();
    let t = env.trip("trip-1", 2.0, 100, false);
    assert_eq!(201, env.request("POST", "/v1/trips", Some(&t), Some(&env.first), None).0);
    let dup = env.request("POST", "/v1/trips", Some(&t), Some(&env.first), None);
    assert_eq!((200, json!(true)), (dup.0, dup.1["duplicate"].clone()));
    let conflict = env.trip("trip-1", 3.0, 100, false);
    assert_eq!(409, env.request("POST", "/v1/trips", Some(&conflict), Some(&env.first), None).0);
    assert_eq!(1, env.weekly(&env.first, false).1["myTripCount"]);
}

#[test]
fn test_concurrent_duplicate_is_counted_once() {
    let env = Arc::new(TestEnv::new());
    let handles: Vec<_> = (0..5)
        .map(|_| {
            let e = Arc::clone(&env);
            thread::spawn(move || {
                let t = e.trip("trip-1", 2.0, 100, false);
                e.request("POST", "/v1/trips", Some(&t), Some(&e.first), None).0
            })
        })
        .collect();
    let replies: Vec<u16> = handles.into_iter().map(|h| h.join().unwrap()).collect();
    assert_eq!(1, replies.iter().filter(|&&c| c == 201).count());
    assert_eq!(4, replies.iter().filter(|&&c| c == 200).count());
    assert_eq!(1, env.weekly(&env.first, false).1["myTripCount"]);
}

#[test]
fn test_same_trip_id_is_scoped_to_user() {
    let env = TestEnv::new();
    let t = env.trip("trip-1", 2.0, 100, false);
    env.request("POST", "/v1/trips", Some(&t), Some(&env.first), None);
    assert_eq!(201, env.request("POST", "/v1/trips", Some(&t), Some(&env.second), None).0);
}

#[test]
fn test_minimum_distance_and_average_not_distance_total() {
    let env = TestEnv::new();
    env.request("POST", "/v1/trips", Some(&env.trip("trip-1", 0.5, 100, false)), Some(&env.first), None);
    assert!(env.weekly(&env.first, false).1["myRank"].is_null());
    env.request("POST", "/v1/trips", Some(&env.trip("trip-2", 0.5, 0, false)), Some(&env.first), None);
    let result = env.weekly(&env.first, false).1;
    assert!(result["myRank"].is_null());
    assert!(result["myAverage"].is_null());
    assert_eq!(0.0, result["myDistanceKm"].as_f64().unwrap());
    env.request("POST", "/v1/trips", Some(&env.trip("trip-3", 1.0, 100, false)), Some(&env.first), None);
    assert_eq!(1, env.weekly(&env.first, false).1["myTripCount"]);
}

#[test]
fn test_week_rollover_earned_points_and_retry() {
    let env = TestEnv::new();
    let mut t = env.trip("trip-1", 2.0, 100, false);
    env.request("POST", "/v1/trips", Some(&t), Some(&env.first), None);
    env.advance_ms(7 * 86_400_000);
    let result = env.weekly(&env.first, false).1;
    assert_eq!(json!([]), result["entries"]);
    assert_eq!(500, result["earnedPoints"]);
    assert_eq!(true, env.request("POST", "/v1/trips", Some(&t), Some(&env.first), None).1["duplicate"]);
    t["tripId"] = json!("late-trip");
    assert_eq!(422, env.request("POST", "/v1/trips", Some(&t), Some(&env.first), None).0);
}

#[test]
fn test_restart_preserves_data_and_token() {
    let env = TestEnv::new();
    env.request("POST", "/v1/trips", Some(&env.trip("trip-1", 2.0, 100, false)), Some(&env.first), None);
    let now = env.now_ms();
    let reopened = Store::open(env.store.path(), Arc::new(move || now)).unwrap();
    let token = env.first["token"].as_str().unwrap();
    let user = reopened.authenticate(Some(&format!("Bearer {token}"))).unwrap();
    assert_eq!(1, reopened.weekly(&user, week_start(now), false).unwrap()["myTripCount"]);
    let raw_bytes = std::fs::read(env.store.path()).unwrap();
    assert!(!raw_bytes.windows(token.len()).any(|w| w == token.as_bytes()));
}

#[test]
fn test_score_is_recalculated_and_invalid_inputs_rejected() {
    let env = TestEnv::new();
    let bad_cases: Vec<(&str, Value)> = vec![
        ("score", json!(99)),
        ("distanceKm", json!(-1)),
        ("distanceKm", json!(true)),
        ("simulated", json!(1)),
        ("harshSteerCount", json!(-1)),
        ("ecoScore", json!(101)),
        ("scoringVersion", json!("unknown")),
        ("startTimeMs", json!(env.now_ms())),
        ("maxSpeedKmh", json!(1)),
    ];
    for (k, v) in bad_cases {
        let mut body = env.trip("trip-1", 2.0, 100, false);
        body[k] = v;
        assert_eq!(422, env.request("POST", "/v1/trips", Some(&body), Some(&env.first), None).0, "key={k}");
    }
    let mut future = env.trip("trip-1", 2.0, 100, false);
    future["endTimeMs"] = json!(env.now_ms() + 1);
    assert_eq!(422, env.request("POST", "/v1/trips", Some(&future), Some(&env.first), None).0);
}

#[test]
fn test_malformed_json_duplicate_keys_nonfinite_and_oversize() {
    let env = TestEnv::new();
    for raw in ["bad json", r#"{"x":1,"x":2}"#, r#"{"x":NaN}"#] {
        assert_eq!(400, env.request("POST", "/v1/trips", None, Some(&env.first), Some(raw)).0, "raw={raw}");
    }
    let big = " ".repeat(17_000);
    assert_eq!(413, env.request("POST", "/v1/trips", None, Some(&env.first), Some(&big)).0);
}

#[test]
fn test_week_query_is_kst_monday() {
    let env = TestEnv::new();
    // 2026-11-16 00:00:00 KST = 2026-11-15 15:00:00 UTC = 1_794_754_800_000
    let expected = 1_794_754_800_000_i64;
    assert_eq!(expected, week_start(env.now_ms()));
    assert_eq!(
        422,
        env.request(
            "GET",
            &format!("/v1/league/weekly?weekStartMs={}&simulated=false", expected + 1),
            None,
            Some(&env.first),
            None
        )
        .0
    );
    assert_eq!(
        422,
        env.request(
            "GET",
            &format!("/v1/league/weekly?weekStartMs={expected}&weekStartMs={expected}&simulated=false"),
            None,
            Some(&env.first),
            None
        )
        .0
    );
}

#[test]
fn test_weighted_average_and_shrinkage() {
    let env = TestEnv::new();
    env.request("POST", "/v1/trips", Some(&env.trip("trip-1", 1.0, 100, false)), Some(&env.first), None);
    env.request("POST", "/v1/trips", Some(&env.trip("long", 9.0, 0, false)), Some(&env.first), None);
    let result = env.weekly(&env.first, false).1;
    assert_eq!(46.0, result["myAverage"].as_f64().unwrap());
    assert_eq!(68.7, result["myLeagueScore"].as_f64().unwrap());
    assert_eq!(68.7, result["entries"][0]["averageScore"].as_f64().unwrap());
}

#[test]
fn test_simulated_and_real_records_and_points_are_separate() {
    let env = TestEnv::new();
    env.request("POST", "/v1/trips", Some(&env.trip("trip-1", 2.0, 100, true)), Some(&env.first), None);
    assert_eq!(json!([]), env.weekly(&env.first, false).1["entries"]);
    assert_eq!(1, env.weekly(&env.first, true).1["myTripCount"]);
    env.advance_ms(7 * 86_400_000);
    assert_eq!(0, env.weekly(&env.first, false).1["earnedPoints"]);
    assert_eq!(500, env.weekly(&env.first, true).1["earnedPoints"]);
}

#[test]
fn test_steering_penalty_uses_latest_score_weights() {
    let env = TestEnv::new();
    let mut body = env.trip("trip-1", 2.0, 100, false);
    body["harshSteerCount"] = json!(1);
    body["score"] = json!(98);
    assert_eq!(201, env.request("POST", "/v1/trips", Some(&body), Some(&env.first), None).0);
}

#[test]
fn test_fast_forward_demo_is_allowed_only_in_simulated_league() {
    let env = TestEnv::new();
    let mut body = env.trip("trip-1", 20.0, 100, false);
    body["startTimeMs"] = json!(env.now_ms() - 90_000);
    assert_eq!(422, env.request("POST", "/v1/trips", Some(&body), Some(&env.first), None).0);
    body["simulated"] = json!(true);
    assert_eq!(201, env.request("POST", "/v1/trips", Some(&body), Some(&env.first), None).0);
}

#[test]
fn test_mode_query_is_required_and_strict() {
    let env = TestEnv::new();
    let prefix = format!("/v1/league/weekly?weekStartMs={}", week_start(env.now_ms()));
    for q in ["", "&simulated=1", "&simulated=false&simulated=true", "&simulated="] {
        assert_eq!(422, env.request("GET", &format!("{prefix}{q}"), None, Some(&env.first), None).0);
    }
}

#[test]
fn test_reward_tiers() {
    assert_eq!(vec![500, 200, 200, 50], [2, 3, 6, 7].into_iter().map(|r| reward(r, 20)).collect::<Vec<_>>());
}

#[test]
fn test_rust_league_client_contract_against_rust_server() {
    let env = TestEnv::new();
    let base = env.server.url();
    let tok1 = env.first["token"].as_str().unwrap().to_string();
    let tok2 = env.second["token"].as_str().unwrap().to_string();
    let c1 = LeagueClient::create(&base, Some(tok1), true).unwrap();
    let c2 = LeagueClient::create(&base, Some(tok2), true).unwrap();

    let now = env.now_ms();
    let week = League::week_start_ms(now, "Asia/Seoul");
    let cfg = ScoreConfig::default();

    for (idx, km) in [1.0, 9.5, 10.0, 10.1, 25.0, 40.2].iter().enumerate() {
        let safety = ScoreCalculator::score(*km, 2, 1, &cfg, 3);
        let eco = if idx % 2 == 0 { 17 } else { 99 };
        let mut t = TripResult::new(
            now - 7_200_000,
            now - 1_000 - idx as i64,
            *km,
            2,
            1,
            80.0,
            ScoreCalculator::total(safety, eco, &cfg),
        );
        t.safety_score = safety;
        t.eco_score = eco;
        t.harsh_steer_count = 3;
        assert_eq!(UploadReply::Success { duplicate: false }, c1.submit(&TripUploadDto::from_trip(&t)));
    }
    let LeagueReply::Success { snapshot } = c1.weekly(week, false) else {
        panic!("expected success");
    };
    assert_eq!(6, snapshot.standing.my_trip_count);

    let mut t2 = TripResult::new(now - 300_000, now - 1_000, 2.0, 0, 0, 80.0, 40);
    t2.safety_score = 100;
    t2.eco_score = 0;
    let dto2 = TripUploadDto::from_trip(&t2);
    assert_eq!(UploadReply::Success { duplicate: false }, c2.submit(&dto2));
    assert_eq!(UploadReply::Success { duplicate: true }, c2.submit(&dto2));
    let LeagueReply::Success { snapshot: s2 } = c2.weekly(week, false) else {
        panic!("expected success");
    };
    assert_eq!(Some(40.0), s2.standing.my_average);
    assert_eq!(Some(76.4), s2.standing.my_league_score);
}
