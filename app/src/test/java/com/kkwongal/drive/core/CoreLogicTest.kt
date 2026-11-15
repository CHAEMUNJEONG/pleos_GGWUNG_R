package com.kkwongal.drive.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CoreLogicTest {

    /** 0.2초 간격으로 속도 값을 넣는다. speeds 의 각 값이 한 번의 측정값. */
    private fun feed(recorder: TripRecorder, startMs: Long, speeds: List<Double>): Long {
        var t = startMs
        for (s in speeds) {
            recorder.onSpeed(t, s)
            t += 200
        }
        return t
    }

    private fun ramp(from: Double, to: Double, seconds: Double): List<Double> {
        val steps = (seconds * 5).toInt()
        return (0..steps).map { from + (to - from) * it / steps }
    }

    private fun hold(speed: Double, seconds: Double): List<Double> = List((seconds * 5).toInt()) { speed }

    @Test
    fun gentleDriveHasNoEventsAndFullScore() {
        val r = TripRecorder()
        r.start()
        // 20초에 걸쳐 0→60 (초당 3km/h), 10분 정속, 20초에 걸쳐 정지
        feed(r, 0, ramp(0.0, 60.0, 20.0) + hold(60.0, 600.0) + ramp(60.0, 0.0, 20.0))
        val result = r.finish(0, 640_000)
        assertEquals(0, result.harshAccelCount)
        assertEquals(0, result.harshBrakeCount)
        assertEquals(100, result.score)
        // 60km/h 로 10분 = 10km, 앞뒤 가감속 구간 약 0.33km
        assertEquals(10.33, result.distanceKm, 0.05)
        assertEquals(60.0, result.maxSpeedKmh, 0.001)
    }

    @Test
    fun harshAccelAndBrakeAreCountedOnceEach() {
        val r = TripRecorder()
        r.start()
        val speeds = hold(20.0, 5.0) +
            ramp(20.0, 60.0, 3.0) +   // 초당 13km/h: 급가속 1회
            hold(60.0, 30.0) +
            ramp(60.0, 10.0, 4.0) +   // 초당 12.5km/h: 급제동 1회
            hold(10.0, 5.0)
        feed(r, 0, speeds)
        val result = r.finish(0, 47_000)
        assertEquals(1, result.harshAccelCount)
        assertEquals(1, result.harshBrakeCount)
        assertTrue(result.score < 100)
    }

    @Test
    fun twoSeparateBrakesAreCountedTwice() {
        val r = TripRecorder()
        r.start()
        val speeds = hold(80.0, 5.0) + ramp(80.0, 40.0, 3.0) + hold(40.0, 5.0) +
            ramp(40.0, 80.0, 20.0) + ramp(80.0, 30.0, 4.0) + hold(30.0, 3.0)
        feed(r, 0, speeds)
        val result = r.finish(0, 40_000)
        assertEquals(2, result.harshBrakeCount)
        assertEquals(0, result.harshAccelCount)
    }

    @Test
    fun longGapIsNotTreatedAsHarshEvent() {
        val r = TripRecorder()
        r.start()
        r.onSpeed(0, 10.0)
        r.onSpeed(200, 10.0)
        r.onSpeed(60_000, 90.0) // 1분 동안 값이 끊김
        r.onSpeed(60_200, 90.0)
        r.onSpeed(61_200, 90.0)
        val result = r.finish(0, 61_200)
        assertEquals(0, result.harshAccelCount)
        assertTrue(result.distanceKm < 0.1)
    }

    @Test
    fun scoreIsNormalizedByDistance() {
        // 같은 2회 급제동이라도 긴 주행에서는 감점이 작다
        val short = ScoreCalculator.score(distanceKm = 5.0, harshAccel = 0, harshBrake = 2)
        val long = ScoreCalculator.score(distanceKm = 50.0, harshAccel = 0, harshBrake = 2)
        assertEquals(80, short)
        assertEquals(98, long)
        assertEquals(0, ScoreCalculator.score(1.0, 30, 30))
    }

    @Test
    fun stampOnlyForNewPlaces() {
        val book = StampBook()
        val gangneung = GeoPoint(37.7519, 128.8761)
        val nearGangneung = GeoPoint(37.7530, 128.8770) // 약 150m
        val busan = GeoPoint(35.1587, 129.1604)

        val first = book.tryAdd(gangneung, 1L, 92, "강릉 카페")
        assertNotNull(first)
        assertEquals("강릉 카페", first!!.name)
        assertNull(book.tryAdd(nearGangneung, 2L, 80))
        val second = book.tryAdd(busan, 3L, 85)
        assertEquals("새로운 장소 2", second!!.name)
        assertEquals(2, book.stamps.size)

        assertEquals("해운대", book.rename(second.id, " 해운대 ")!!.name)
        assertNull(book.rename(99, "없음"))
    }

    @Test
    fun distanceBetweenSeoulAndBusanIsAbout325Km() {
        val seoul = GeoPoint(37.5665, 126.9780)
        val busan = GeoPoint(35.1796, 129.0756)
        assertEquals(325_000.0, distanceMeters(seoul, busan), 5_000.0)
    }

    @Test
    fun badges() {
        val trip = TripResult(0, 600_000, 10.0, 0, 0, 60.0, 100)
        val rough = trip.copy(harshBrakeCount = 3, score = 85)
        val stamps = (1..5).map { Stamp(it, "s$it", 35.0 + it, 127.0, 0, 90) }

        assertEquals(emptySet<Badge>(), BadgeRules.evaluate(listOf(rough), emptyList()))
        assertEquals(
            setOf(Badge.FIRST_STAMP, Badge.PERFECT),
            BadgeRules.evaluate(listOf(trip), stamps.take(1)),
        )
        assertEquals(
            Badge.entries.toSet(),
            BadgeRules.evaluate(listOf(trip, trip, trip), stamps),
        )
    }

    @Test
    fun ecoScoreFollowsAccelerationEnergy() {
        assertEquals(100, ScoreCalculator.eco(0.05))
        assertEquals(100, ScoreCalculator.eco(0.20))
        assertEquals(50, ScoreCalculator.eco(0.40))
        assertEquals(0, ScoreCalculator.eco(0.60))
        assertEquals(0, ScoreCalculator.eco(1.50))
        assertEquals(80, ScoreCalculator.total(safety = 100, eco = 50))
    }

    @Test
    fun stopAndGoDrivingGetsLowerEcoThanSteadyDriving() {
        val steady = TripRecorder()
        steady.start()
        feed(steady, 0, ramp(0.0, 50.0, 15.0) + hold(50.0, 300.0) + ramp(50.0, 0.0, 15.0))
        val steadyResult = steady.finish(0, 330_000)

        // 급가속 기준(초당 8km/h)에는 걸리지 않지만 가다 서다를 반복하는 주행
        val stopGo = TripRecorder()
        stopGo.start()
        val cycle = ramp(0.0, 50.0, 8.0) + hold(50.0, 6.0) + ramp(50.0, 0.0, 8.0) + hold(0.0, 3.0)
        feed(stopGo, 0, (1..13).flatMap { cycle })
        val stopGoResult = stopGo.finish(0, 325_000)

        assertEquals(0, stopGoResult.harshAccelCount)
        assertEquals(100, steadyResult.ecoScore)
        assertEquals(100, stopGoResult.safetyScore)
        assertTrue(stopGoResult.ecoScore < 40)
        assertTrue(stopGoResult.score < steadyResult.score)
    }

    private val seoul = ZoneId.of("Asia/Seoul")

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        ZonedDateTime.of(year, month, day, hour, 0, 0, 0, seoul).toInstant().toEpochMilli()

    private fun tripAt(endMs: Long, score: Int, km: Double = 10.0) =
        TripResult(endMs - 600_000, endMs, km, 0, 0, 60.0, score)

    @Test
    fun weekStartsOnMondayMidnight() {
        val monday = at(2026, 11, 9, 0)
        assertEquals(monday, League.weekStartMs(at(2026, 11, 14, 21), seoul)) // 토요일
        assertEquals(monday, League.weekStartMs(monday, seoul))
        assertEquals(monday, League.weekStartMs(at(2026, 11, 15, 23), seoul)) // 일요일
        assertEquals(at(2026, 11, 16, 0), League.weekStartMs(at(2026, 11, 16, 0), seoul))
    }

    @Test
    fun leagueRanksByAverageScore() {
        val now = at(2026, 11, 14)

        val empty = League.standing(emptyList(), now, seoul)
        assertNull(empty.myRank)
        assertEquals(19, empty.entries.size)
        assertEquals(0, empty.rewardPoints)

        val best = League.standing(listOf(tripAt(now, 100)), now, seoul)
        assertEquals(1, best.myRank)
        assertEquals(20, best.entries.size)
        assertEquals(500, best.rewardPoints)
        assertTrue(best.entries.first().isMe)

        val worst = League.standing(listOf(tripAt(now, 0)), now, seoul)
        assertEquals(20, worst.myRank)
        assertEquals(50, worst.rewardPoints)

        // 평균으로 순위를 매긴다: 100점과 0점이면 50점
        val mixed = League.standing(listOf(tripAt(now, 100), tripAt(now, 0)), now, seoul)
        assertEquals(50.0, mixed.myAverage!!, 0.001)
        assertEquals(2, mixed.myTripCount)

        // 같은 주에는 가상 운전자가 바뀌지 않는다
        assertEquals(best.entries, League.standing(listOf(tripAt(now, 100)), at(2026, 11, 10), seoul).entries)
    }

    @Test
    fun leagueNeedsMinimumDistanceAndIgnoresOtherWeeks() {
        val now = at(2026, 11, 14)
        val short = League.standing(listOf(tripAt(now, 100, km = 0.5)), now, seoul)
        assertNull(short.myRank)
        assertEquals(0.5, short.myDistanceKm, 0.001)

        val lastWeekOnly = League.standing(listOf(tripAt(at(2026, 11, 5), 100)), now, seoul)
        assertNull(lastWeekOnly.myRank)
        assertEquals(0, lastWeekOnly.myTripCount)
    }

    @Test
    fun rewardTiersAndEarnedPoints() {
        assertEquals(500, League.rewardFor(2, 20))
        assertEquals(200, League.rewardFor(3, 20))
        assertEquals(200, League.rewardFor(6, 20))
        assertEquals(50, League.rewardFor(7, 20))

        val now = at(2026, 11, 14)
        val trips = listOf(
            tripAt(at(2026, 10, 28), 100), // 2주 전: 1위 → 500
            tripAt(at(2026, 11, 5), 0),    // 지난주: 꼴찌 → 50
            tripAt(now, 100),              // 이번 주: 아직 끝나지 않아 제외
        )
        assertEquals(550, League.earnedPoints(trips, now, seoul))
        assertEquals(0, League.earnedPoints(listOf(tripAt(now, 100)), now, seoul))
    }

    @Test
    fun stampMemoAndTags() {
        val book = StampBook()
        val stamp = book.tryAdd(GeoPoint(37.7519, 128.8761), 1L, 92)!!
        assertEquals("", stamp.memo)
        assertEquals(emptyList<String>(), stamp.tags)

        val updated = book.update(
            stamp.id,
            name = " 강릉 카페 ",
            memo = "  바다가 보이는 주차장이 좋았어요 ",
            tags = listOf("다시 올래요", "없는 태그", "주차가 편해요", "다시 올래요"),
        )!!
        assertEquals("강릉 카페", updated.name)
        assertEquals("바다가 보이는 주차장이 좋았어요", updated.memo)
        // 목록에 있는 태그만, 정해진 순서로, 중복 없이
        assertEquals(listOf("주차가 편해요", "다시 올래요"), updated.tags)
        assertEquals(updated, book.stamps.single())

        // 이름을 비우면 기존 이름 유지, 메모는 200자까지
        val again = book.update(stamp.id, name = "  ", memo = "가".repeat(300), tags = emptyList())!!
        assertEquals("강릉 카페", again.name)
        assertEquals(200, again.memo.length)
        assertEquals(emptyList<String>(), again.tags)

        // 이름만 바꿔도 메모와 태그는 남는다
        book.update(stamp.id, "강릉", "좋았어요", listOf("경치가 좋아요"))
        val renamed = book.rename(stamp.id, "강릉 바다")!!
        assertEquals("좋았어요", renamed.memo)
        assertEquals(listOf("경치가 좋아요"), renamed.tags)

        assertNull(book.update(99, "x", "y", emptyList()))
    }

    @Test
    fun harshEventsAreReportedLiveWithPenalty() {
        val r = TripRecorder()
        r.start()
        val events = mutableListOf<HarshEvent>()
        var time = 0L
        fun drive(speeds: List<Double>) {
            for (v in speeds) {
                r.onSpeed(time, v)?.let { events += it }
                time += 200
            }
        }
        drive(hold(30.0, 5.0))
        assertEquals(100, r.currentSafetyScore)
        drive(ramp(30.0, 60.0, 2.0) + hold(60.0, 5.0))   // 초당 15km/h: 급출발
        drive(ramp(60.0, 20.0, 3.0) + hold(20.0, 5.0))   // 초당 13km/h: 급정거

        assertEquals(listOf(HarshType.ACCEL, HarshType.BRAKE), events.map { it.type })
        // 주행 거리가 짧으면 5km 기준으로 환산: 급출발 -8, 급정거 -10
        assertEquals(8, events[0].penalty)
        assertEquals(92, events[0].safetyAfter)
        assertEquals(10, events[1].penalty)
        assertEquals(82, events[1].safetyAfter)
        assertEquals(82, r.currentSafetyScore)
        assertEquals(82, r.finish(0, time).safetyScore)
    }
}
