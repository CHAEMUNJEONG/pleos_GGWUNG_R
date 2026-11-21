use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::fmt;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine as _;
use kkwongal_core::league::League;
use kkwongal_core::round_half_up_i32;
use rand::RngCore;
use rusqlite::{params, Connection, OptionalExtension};
use serde::de::{DeserializeSeed, Deserializer, MapAccess, SeqAccess, Visitor};
use serde_json::{json, Map, Value};
use sha2::{Digest, Sha256};
use uuid::Uuid;

pub const SCORE_VERSION: &str = "prototype-v2";
pub const MAX_BODY: usize = 16_384;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ApiError {
    pub status: u16,
    pub code: &'static str,
}

impl ApiError {
    pub fn new(status: u16, code: &'static str) -> Self {
        Self { status, code }
    }
}

pub fn week_start(ms: i64) -> i64 {
    League::week_start_ms_kst(ms)
}

pub fn reward(rank: i32, size: i32) -> i32 {
    if rank <= (size as f64 * 0.1).ceil() as i32 {
        500
    } else if rank <= (size as f64 * 0.3).ceil() as i32 {
        200
    } else {
        50
    }
}

pub type ClockFn = Arc<dyn Fn() -> i64 + Send + Sync>;

pub fn system_clock_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}

pub struct Store {
    path: PathBuf,
    clock: ClockFn,
}

impl Store {
    pub fn open(db_path: impl AsRef<Path>, clock: ClockFn) -> Result<Self, String> {
        let path = db_path.as_ref().to_path_buf();
        if let Some(parent) = path.parent() {
            if !parent.as_os_str().is_empty() {
                std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
            }
        }
        let store = Self { path, clock };
        let conn = store.connect().map_err(|e| e.to_string())?;
        let mut stmt = conn
            .prepare("PRAGMA table_info(trips)")
            .map_err(|e| e.to_string())?;
        let columns: BTreeSet<String> = stmt
            .query_map([], |row| row.get::<_, String>(1))
            .map_err(|e| e.to_string())?
            .filter_map(Result::ok)
            .collect();
        drop(stmt);
        if !columns.is_empty() && !columns.contains("simulated") {
            return Err(
                "Legacy v1 database: use a new v2 database; do not mix score versions".to_string(),
            );
        }
        conn.execute_batch(
            "PRAGMA journal_mode=WAL;
             CREATE TABLE IF NOT EXISTS users (
                 id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT UNIQUE NOT NULL);
             CREATE TABLE IF NOT EXISTS trips (
                 user_id TEXT NOT NULL REFERENCES users(id), trip_id TEXT NOT NULL,
                 end_ms INTEGER NOT NULL, distance_km REAL NOT NULL,
                 score INTEGER NOT NULL, payload TEXT NOT NULL, simulated INTEGER NOT NULL,
                 PRIMARY KEY(user_id, trip_id));
             CREATE INDEX IF NOT EXISTS trip_week ON trips(end_ms);",
        )
        .map_err(|e| e.to_string())?;
        Ok(store)
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    fn connect(&self) -> rusqlite::Result<Connection> {
        let conn = Connection::open(&self.path)?;
        conn.busy_timeout(Duration::from_secs(10))?;
        conn.execute_batch("PRAGMA foreign_keys=ON;")?;
        Ok(conn)
    }

    pub fn token_hash(token: &str) -> String {
        let digest = Sha256::digest(token.as_bytes());
        let mut out = String::with_capacity(64);
        for b in digest {
            out.push_str(&format!("{b:02x}"));
        }
        out
    }

    pub fn create_user(&self, name: &str) -> Result<Value, ApiError> {
        let trimmed = name.trim();
        if trimmed.is_empty()
            || trimmed.chars().count() > 50
            || trimmed.chars().any(|c| (c as u32) < 32)
        {
            return Err(ApiError::new(422, "invalid_name"));
        }
        let user_id = Uuid::new_v4().to_string();
        let mut random_bytes = [0u8; 32];
        rand::thread_rng().fill_bytes(&mut random_bytes);
        let token = URL_SAFE_NO_PAD.encode(random_bytes);
        let hash = Self::token_hash(&token);

        let conn = self
            .connect()
            .map_err(|_| ApiError::new(500, "internal_error"))?;
        let count: i64 = conn
            .query_row("SELECT COUNT(*) FROM users", [], |row| row.get(0))
            .map_err(|_| ApiError::new(500, "internal_error"))?;
        if count >= 1000 {
            return Err(ApiError::new(409, "development_user_limit"));
        }
        conn.execute(
            "INSERT INTO users VALUES(?,?,?)",
            params![user_id, trimmed, hash],
        )
        .map_err(|_| ApiError::new(500, "internal_error"))?;

        Ok(json!({
            "userId": user_id,
            "name": trimmed,
            "token": token,
        }))
    }

    pub fn authenticate(&self, authorization: Option<&str>) -> Result<String, ApiError> {
        let Some(auth) = authorization else {
            return Err(ApiError::new(401, "authentication_required"));
        };
        let Some(token) = auth.strip_prefix("Bearer ") else {
            return Err(ApiError::new(401, "authentication_required"));
        };
        if token.chars().count() > 200 {
            return Err(ApiError::new(401, "invalid_token"));
        }
        let hash = Self::token_hash(token);
        let conn = self
            .connect()
            .map_err(|_| ApiError::new(500, "internal_error"))?;
        let user_id: Option<String> = conn
            .query_row(
                "SELECT id FROM users WHERE token_hash=?",
                params![hash],
                |row| row.get(0),
            )
            .optional()
            .map_err(|_| ApiError::new(500, "internal_error"))?;
        user_id.ok_or_else(|| ApiError::new(401, "invalid_token"))
    }

    pub fn save_trip(&self, user: &str, data: &Value) -> Result<(u16, Value), ApiError> {
        let Some(obj) = data.as_object() else {
            return Err(ApiError::new(422, "invalid_trip_fields"));
        };
        const EXPECTED: [&str; 12] = [
            "distanceKm",
            "ecoScore",
            "endTimeMs",
            "harshAccelCount",
            "harshBrakeCount",
            "harshSteerCount",
            "maxSpeedKmh",
            "score",
            "scoringVersion",
            "simulated",
            "startTimeMs",
            "tripId",
        ];
        if obj.len() != EXPECTED.len() || !EXPECTED.iter().all(|k| obj.contains_key(*k)) {
            return Err(ApiError::new(422, "invalid_trip_fields"));
        }
        let Some(trip_id) = obj.get("tripId").and_then(Value::as_str) else {
            return Err(ApiError::new(422, "invalid_trip_id"));
        };
        if trip_id.is_empty()
            || trip_id.len() > 100
            || !trip_id
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
        {
            return Err(ApiError::new(422, "invalid_trip_id"));
        }

        // Canonical sorted compact JSON (serde_json Map uses BTreeMap by default).
        let canonical =
            serde_json::to_string(data).map_err(|_| ApiError::new(422, "invalid_trip_value"))?;

        let mut conn = self
            .connect()
            .map_err(|_| ApiError::new(500, "internal_error"))?;
        let tx = conn
            .transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)
            .map_err(|_| ApiError::new(500, "internal_error"))?;

        let previous: Option<(String, i64)> = tx
            .query_row(
                "SELECT payload,score FROM trips WHERE user_id=? AND trip_id=?",
                params![user, trip_id],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .optional()
            .map_err(|_| ApiError::new(500, "internal_error"))?;

        if let Some((prev_payload, prev_score)) = previous {
            if prev_payload != canonical {
                return Err(ApiError::new(409, "trip_id_conflict"));
            }
            return Ok((
                200,
                json!({
                    "tripId": trip_id,
                    "accepted": true,
                    "duplicate": true,
                    "score": prev_score,
                }),
            ));
        }

        let now = (self.clock)();
        let start = strict_int(obj.get("startTimeMs"), 0, now)
            .ok_or_else(|| ApiError::new(422, "invalid_trip_time"))?;
        let end = strict_int(obj.get("endTimeMs"), 0, now)
            .ok_or_else(|| ApiError::new(422, "invalid_trip_time"))?;
        if end <= start || end - start > 86_400_000 {
            return Err(ApiError::new(422, "invalid_trip_time"));
        }
        if week_start(end) != week_start(now) {
            return Err(ApiError::new(422, "only_current_week_accepts_new_trips"));
        }

        let km = strict_number(obj.get("distanceKm"), 0.3, 5000.0)
            .ok_or_else(|| ApiError::new(422, "invalid_distance_or_speed"))?;
        let speed = strict_number(obj.get("maxSpeedKmh"), 0.0, 300.0)
            .ok_or_else(|| ApiError::new(422, "invalid_distance_or_speed"))?;
        let Some(simulated) = obj.get("simulated").and_then(Value::as_bool) else {
            return Err(ApiError::new(422, "invalid_simulation_mode"));
        };
        if !simulated && km > speed * (end - start) as f64 / 3_600_000.0 + 0.05 {
            return Err(ApiError::new(422, "implausible_trip_distance"));
        }

        let accel = strict_int(obj.get("harshAccelCount"), 0, 100_000)
            .ok_or_else(|| ApiError::new(422, "invalid_event_count"))?;
        let brake = strict_int(obj.get("harshBrakeCount"), 0, 100_000)
            .ok_or_else(|| ApiError::new(422, "invalid_event_count"))?;
        let steer = strict_int(obj.get("harshSteerCount"), 0, 100_000)
            .ok_or_else(|| ApiError::new(422, "invalid_event_count"))?;

        let eco = strict_int(obj.get("ecoScore"), 0, 100)
            .ok_or_else(|| ApiError::new(422, "invalid_score_or_version"))?;
        let reported_score = strict_int(obj.get("score"), 0, 100)
            .ok_or_else(|| ApiError::new(422, "invalid_score_or_version"))?;
        let version = obj.get("scoringVersion").and_then(Value::as_str);
        if version != Some(SCORE_VERSION) {
            return Err(ApiError::new(422, "invalid_score_or_version"));
        }

        let penalty = (accel * 4 + brake * 5 + steer * 4) as f64 * 10.0 / km.max(10.0);
        let safety = round_half_up_i32(100.0 - penalty).clamp(0, 100);
        let score = round_half_up_i32(safety as f64 * 0.4 + eco as f64 * 0.6);
        if score as i64 != reported_score {
            return Err(ApiError::new(422, "score_mismatch"));
        }

        tx.execute(
            "INSERT INTO trips VALUES(?,?,?,?,?,?,?)",
            params![user, trip_id, end, km, score, canonical, if simulated { 1 } else { 0 }],
        )
        .map_err(|_| ApiError::new(500, "internal_error"))?;
        tx.commit()
            .map_err(|_| ApiError::new(500, "internal_error"))?;

        Ok((
            201,
            json!({
                "tripId": trip_id,
                "accepted": true,
                "duplicate": false,
                "score": score,
            }),
        ))
    }

    fn standing_in_tx(
        &self,
        conn: &Connection,
        user: &str,
        week: i64,
        now: i64,
        simulated: bool,
    ) -> Result<Value, ApiError> {
        struct RowData {
            id: String,
            name: String,
            average: f64,
            km: f64,
            trips: i64,
            league_score: f64,
        }

        let mut stmt = conn
            .prepare(
                "SELECT u.id, u.name, SUM(t.score*t.distance_km)/SUM(t.distance_km) AS average,
                        SUM(t.score*t.distance_km) AS weighted, SUM(t.distance_km) AS km, COUNT(*) AS trips
                 FROM trips t JOIN users u ON u.id=t.user_id
                 WHERE t.end_ms>=? AND t.end_ms<? AND t.end_ms<=? AND t.simulated=? AND t.distance_km>=1
                 GROUP BY u.id, u.name",
            )
            .map_err(|_| ApiError::new(500, "internal_error"))?;

        let rows: Vec<RowData> = stmt
            .query_map(
                params![week, week + 7 * 86_400_000, now, if simulated { 1 } else { 0 }],
                |row| {
                    let weighted: f64 = row.get(3)?;
                    let km: f64 = row.get(4)?;
                    let league_score =
                        round_half_up_i32((weighted + 80.0 * 20.0) / (km + 20.0) * 10.0) as f64
                            / 10.0;
                    Ok(RowData {
                        id: row.get(0)?,
                        name: row.get(1)?,
                        average: row.get(2)?,
                        km,
                        trips: row.get(5)?,
                        league_score,
                    })
                },
            )
            .map_err(|_| ApiError::new(500, "internal_error"))?
            .collect::<Result<Vec<_>, _>>()
            .map_err(|_| ApiError::new(500, "internal_error"))?;

        let mut qualified: Vec<&RowData> = rows.iter().filter(|r| r.km >= 1.0).collect();
        qualified.sort_by(|a, b| {
            b.league_score
                .total_cmp(&a.league_score)
                .then_with(|| a.id.cmp(&b.id))
        });

        let mine = rows.iter().find(|r| r.id == user);
        let rank = qualified
            .iter()
            .position(|r| r.id == user)
            .map(|i| (i + 1) as i32);
        let entries: Vec<Value> = qualified
            .iter()
            .map(|r| {
                json!({
                    "name": r.name,
                    "averageScore": r.league_score,
                    "isMe": r.id == user,
                })
            })
            .collect();
        let my_league_score = rank.map(|r| qualified[(r - 1) as usize].league_score);
        let reward_points = rank
            .map(|r| reward(r, qualified.len() as i32))
            .unwrap_or(0);

        Ok(json!({
            "weekStartMs": week,
            "simulated": simulated,
            "entries": entries,
            "myRank": rank,
            "myAverage": mine.map(|m| m.average),
            "myLeagueScore": my_league_score,
            "myDistanceKm": mine.map(|m| m.km).unwrap_or(0.0),
            "myTripCount": mine.map(|m| m.trips).unwrap_or(0),
            "rewardPoints": reward_points,
        }))
    }

    pub fn weekly(&self, user: &str, week: i64, simulated: bool) -> Result<Value, ApiError> {
        let now = (self.clock)();
        if week < 0 || week > now || week_start(week) != week {
            return Err(ApiError::new(422, "week_must_be_kst_monday_midnight"));
        }
        let conn = self
            .connect()
            .map_err(|_| ApiError::new(500, "internal_error"))?;
        conn.execute_batch("BEGIN")
            .map_err(|_| ApiError::new(500, "internal_error"))?;
        let res = (|| -> Result<Value, ApiError> {
            let mut result = self.standing_in_tx(&conn, user, week, now, simulated)?;
            let mut stmt = conn
                .prepare(
                    "SELECT DISTINCT end_ms FROM trips
                     WHERE user_id=? AND end_ms<? AND simulated=? AND distance_km>=1",
                )
                .map_err(|_| ApiError::new(500, "internal_error"))?;
            let end_times: Vec<i64> = stmt
                .query_map(
                    params![user, week_start(now), if simulated { 1 } else { 0 }],
                    |row| row.get(0),
                )
                .map_err(|_| ApiError::new(500, "internal_error"))?
                .collect::<Result<Vec<_>, _>>()
                .map_err(|_| ApiError::new(500, "internal_error"))?;
            let weeks: BTreeSet<i64> = end_times.into_iter().map(week_start).collect();
            let mut earned: i64 = 0;
            for w in weeks {
                let st = self.standing_in_tx(&conn, user, w, now, simulated)?;
                earned += st.get("rewardPoints").and_then(Value::as_i64).unwrap_or(0);
            }
            if let Some(obj) = result.as_object_mut() {
                obj.insert("earnedPoints".into(), Value::from(earned));
            }
            Ok(result)
        })();
        let _ = conn.execute_batch("COMMIT");
        res
    }
}

fn strict_int(v: Option<&Value>, low: i64, high: i64) -> Option<i64> {
    let n = v?.as_number()?;
    let i = n.as_i64()?;
    if (low..=high).contains(&i) {
        Some(i)
    } else {
        None
    }
}

fn strict_number(v: Option<&Value>, low: f64, high: f64) -> Option<f64> {
    let n = v?.as_number()?;
    let f = n.as_f64()?;
    if f.is_finite() && (low..=high).contains(&f) {
        Some(f)
    } else {
        None
    }
}

/// Strict JSON parser that rejects duplicate object keys and non-finite numbers.
pub fn parse_strict_json(bytes: &[u8]) -> Result<Value, String> {
    let text = std::str::from_utf8(bytes).map_err(|e| e.to_string())?;
    let mut de = serde_json::Deserializer::from_str(text);
    let val = StrictValueSeed
        .deserialize(&mut de)
        .map_err(|e| e.to_string())?;
    de.end().map_err(|e| e.to_string())?;
    Ok(val)
}

struct StrictValueSeed;

impl<'de> DeserializeSeed<'de> for StrictValueSeed {
    type Value = Value;

    fn deserialize<D>(self, deserializer: D) -> Result<Value, D::Error>
    where
        D: Deserializer<'de>,
    {
        deserializer.deserialize_any(StrictValueVisitor)
    }
}

struct StrictValueVisitor;

impl<'de> Visitor<'de> for StrictValueVisitor {
    type Value = Value;

    fn expecting(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("any valid JSON value without duplicate object keys")
    }

    fn visit_bool<E>(self, v: bool) -> Result<Value, E> {
        Ok(Value::Bool(v))
    }

    fn visit_i64<E>(self, v: i64) -> Result<Value, E> {
        Ok(Value::from(v))
    }

    fn visit_u64<E>(self, v: u64) -> Result<Value, E> {
        Ok(Value::from(v))
    }

    fn visit_f64<E>(self, v: f64) -> Result<Value, E>
    where
        E: serde::de::Error,
    {
        serde_json::Number::from_f64(v)
            .map(Value::Number)
            .ok_or_else(|| E::custom("nonfinite JSON number"))
    }

    fn visit_str<E>(self, v: &str) -> Result<Value, E> {
        Ok(Value::String(v.to_string()))
    }

    fn visit_string<E>(self, v: String) -> Result<Value, E> {
        Ok(Value::String(v))
    }

    fn visit_none<E>(self) -> Result<Value, E> {
        Ok(Value::Null)
    }

    fn visit_unit<E>(self) -> Result<Value, E> {
        Ok(Value::Null)
    }

    fn visit_seq<A>(self, mut seq: A) -> Result<Value, A::Error>
    where
        A: SeqAccess<'de>,
    {
        let mut vec = Vec::new();
        while let Some(elem) = seq.next_element_seed(StrictValueSeed)? {
            vec.push(elem);
        }
        Ok(Value::Array(vec))
    }

    fn visit_map<A>(self, mut access: A) -> Result<Value, A::Error>
    where
        A: MapAccess<'de>,
    {
        let mut map = Map::new();
        while let Some(key) = access.next_key::<String>()? {
            if map.contains_key(&key) {
                return Err(serde::de::Error::custom("duplicate JSON key"));
            }
            let val = access.next_value_seed(StrictValueSeed)?;
            map.insert(key, val);
        }
        Ok(Value::Object(map))
    }
}

pub struct LeagueHttpServer {
    addr: SocketAddr,
    stop: Arc<AtomicBool>,
    handle: Option<thread::JoinHandle<()>>,
}

impl LeagueHttpServer {
    pub fn start(store: Arc<Store>, port: u16) -> Result<Self, String> {
        let listener =
            TcpListener::bind(("127.0.0.1", port)).map_err(|e| format!("bind failed: {e}"))?;
        let addr = listener.local_addr().map_err(|e| e.to_string())?;
        listener
            .set_nonblocking(true)
            .map_err(|e| e.to_string())?;
        let stop = Arc::new(AtomicBool::new(false));
        let stop_flag = Arc::clone(&stop);

        let handle = thread::spawn(move || {
            while !stop_flag.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((stream, _)) => {
                        let st = Arc::clone(&store);
                        thread::spawn(move || handle_connection(stream, &st));
                    }
                    Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(10));
                    }
                    Err(_) => break,
                }
            }
        });

        Ok(Self {
            addr,
            stop,
            handle: Some(handle),
        })
    }

    pub fn port(&self) -> u16 {
        self.addr.port()
    }

    pub fn url(&self) -> String {
        format!("http://127.0.0.1:{}/", self.addr.port())
    }

    pub fn shutdown(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(h) = self.handle.take() {
            let _ = h.join();
        }
    }
}

impl Drop for LeagueHttpServer {
    fn drop(&mut self) {
        self.shutdown();
    }
}

fn handle_connection(stream: TcpStream, store: &Store) {
    let _ = stream.set_nonblocking(false);
    let _ = stream.set_read_timeout(Some(Duration::from_secs(5)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(5)));

    let mut writer = match stream.try_clone() {
        Ok(s) => s,
        Err(_) => return,
    };
    let mut reader = BufReader::new(stream);

    let result = process_request(&mut reader, store);
    match result {
        Ok((status, body)) => write_json_response(&mut writer, status, &body),
        Err(err) => write_json_response(&mut writer, err.status, &json!({ "error": err.code })),
    }
    // Half-close write side so the peer reliably reads early error responses (e.g. 413)
    // before Windows closesocket() could send TCP RST when unread request body remains.
    let _ = writer.shutdown(std::net::Shutdown::Write);
    let _ = writer.set_read_timeout(Some(Duration::from_millis(200)));
    let mut drain = [0u8; 4096];
    let mut drained = 0usize;
    while drained < 65_536 {
        match reader.read(&mut drain) {
            Ok(0) | Err(_) => break,
            Ok(n) => drained += n,
        }
    }
}

fn process_request(reader: &mut BufReader<TcpStream>, store: &Store) -> Result<(u16, Value), ApiError> {
    let mut request_line = String::new();
    match reader.read_line(&mut request_line) {
        Ok(0) => return Err(ApiError::new(400, "empty_request")),
        Ok(_) => {}
        Err(e) if is_timeout(&e) => return Err(ApiError::new(408, "request_timeout")),
        Err(_) => return Err(ApiError::new(400, "invalid_request")),
    }
    let parts: Vec<&str> = request_line.trim_end_matches(['\r', '\n']).split_whitespace().collect();
    if parts.len() < 2 {
        return Err(ApiError::new(400, "invalid_request"));
    }
    let method = parts[0];
    let target = parts[1];

    let mut headers: HashMap<String, String> = HashMap::new();
    let mut header_count = 0usize;
    loop {
        let mut line = String::new();
        match reader.read_line(&mut line) {
            Ok(0) => break,
            Ok(_) => {}
            Err(e) if is_timeout(&e) => return Err(ApiError::new(408, "request_timeout")),
            Err(_) => return Err(ApiError::new(400, "invalid_request")),
        }
        let trimmed = line.trim_end_matches(['\r', '\n']);
        if trimmed.is_empty() {
            break;
        }
        header_count += 1;
        if header_count > 100 {
            return Err(ApiError::new(400, "too_many_headers"));
        }
        if let Some((name, val)) = trimmed.split_once(':') {
            headers.insert(name.trim().to_ascii_lowercase(), val.trim().to_string());
        }
    }

    let (path, query_str) = match target.split_once('?') {
        Some((p, q)) => (p, q),
        None => (target, ""),
    };

    if method == "GET" && path == "/health" {
        return Ok((200, json!({ "status": "ok", "mode": "local-development" })));
    }

    let user = store.authenticate(headers.get("authorization").map(String::as_str))?;

    if method == "GET" && path == "/v1/league/weekly" {
        let query = parse_query_strict(query_str);
        let keys: BTreeSet<&str> = query.keys().map(String::as_str).collect();
        let expected_keys: BTreeSet<&str> = BTreeSet::from(["weekStartMs", "simulated"]);
        let week_vals = query.get("weekStartMs");
        let mode_vals = query.get("simulated");

        if keys != expected_keys {
            return Err(ApiError::new(422, "invalid_week_query"));
        }
        let wv = week_vals.ok_or_else(|| ApiError::new(422, "invalid_week_query"))?;
        if wv.len() != 1
            || wv[0].is_empty()
            || wv[0].len() > 16
            || !wv[0].bytes().all(|b| b.is_ascii_digit())
        {
            return Err(ApiError::new(422, "invalid_week_query"));
        }
        let mv = mode_vals.ok_or_else(|| ApiError::new(422, "invalid_simulation_query"))?;
        if mv.len() != 1 || (mv[0] != "true" && mv[0] != "false") {
            return Err(ApiError::new(422, "invalid_simulation_query"));
        }
        let week_ms: i64 = wv[0]
            .parse()
            .map_err(|_| ApiError::new(422, "invalid_week_query"))?;
        let simulated = mv[0] == "true";
        let payload = store.weekly(&user, week_ms, simulated)?;
        return Ok((200, payload));
    }

    if method == "POST" && path == "/v1/trips" {
        let content_type = headers
            .get("content-type")
            .map(|s| s.split(';').next().unwrap_or("").trim().to_ascii_lowercase())
            .unwrap_or_default();
        if content_type != "application/json" {
            return Err(ApiError::new(415, "json_required"));
        }
        if headers.contains_key("transfer-encoding") {
            return Err(ApiError::new(400, "content_length_required"));
        }
        let size_str = headers
            .get("content-length")
            .map(String::as_str)
            .unwrap_or("0");
        let size: i64 = size_str
            .parse()
            .map_err(|_| ApiError::new(400, "invalid_content_length"))?;
        if size <= 0 || size > MAX_BODY as i64 {
            return Err(ApiError::new(413, "invalid_body_size"));
        }
        let mut body = vec![0u8; size as usize];
        match reader.read_exact(&mut body) {
            Ok(()) => {}
            Err(e) if is_timeout(&e) => return Err(ApiError::new(408, "request_timeout")),
            Err(_) => return Err(ApiError::new(400, "incomplete_body")),
        }
        let data = parse_strict_json(&body).map_err(|_| ApiError::new(400, "invalid_json"))?;
        return store.save_trip(&user, &data);
    }

    Err(ApiError::new(404, "not_found"))
}

fn parse_query_strict(query: &str) -> BTreeMap<String, Vec<String>> {
    let mut map: BTreeMap<String, Vec<String>> = BTreeMap::new();
    if query.is_empty() {
        return map;
    }
    for pair in query.split('&') {
        if pair.is_empty() {
            continue;
        }
        let (k, v) = match pair.split_once('=') {
            Some((k, v)) => (percent_decode(k), percent_decode(v)),
            None => (percent_decode(pair), String::new()),
        };
        map.entry(k).or_default().push(v);
    }
    map
}

fn percent_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let (Some(hi), Some(lo)) = (hex_val(bytes[i + 1]), hex_val(bytes[i + 2])) {
                out.push((hi << 4) | lo);
                i += 3;
                continue;
            }
        } else if bytes[i] == b'+' {
            out.push(b' ');
            i += 1;
            continue;
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).to_string()
}

fn hex_val(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

fn is_timeout(e: &std::io::Error) -> bool {
    matches!(
        e.kind(),
        std::io::ErrorKind::TimedOut | std::io::ErrorKind::WouldBlock
    )
}

fn status_reason(status: u16) -> &'static str {
    match status {
        200 => "OK",
        201 => "Created",
        400 => "Bad Request",
        401 => "Unauthorized",
        404 => "Not Found",
        408 => "Request Timeout",
        409 => "Conflict",
        413 => "Payload Too Large",
        415 => "Unsupported Media Type",
        422 => "Unprocessable Entity",
        _ => "Internal Server Error",
    }
}

fn write_json_response(stream: &mut TcpStream, status: u16, body: &Value) {
    let raw = serde_json::to_vec(body).unwrap_or_else(|_| b"{\"error\":\"internal_error\"}".to_vec());
    let header = format!(
        "HTTP/1.1 {} {}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: {}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n",
        status,
        status_reason(status),
        raw.len()
    );
    let _ = stream.write_all(header.as_bytes());
    let _ = stream.write_all(&raw);
    let _ = stream.flush();
}
