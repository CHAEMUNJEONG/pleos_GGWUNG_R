import concurrent.futures
import http.client
import json
import tempfile
import threading
import unittest
from datetime import datetime
from pathlib import Path

from server import ApiError, KST, Store, make_server, reward, week_start


class LeagueServerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.now = int(datetime(2026, 11, 18, 12, tzinfo=KST).timestamp() * 1000)
        self.store = Store(Path(self.tmp.name) / "league.sqlite3", lambda: self.now)
        self.first = self.store.create_user("첫째(개발)")
        self.second = self.store.create_user("둘째(개발)")
        self.server = make_server(self.store, 0)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.tmp.cleanup()

    def request(self, method, path, data=None, account=None, raw=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        headers = {"Content-Type": "application/json"}
        if account:
            headers["Authorization"] = "Bearer " + account["token"]
        try:
            conn.request(method, path, raw if raw is not None else (json.dumps(data) if data is not None else None), headers)
            response = conn.getresponse()
            return response.status, json.loads(response.read())
        finally:
            conn.close()

    def trip(self, trip_id="trip-1", km=2.0, eco=100, simulated=False):
        score = int(40 + eco * .6 + .5)
        return {"tripId": trip_id, "startTimeMs": self.now - 3600000, "endTimeMs": self.now - 1000,
                "distanceKm": km, "harshAccelCount": 0, "harshBrakeCount": 0,
                "harshSteerCount": 0, "simulated": simulated,
                "maxSpeedKmh": 80.0, "ecoScore": eco, "score": score, "scoringVersion": "prototype-v2"}

    def weekly(self, account, simulated=False):
        return self.request("GET", f"/v1/league/weekly?weekStartMs={week_start(self.now)}&simulated={str(simulated).lower()}", account=account)

    def test_health_and_authentication(self):
        self.assertEqual(200, self.request("GET", "/health")[0])
        self.assertEqual(401, self.request("GET", "/v1/league/weekly")[0])
        self.assertEqual(401, self.weekly({"token": "wrong"})[0])

    def test_empty_then_two_real_users_rank_by_average(self):
        self.assertEqual([], self.weekly(self.first)[1]["entries"])
        self.assertEqual(201, self.request("POST", "/v1/trips", self.trip(), self.first)[0])
        self.assertEqual(201, self.request("POST", "/v1/trips", self.trip(eco=0), self.second)[0])
        first, second = self.weekly(self.first)[1], self.weekly(self.second)[1]
        self.assertEqual(1, first["myRank"])
        self.assertEqual(2, second["myRank"])
        self.assertTrue(second["entries"][1]["isMe"])
        self.assertFalse(second["entries"][0]["isMe"])
        self.assertEqual(500, first["rewardPoints"])
        self.assertEqual(0, first["earnedPoints"])

    def test_duplicate_and_conflicting_payload(self):
        self.assertEqual(201, self.request("POST", "/v1/trips", self.trip(), self.first)[0])
        duplicate = self.request("POST", "/v1/trips", self.trip(), self.first)
        self.assertEqual((200, True), (duplicate[0], duplicate[1]["duplicate"]))
        self.assertEqual(409, self.request("POST", "/v1/trips", self.trip(km=3), self.first)[0])
        self.assertEqual(1, self.weekly(self.first)[1]["myTripCount"])

    def test_concurrent_duplicate_is_counted_once(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
            replies = list(pool.map(lambda _: self.request("POST", "/v1/trips", self.trip(), self.first)[0], range(5)))
        self.assertEqual(1, replies.count(201))
        self.assertEqual(4, replies.count(200))
        self.assertEqual(1, self.weekly(self.first)[1]["myTripCount"])

    def test_same_trip_id_is_scoped_to_user(self):
        self.request("POST", "/v1/trips", self.trip(), self.first)
        self.assertEqual(201, self.request("POST", "/v1/trips", self.trip(), self.second)[0])

    def test_minimum_distance_and_average_not_distance_total(self):
        self.request("POST", "/v1/trips", self.trip(km=.5), self.first)
        self.assertIsNone(self.weekly(self.first)[1]["myRank"])
        self.request("POST", "/v1/trips", self.trip("trip-2", km=.5, eco=0), self.first)
        result = self.weekly(self.first)[1]
        self.assertIsNone(result["myRank"])
        self.assertIsNone(result["myAverage"])
        self.assertEqual(0, result["myDistanceKm"])
        self.request("POST", "/v1/trips", self.trip("trip-3", km=1), self.first)
        self.assertEqual(1, self.weekly(self.first)[1]["myTripCount"])

    def test_week_rollover_earned_points_and_retry(self):
        trip = self.trip()
        self.request("POST", "/v1/trips", trip, self.first)
        self.now += 7 * 86400000
        result = self.weekly(self.first)[1]
        self.assertEqual([], result["entries"])
        self.assertEqual(500, result["earnedPoints"])
        self.assertTrue(self.request("POST", "/v1/trips", trip, self.first)[1]["duplicate"])
        trip["tripId"] = "late-trip"
        self.assertEqual(422, self.request("POST", "/v1/trips", trip, self.first)[0])

    def test_restart_preserves_data_and_token(self):
        self.request("POST", "/v1/trips", self.trip(), self.first)
        reopened = Store(self.store.path, lambda: self.now)
        user = reopened.authenticate("Bearer " + self.first["token"])
        self.assertEqual(1, reopened.weekly(user, week_start(self.now))["myTripCount"])
        self.assertNotIn(self.first["token"].encode(), self.store.path.read_bytes())

    def test_score_is_recalculated_and_invalid_inputs_rejected(self):
        for key, value in [("score", 99), ("distanceKm", -1), ("distanceKm", True), ("simulated", 1),
                           ("harshSteerCount", -1),
                           ("ecoScore", 101), ("scoringVersion", "unknown"),
                           ("startTimeMs", self.now), ("maxSpeedKmh", 1)]:
            with self.subTest(key=key, value=value):
                body = self.trip()
                body[key] = value
                self.assertEqual(422, self.request("POST", "/v1/trips", body, self.first)[0])
        body = self.trip()
        body["endTimeMs"] = self.now + 1
        self.assertEqual(422, self.request("POST", "/v1/trips", body, self.first)[0])

    def test_malformed_json_duplicate_keys_nonfinite_and_oversize(self):
        for raw in ['bad json', '{"x":1,"x":2}', '{"x":NaN}']:
            self.assertEqual(400, self.request("POST", "/v1/trips", account=self.first, raw=raw)[0])
        self.assertEqual(413, self.request("POST", "/v1/trips", account=self.first, raw=" " * 17000)[0])

    def test_week_query_is_kst_monday(self):
        expected = int(datetime(2026, 11, 16, tzinfo=KST).timestamp() * 1000)
        self.assertEqual(expected, week_start(self.now))
        self.assertEqual(422, self.request("GET", f"/v1/league/weekly?weekStartMs={expected+1}", account=self.first)[0])
        self.assertEqual(422, self.request("GET", f"/v1/league/weekly?weekStartMs={expected}&weekStartMs={expected}", account=self.first)[0])

    def test_weighted_average_and_shrinkage(self):
        self.request("POST", "/v1/trips", self.trip(km=1), self.first)
        self.request("POST", "/v1/trips", self.trip("long", km=9, eco=0), self.first)
        result = self.weekly(self.first)[1]
        self.assertEqual(46, result["myAverage"])
        self.assertEqual(68.7, result["myLeagueScore"])
        self.assertEqual(68.7, result["entries"][0]["averageScore"])

    def test_simulated_and_real_records_and_points_are_separate(self):
        self.request("POST", "/v1/trips", self.trip(simulated=True), self.first)
        self.assertEqual([], self.weekly(self.first)[1]["entries"])
        self.assertEqual(1, self.weekly(self.first, True)[1]["myTripCount"])
        self.now += 7 * 86400000
        self.assertEqual(0, self.weekly(self.first)[1]["earnedPoints"])
        self.assertEqual(500, self.weekly(self.first, True)[1]["earnedPoints"])

    def test_steering_penalty_uses_latest_score_weights(self):
        body = self.trip()
        body.update(harshSteerCount=1, score=98)
        self.assertEqual(201, self.request("POST", "/v1/trips", body, self.first)[0])

    def test_fast_forward_demo_is_allowed_only_in_simulated_league(self):
        body = self.trip(km=20)
        body["startTimeMs"] = self.now - 90000
        self.assertEqual(422, self.request("POST", "/v1/trips", body, self.first)[0])
        body["simulated"] = True
        self.assertEqual(201, self.request("POST", "/v1/trips", body, self.first)[0])

    def test_mode_query_is_required_and_strict(self):
        prefix = f"/v1/league/weekly?weekStartMs={week_start(self.now)}"
        for query in ["", "&simulated=1", "&simulated=false&simulated=true", "&simulated="]:
            self.assertEqual(422, self.request("GET", prefix + query, account=self.first)[0])

    def test_reward_tiers(self):
        self.assertEqual([500, 200, 200, 50], [reward(r, 20) for r in (2, 3, 6, 7)])


if __name__ == "__main__":
    unittest.main()
