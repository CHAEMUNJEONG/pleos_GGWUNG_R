"""Local-only development league server. Python 3.11+, standard library only."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import secrets
import sqlite3
import time
import uuid
from contextlib import closing
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

KST = timezone(timedelta(hours=9))
SCORE_VERSION = "prototype-v2"
MAX_BODY = 16_384


class ApiError(Exception):
    def __init__(self, status: int, code: str):
        self.status, self.code = status, code


def week_start(ms: int) -> int:
    day = datetime.fromtimestamp(ms / 1000, KST).replace(hour=0, minute=0, second=0, microsecond=0)
    return int((day - timedelta(days=day.weekday())).timestamp() * 1000)


def reward(rank: int, size: int) -> int:
    return 500 if rank <= math.ceil(size * .1) else 200 if rank <= math.ceil(size * .3) else 50


def integer(value, low: int, high: int) -> bool:
    return type(value) is int and low <= value <= high


def number(value, low: float, high: float) -> bool:
    return type(value) in (float, int) and math.isfinite(value) and low <= value <= high


class Store:
    def __init__(self, db_path: Path, clock=lambda: int(time.time() * 1000)):
        self.path, self.clock = db_path, clock
        db_path.parent.mkdir(parents=True, exist_ok=True)
        with closing(self.connect()) as db, db:
            columns = {row[1] for row in db.execute("PRAGMA table_info(trips)")}
            if columns and "simulated" not in columns:
                raise RuntimeError("Legacy v1 database: use a new v2 database; do not mix score versions")
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS users (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT UNIQUE NOT NULL);
                CREATE TABLE IF NOT EXISTS trips (
                    user_id TEXT NOT NULL REFERENCES users(id), trip_id TEXT NOT NULL,
                    end_ms INTEGER NOT NULL, distance_km REAL NOT NULL,
                    score INTEGER NOT NULL, payload TEXT NOT NULL, simulated INTEGER NOT NULL,
                    PRIMARY KEY(user_id, trip_id));
                CREATE INDEX IF NOT EXISTS trip_week ON trips(end_ms);
            """)

    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        return db

    def create_user(self, name: str) -> dict:
        name = name.strip()
        if not name or len(name) > 50 or any(ord(c) < 32 for c in name):
            raise ApiError(422, "invalid_name")
        user_id, token = str(uuid.uuid4()), secrets.token_urlsafe(32)
        with closing(self.connect()) as db, db:
            if db.execute("SELECT COUNT(*) FROM users").fetchone()[0] >= 1000:
                raise ApiError(409, "development_user_limit")
            db.execute("INSERT INTO users VALUES(?,?,?)", (user_id, name, self.token_hash(token)))
        return {"userId": user_id, "name": name, "token": token}

    @staticmethod
    def token_hash(token: str) -> str:
        return hashlib.sha256(token.encode()).hexdigest()

    def authenticate(self, authorization: str | None) -> str:
        if not authorization or not authorization.startswith("Bearer "):
            raise ApiError(401, "authentication_required")
        token = authorization[7:]
        if len(token) > 200:
            raise ApiError(401, "invalid_token")
        with closing(self.connect()) as db:
            row = db.execute("SELECT id FROM users WHERE token_hash=?", (self.token_hash(token),)).fetchone()
        if row is None:
            raise ApiError(401, "invalid_token")
        return row[0]

    def save_trip(self, user: str, data: dict) -> tuple[int, dict]:
        expected = {"tripId", "startTimeMs", "endTimeMs", "distanceKm", "harshAccelCount",
                    "harshBrakeCount", "harshSteerCount", "simulated", "maxSpeedKmh", "ecoScore", "score", "scoringVersion"}
        if type(data) is not dict or set(data) != expected:
            raise ApiError(422, "invalid_trip_fields")
        trip_id = data["tripId"]
        if not isinstance(trip_id, str) or not re.fullmatch(r"[a-zA-Z0-9_-]{1,100}", trip_id):
            raise ApiError(422, "invalid_trip_id")
        # Check existing records before current-week validation: retries remain idempotent after rollover.
        try:
            canonical = json.dumps(data, sort_keys=True, separators=(",", ":"), allow_nan=False)
        except (ValueError, TypeError):
            raise ApiError(422, "invalid_trip_value")
        with closing(self.connect()) as db, db:
            db.execute("BEGIN IMMEDIATE")
            previous = db.execute("SELECT payload,score FROM trips WHERE user_id=? AND trip_id=?", (user, trip_id)).fetchone()
            if previous:
                if previous["payload"] != canonical:
                    raise ApiError(409, "trip_id_conflict")
                return 200, {"tripId": trip_id, "accepted": True, "duplicate": True, "score": previous["score"]}
            now = self.clock()
            start, end = data["startTimeMs"], data["endTimeMs"]
            if not integer(start, 0, now) or not integer(end, 0, now) or not 0 < end - start <= 86_400_000:
                raise ApiError(422, "invalid_trip_time")
            if week_start(end) != week_start(now):
                raise ApiError(422, "only_current_week_accepts_new_trips")
            km, speed = data["distanceKm"], data["maxSpeedKmh"]
            if not number(km, .3, 5000) or not number(speed, 0, 300):
                raise ApiError(422, "invalid_distance_or_speed")
            if type(data["simulated"]) is not bool:
                raise ApiError(422, "invalid_simulation_mode")
            # Demo time advances faster than wall time; it is isolated from the real league.
            if not data["simulated"] and km > speed * (end - start) / 3_600_000 + .05:
                raise ApiError(422, "implausible_trip_distance")
            if not all(integer(data[k], 0, 100000) for k in ("harshAccelCount", "harshBrakeCount", "harshSteerCount")):
                raise ApiError(422, "invalid_event_count")
            if not all(integer(data[k], 0, 100) for k in ("ecoScore", "score")) or data["scoringVersion"] != SCORE_VERSION:
                raise ApiError(422, "invalid_score_or_version")
            penalty = (data["harshAccelCount"] * 4 + data["harshBrakeCount"] * 5 + data["harshSteerCount"] * 4) * 10 / max(km, 10)
            safety = max(0, min(100, math.floor(100 - penalty + .5)))
            score = math.floor(safety * .4 + data["ecoScore"] * .6 + .5)
            if score != data["score"]:
                raise ApiError(422, "score_mismatch")
            db.execute("INSERT INTO trips VALUES(?,?,?,?,?,?,?)", (user, trip_id, end, km, score, canonical, int(data["simulated"])))
        return 201, {"tripId": trip_id, "accepted": True, "duplicate": False, "score": score}

    def _standing(self, db, user: str, week: int, now: int, simulated: bool) -> dict:
        rows = db.execute("""
            SELECT u.id,u.name,SUM(t.score*t.distance_km)/SUM(t.distance_km) AS average,
                   SUM(t.score*t.distance_km) AS weighted,SUM(t.distance_km) AS km,COUNT(*) AS trips
            FROM trips t JOIN users u ON u.id=t.user_id
            WHERE t.end_ms>=? AND t.end_ms<? AND t.end_ms<=? AND t.simulated=? AND t.distance_km>=1
            GROUP BY u.id,u.name
        """, (week, week + 7 * 86_400_000, now, int(simulated))).fetchall()
        qualified = [dict(r, league_score=math.floor((r["weighted"] + 80 * 20) / (r["km"] + 20) * 10 + .5) / 10)
                     for r in rows if r["km"] >= 1]
        qualified.sort(key=lambda r: (-r["league_score"], r["id"]))
        mine = next((r for r in rows if r["id"] == user), None)
        rank = next((i + 1 for i, r in enumerate(qualified) if r["id"] == user), None)
        return {
            "weekStartMs": week, "simulated": simulated,
            "entries": [{"name": r["name"], "averageScore": r["league_score"], "isMe": r["id"] == user} for r in qualified],
            "myRank": rank, "myAverage": mine["average"] if mine else None,
            "myLeagueScore": qualified[rank - 1]["league_score"] if rank else None,
            "myDistanceKm": mine["km"] if mine else 0, "myTripCount": mine["trips"] if mine else 0,
            "rewardPoints": reward(rank, len(qualified)) if rank else 0,
        }

    def weekly(self, user: str, week: int, simulated: bool = False) -> dict:
        now = self.clock()
        if not integer(week, 0, now) or week_start(week) != week:
            raise ApiError(422, "week_must_be_kst_monday_midnight")
        with closing(self.connect()) as db:
            db.execute("BEGIN")  # Rank and cumulative points use one consistent SQLite snapshot.
            result = self._standing(db, user, week, now, simulated)
            previous = db.execute("SELECT DISTINCT end_ms FROM trips WHERE user_id=? AND end_ms<? AND simulated=? AND distance_km>=1",
                                  (user, week_start(now), int(simulated))).fetchall()
            weeks = {week_start(r[0]) for r in previous}
            result["earnedPoints"] = sum(self._standing(db, user, w, now, simulated)["rewardPoints"] for w in weeks)
        return result


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def reject_constant(value):
    raise ValueError("nonfinite JSON number")


def make_server(store: Store, port: int = 8765):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass  # Never write Authorization headers, tokens or personal trip bodies to logs.

        def reply(self, status, body):
            raw = json.dumps(body, ensure_ascii=False, allow_nan=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(raw)

        def dispatch(self):
            try:
                path = urlsplit(self.path)
                if self.command == "GET" and path.path == "/health":
                    return self.reply(200, {"status": "ok", "mode": "local-development"})
                user = store.authenticate(self.headers.get("Authorization"))
                if self.command == "GET" and path.path == "/v1/league/weekly":
                    query = parse_qs(path.query, keep_blank_values=True)
                    values, modes = query.get("weekStartMs", []), query.get("simulated", [])
                    if set(query) != {"weekStartMs", "simulated"} or len(values) != 1 or not values[0].isdigit() or len(values[0]) > 16:
                        raise ApiError(422, "invalid_week_query")
                    if len(modes) != 1 or modes[0] not in ("true", "false"):
                        raise ApiError(422, "invalid_simulation_query")
                    return self.reply(200, store.weekly(user, int(values[0]), modes[0] == "true"))
                if self.command == "POST" and path.path == "/v1/trips":
                    if self.headers.get_content_type() != "application/json":
                        raise ApiError(415, "json_required")
                    if self.headers.get("Transfer-Encoding"):
                        raise ApiError(400, "content_length_required")
                    try:
                        size = int(self.headers.get("Content-Length", "0"))
                    except ValueError:
                        raise ApiError(400, "invalid_content_length")
                    if not 0 < size <= MAX_BODY:
                        raise ApiError(413, "invalid_body_size")
                    self.connection.settimeout(5)
                    body = self.rfile.read(size)
                    if len(body) != size:
                        raise ApiError(400, "incomplete_body")
                    try:
                        data = json.loads(body, object_pairs_hook=unique_object, parse_constant=reject_constant)
                    except (ValueError, UnicodeError):
                        raise ApiError(400, "invalid_json")
                    status, result = store.save_trip(user, data)
                    return self.reply(status, result)
                raise ApiError(404, "not_found")
            except ApiError as error:
                self.reply(error.status, {"error": error.code})
            except (BrokenPipeError, ConnectionResetError):
                pass
            except TimeoutError:
                self.reply(408, {"error": "request_timeout"})
            except Exception:
                self.reply(500, {"error": "internal_error"})

        do_GET = dispatch
        do_POST = dispatch

    return ThreadingHTTPServer(("127.0.0.1", port), Handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", type=Path, default=Path(__file__).parent / "data" / "league-v2.sqlite3")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--ready-file", type=Path, help="Write a loopback endpoint for integration tests")
    parser.add_argument("--create-user", metavar="DISPLAY_NAME", help="Issue a development token; printed once")
    args = parser.parse_args()
    store = Store(args.db)
    if args.create_user:
        print(json.dumps(store.create_user(args.create_user), ensure_ascii=False))
        return
    server = make_server(store, args.port)
    url = f"http://127.0.0.1:{server.server_port}/"
    if args.ready_file:
        staged = args.ready_file.with_suffix(".tmp")
        staged.write_text(json.dumps({"url": url}), encoding="utf-8")
        staged.replace(args.ready_file)
    print(f"Local development league: {url} (Ctrl+C to stop)", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
