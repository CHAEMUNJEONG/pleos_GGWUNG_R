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
        assertEquals(90, short)
        assertEquals(98, long)
        // 10km 이하는 모두 10km 로 본다: 급정거 1회 = -5
        assertEquals(95, ScoreCalculator.score(distanceKm = 1.0, harshAccel = 0, harshBrake = 1))
        assertEquals(95, ScoreCalculator.score(distanceKm = 10.0, harshAccel = 0, harshBrake = 1))
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
        // 에코 60% + 안전 40%
        assertEquals(70, ScoreCalculator.total(safety = 100, eco = 50))
        assertEquals(80, ScoreCalculator.total(safety = 50, eco = 100))
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

        // 충분히 달린 만점 기록: 보정이 거의 없어 1위
        val best = League.standing(listOf(tripAt(now, 100, km = 200.0)), now, seoul)
        assertEquals(1, best.myRank)
        assertEquals(20, best.entries.size)
        assertEquals(500, best.rewardPoints)
        assertTrue(best.entries.first().isMe)

        val worst = League.standing(listOf(tripAt(now, 0, km = 200.0)), now, seoul)
        assertEquals(20, worst.myRank)
        assertEquals(50, worst.rewardPoints)

        // 평균은 거리만큼 비중을 둔다: 100점 30km + 0점 10km = 75점
        val mixed = League.standing(listOf(tripAt(now, 100, km = 30.0), tripAt(now, 0, km = 10.0)), now, seoul)
        assertEquals(75.0, mixed.myAverage!!, 0.001)
        assertEquals(2, mixed.myTripCount)
        // 리그 점수 = (100x30 + 0x10 + 80x20) / (40 + 20) = 76.7
        assertEquals(76.7, mixed.myLeagueScore!!, 0.001)

        // 같은 주에는 가상 운전자가 바뀌지 않는다
        assertEquals(best.entries, League.standing(listOf(tripAt(now, 100, km = 200.0)), at(2026, 11, 10), seoul).entries)
    }

    @Test
    fun leagueNeedsMinimumDistanceAndIgnoresOtherWeeks() {
        val now = at(2026, 11, 14)
        // 1km 미만 주행은 리그에 넣지 않는다
        val short = League.standing(listOf(tripAt(now, 100, km = 0.5)), now, seoul)
        assertNull(short.myRank)
        assertEquals(0, short.myTripCount)
        assertEquals(0.0, short.myDistanceKm, 0.001)

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
            tripAt(at(2026, 10, 28), 100, km = 200.0), // 2주 전: 1위 → 500
            tripAt(at(2026, 11, 5), 0, km = 200.0),    // 지난주: 꼴찌 → 50
            tripAt(now, 100, km = 200.0),              // 이번 주: 아직 끝나지 않아 제외
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
        // 주행 거리가 짧으면 10km 로 보고 환산: 급출발 -4, 급정거 -5
        assertEquals(4, events[0].penalty)
        assertEquals(96, events[0].safetyAfter)
        assertEquals(5, events[1].penalty)
        assertEquals(91, events[1].safetyAfter)
        assertEquals(91, r.currentSafetyScore)
        assertEquals(91, r.finish(0, time).safetyScore)
    }

    /** 0.2초마다 속도와 핸들 각도를 함께 넣는다. */
    private fun driveWithSteering(r: TripRecorder, speedKmh: Double, angles: List<Double>, startMs: Long = 0L): List<HarshEvent> {
        val events = mutableListOf<HarshEvent>()
        var time = startMs
        for (a in angles) {
            r.onSpeed(time, speedKmh)?.let { events += it }
            r.onSteering(time, a)?.let { events += it }
            time += 200
        }
        return events
    }

    private fun steady(angle: Double, seconds: Double) = List((seconds * 5).toInt()) { angle }

    @Test
    fun jerkOfSteeringWheelCountsOnceAtSpeed() {
        val r = TripRecorder()
        r.start()
        // 핸들을 0.6초 동안 120도로 확 꺾었다가 되돌림
        val angles = steady(0.0, 5.0) + steady(120.0, 0.6) + steady(0.0, 5.0)
        val events = driveWithSteering(r, 50.0, angles)
        assertEquals(listOf(HarshType.STEER), events.map { it.type })
        assertEquals(4, events.single().penalty) // 10km 이하는 정해진 감점 그대로
        assertEquals(96, r.currentSafetyScore)
        val result = r.finish(0, 10_000)
        assertEquals(1, result.harshSteerCount)
        assertEquals(96, result.safetyScore)
    }

    @Test
    fun gentleCurvesAndParkingManeuversAreNotHarsh() {
        // 고속도로 완만한 커브: 4초에 걸쳐 0 -> 40도 (초당 10도)
        val curve = TripRecorder()
        curve.start()
        val gentle = steady(0.0, 3.0) + (0..20).map { it * 2.0 } + steady(40.0, 3.0)
        assertEquals(emptyList<HarshEvent>(), driveWithSteering(curve, 90.0, gentle))

        // 주차장에서 핸들을 크게 돌림: 시속 10km 라 급조향 아님
        val parking = TripRecorder()
        parking.start()
        val bigTurn = steady(0.0, 3.0) + steady(360.0, 2.0) + steady(0.0, 3.0)
        assertEquals(emptyList<HarshEvent>(), driveWithSteering(parking, 10.0, bigTurn))
        assertEquals(0, parking.currentHarshSteer)
    }

    @Test
    fun twoSeparateSwervesCountTwiceAndPerfectBadgeNeedsNoSteer() {
        val r = TripRecorder()
        r.start()
        val swerve = steady(0.0, 3.0) + steady(-90.0, 0.6) + steady(0.0, 3.0)
        val events = driveWithSteering(r, 60.0, swerve + swerve)
        assertEquals(2, events.count { it.type == HarshType.STEER })

        val clean = TripResult(0, 600_000, 10.0, 0, 0, 60.0, 100)
        assertEquals(setOf(Badge.PERFECT), BadgeRules.evaluate(listOf(clean), emptyList()))
        assertEquals(
            emptySet<Badge>(),
            BadgeRules.evaluate(listOf(clean.copy(harshSteerCount = 1)), emptyList()),
        )
        assertEquals(96, ScoreCalculator.score(10.0, 0, 0, harshSteer = 1))
    }

    @Test
    fun measuresEvAndFuelEfficiency() {
        val ev = EnergyMeter.measure(
            EnergySnapshot(evBatteryWh = 50_000.0, odometerKm = 1000.0),
            EnergySnapshot(evBatteryWh = 48_000.0, odometerKm = 1012.0),
            fallbackDistanceKm = 11.5,
        )!!
        assertEquals(EnergyKind.EV, ev.kind)
        assertEquals(6.0, ev.value, 0.001) // 12km / 2kWh, 누적 주행거리 우선
        assertEquals(12.0, ev.distanceKm, 0.001)

        val fuel = EnergyMeter.measure(
            EnergySnapshot(fuelPercent = 80.0, fuelCapacityL = 50.0),
            EnergySnapshot(fuelPercent = 78.0, fuelCapacityL = 50.0),
            fallbackDistanceKm = 15.0,
        )!!
        assertEquals(EnergyKind.FUEL, fuel.kind)
        assertEquals(15.0, fuel.value, 0.001) // 15km / 1L

        // 연료 1% 단위라 짧은 주행은 재지 않는다, 값이 없으면 null
        assertNull(EnergyMeter.measure(EnergySnapshot(fuelPercent = 80.0, fuelCapacityL = 50.0), EnergySnapshot(fuelPercent = 80.0, fuelCapacityL = 50.0), 3.0))
        assertNull(EnergyMeter.measure(EnergySnapshot(evBatteryWh = 50_000.0), EnergySnapshot(evBatteryWh = 49_990.0), 2.0))
        assertNull(EnergyMeter.measure(null, EnergySnapshot(evBatteryWh = 1.0), 5.0))
        assertNull(EnergyMeter.measure(EnergySnapshot(fuelPercent = 80.0), EnergySnapshot(fuelPercent = 70.0), 50.0)) // 탱크 용량 모름
    }

    @Test
    fun measuredEfficiencyAdjustsEcoAgainstMyOwnBaseline() {
        val base = TripResult(0, 600_000, 10.0, 0, 0, 60.0, score = 90, safetyScore = 100, ecoScore = 75)
        val eff = Efficiency(EnergyKind.EV, 6.6, 10.0, 1.5)

        // 이전 측정이 1번뿐이면 비교하지 않는다: 점수 그대로, 측정값만 저장
        val first = EnergyMeter.apply(base, eff, listOf(base.copy(efficiency = 6.0, efficiencyKind = "EV")))
        assertEquals(75, first.ecoScore)
        assertEquals(6.6, first.efficiency!!, 0.001)
        assertNull(first.efficiencyImprovement)

        // 평소 5.5 -> 이번 6.6 = 20% 개선: 측정 에코 100, 추정 75 와 평균 87.5 -> 88
        val history = listOf(5.0, 6.0).map { base.copy(efficiency = it, efficiencyKind = "EV") } +
            base.copy(efficiency = 15.0, efficiencyKind = "FUEL") // 종류가 다른 기록은 비교에서 뺀다
        val applied = EnergyMeter.apply(base, eff, history)
        assertEquals(20.0, applied.efficiencyImprovement!!, 0.001)
        assertEquals(88, applied.ecoScore)
        assertEquals(ScoreCalculator.total(100, 88), applied.score)

        assertEquals(75, EnergyMeter.ecoFromImprovement(0.0))
        assertEquals(100, EnergyMeter.ecoFromImprovement(30.0))
        assertEquals(25, EnergyMeter.ecoFromImprovement(-40.0))
        assertEquals(base, EnergyMeter.apply(base, null, history))
    }

    @Test
    fun eggLevelsGrowWithEggs() {
        assertEquals("알", EggLevels.current(0).name)
        assertEquals("아기 꿩", EggLevels.next(0)?.name)
        assertEquals(5, EggLevels.remaining(0))
        assertEquals(2, EggLevels.remaining(3))
        assertEquals("아기 꿩", EggLevels.current(5).name)
        assertEquals(0.6, EggLevels.progress(3).toDouble(), 0.001)
        assertEquals("황금 꿩", EggLevels.current(40).name)
        assertNull(EggLevels.next(40))
        assertEquals(1.0, EggLevels.progress(40).toDouble(), 0.001)
    }

    @Test
    fun perfectDriveGivesGoldenEgg() {
        val book = StampBook()
        val gold = book.tryAdd(GeoPoint(37.77, 128.94), 1_000L, 95, "강릉", perfect = true)
        assertNotNull(gold)
        assertTrue(gold!!.perfect)
        val plain = book.tryAdd(GeoPoint(35.15, 129.16), 2_000L, 80, "부산")
        assertTrue(!plain!!.perfect)
    }

    @Test
    fun resumedTripKeepsTotalsAndDoesNotInventEvents() {
        val r = TripRecorder()
        r.start()
        var t = feed(r, 0, hold(30.0, 3.0) + ramp(30.0, 60.0, 2.0) + hold(60.0, 60.0)) // 급출발 1회, 약 1km
        val first = r.snapshot()
        assertEquals(1, first.harshAccel)
        r.finish(0, t)

        // 정차 후 이어 가기: 멈춰 있던 0km/h 에서 바로 60km/h 값이 와도 급출발로 세지 않는다
        val resumed = TripRecorder()
        resumed.resume(first)
        assertTrue(resumed.isActive)
        t += 120_000
        t = feed(resumed, t, hold(60.0, 60.0))
        val result = resumed.finish(0, t)
        assertEquals(1, result.harshAccelCount)
        assertEquals(first.distanceKm + 1.0, result.distanceKm, 0.05)
        assertEquals(96, result.safetyScore)
    }

    @Test
    fun shortStopContinuesOnlyWhenSoonAndNearby() {
        val here = GeoPoint(37.5665, 126.9780)
        val near = GeoPoint(37.5675, 126.9780)   // 약 110m
        val far = GeoPoint(37.5765, 126.9780)    // 약 1.1km
        assertTrue(TripContinuation.canContinue(0, 60_000, here, near))
        assertTrue(TripContinuation.canContinue(0, 60_000, null, null))      // 위치를 모르면 시간만 본다
        assertTrue(!TripContinuation.canContinue(0, 4 * 60_000, here, near)) // 3분이 넘으면 새 주행
        assertTrue(!TripContinuation.canContinue(0, 60_000, here, far))      // 멀리 옮겨졌으면 새 주행
        assertTrue(!TripContinuation.canContinue(60_000, 0, here, here))     // 시계가 거꾸로면 새 주행

        assertTrue(TripContinuation.canRecover(0, 9 * 60_000))
        assertTrue(!TripContinuation.canRecover(0, 11 * 60_000))
    }

    @Test
    fun marksAndStampRemoval() {
        val events = listOf(
            LiveEvent(HarshType.BRAKE, 5, 95, 1_000L + 30_000, 1.0),
            LiveEvent(HarshType.ACCEL, 4, 91, 1_000L + 90_000, 3.0),
        )
        val marks = TripContinuation.marks(events, 1_000L, 1_000L + 120_000)
        assertEquals(listOf(HarshType.BRAKE, HarshType.ACCEL), marks.map { it.first })
        assertEquals(0.25, marks[0].second.toDouble(), 0.001)
        assertEquals(0.75, marks[1].second.toDouble(), 0.001)

        val book = StampBook()
        val a = book.tryAdd(GeoPoint(37.0, 127.0), 1L, 90)!!
        assertTrue(book.remove(a.id))
        assertTrue(!book.remove(a.id))
        assertEquals(0, book.stamps.size)
        // 지운 뒤 같은 자리에 다시 찍을 수 있다
        assertNotNull(book.tryAdd(GeoPoint(37.0, 127.0), 2L, 90))
    }

    @Test
    fun shortPerfectTripCannotWinTheLeague() {
        val now = at(2026, 11, 14)
        val short = League.standing(listOf(tripAt(now, 100, km = 3.0)), now, seoul)
        // (100x3 + 80x20) / 23 = 82.6: 만점이지만 보정되어 1위가 아니다
        assertEquals(82.6, short.myLeagueScore!!, 0.001)
        assertEquals(100.0, short.myAverage!!, 0.001)
        assertTrue(short.myRank!! > 1)
        val long = League.standing(listOf(tripAt(now, 100, km = 3.0), tripAt(now, 95, km = 120.0)), now, seoul)
        assertTrue(long.myRank!! < short.myRank!!)
    }

    @Test
    fun efficiencyIsComparedWithinTheSameRoadType() {
        // 시내 30분 10km (평균 20km/h), 고속도로 1시간 100km
        fun city(eff: Double?) = TripResult(0, 1_800_000, 10.0, 0, 0, 50.0, 90, 100, 75, efficiency = eff, efficiencyKind = eff?.let { "EV" })
        fun highway(eff: Double?) = TripResult(0, 3_600_000, 100.0, 0, 0, 110.0, 90, 100, 75, efficiency = eff, efficiencyKind = eff?.let { "EV" })
        assertEquals(RoadType.CITY, city(null).roadType)
        assertEquals(RoadType.HIGHWAY, highway(null).roadType)
        assertEquals(RoadType.MIXED, RoadType.of(45.0))

        // 고속도로 기록(4.0)은 빼고 시내 기록(6.0, 6.0)과 비교한다: 6.6 은 10% 좋음
        val history = listOf(city(6.0), city(6.0), highway(4.0), highway(4.0))
        val applied = EnergyMeter.apply(city(null), Efficiency(EnergyKind.EV, 6.6, 10.0, 1.5), history)
        assertEquals(10.0, applied.efficiencyImprovement!!, 0.001)

        // 같은 도로 종류 기록이 2번 미만이면 전체 기록과 비교한다 (중앙값)
        val few = listOf(city(6.0), highway(4.0), highway(5.0))
        val fallback = EnergyMeter.apply(city(null), Efficiency(EnergyKind.EV, 5.5, 10.0, 1.5), few)
        assertEquals(10.0, fallback.efficiencyImprovement!!, 0.001) // 중앙값 5.0 대비 10%

        // 평소 값은 중앙값: 한 번 튄 기록에 흔들리지 않는다
        val outlier = listOf(city(6.0), city(6.0), city(30.0))
        assertEquals(10.0, EnergyMeter.apply(city(null), Efficiency(EnergyKind.EV, 6.6, 10.0, 1.5), outlier).efficiencyImprovement!!, 0.001)
    }

    @Test
    fun speedDecidesDrivingWhenGearIsUnknown() {
        val d = SpeedDriveDetector()
        assertEquals(SpeedDriveDetector.DriveMode.UNKNOWN, d.onSpeed(0, 0.0))
        assertEquals(SpeedDriveDetector.DriveMode.DRIVING, d.onSpeed(1_000, 20.0))
        // 신호 대기 1분: 아직 주행 중
        assertEquals(SpeedDriveDetector.DriveMode.DRIVING, d.onSpeed(2_000, 0.0))
        assertEquals(SpeedDriveDetector.DriveMode.DRIVING, d.onTick(62_000))
        assertEquals(SpeedDriveDetector.DriveMode.DRIVING, d.onSpeed(63_000, 30.0))
        // 2분 넘게 멈춤: 주차
        d.onSpeed(70_000, 0.0)
        assertEquals(SpeedDriveDetector.DriveMode.DRIVING, d.onTick(150_000))
        assertEquals(SpeedDriveDetector.DriveMode.PARKED, d.onTick(190_000))
        // 다시 출발
        assertEquals(SpeedDriveDetector.DriveMode.DRIVING, d.onSpeed(200_000, 8.0))
    }

    @Test
    fun diagnosticsBoardThrottlesSameStatusButNotChanges() {
        var now = 0L
        var changes = 0
        val board = DiagnosticsBoard({ now }) { changes++ }
        assertEquals(ChannelStatus.WAITING, board.current.getValue(Channel.SPEED).status)
        board.ok(Channel.SPEED, "10 km/h")
        assertEquals(1, changes)
        now = 300
        board.ok(Channel.SPEED, "12 km/h")     // 1초 안의 같은 상태: 건너뜀
        assertEquals(1, changes)
        assertEquals("10 km/h", board.current.getValue(Channel.SPEED).detail)
        now = 1_200
        board.ok(Channel.SPEED, "15 km/h")
        assertEquals(2, changes)
        now = 1_300
        board.unavailable(Channel.SPEED, "끊김") // 상태가 바뀌면 바로
        assertEquals(3, changes)
        assertEquals(ChannelStatus.UNAVAILABLE, board.current.getValue(Channel.SPEED).status)
        assertEquals(1_300L, board.current.getValue(Channel.SPEED).updatedAtMs)
    }

    @Test
    fun brakePedalSignalSeparatesRegenFromHarshBraking() {
        // 페달 신호가 있고 밟지 않은 채 초당 12km/h 감속(회생제동): 급정거 아님
        val regen = TripRecorder()
        regen.start()
        regen.onBrakePedal(0, false)
        feed(regen, 0, hold(60.0, 3.0) + ramp(60.0, 36.0, 2.0) + hold(36.0, 3.0))
        assertEquals(0, regen.currentHarshBrake)

        // 같은 감속을 페달을 밟으면서: 급정거
        val pedal = TripRecorder()
        pedal.start()
        pedal.onBrakePedal(0, false)
        pedal.onBrakePedal(3_000, true)
        feed(pedal, 0, hold(60.0, 3.0) + ramp(60.0, 36.0, 2.0) + hold(36.0, 3.0))
        assertEquals(1, pedal.currentHarshBrake)

        // 페달을 안 밟아도 초당 15km/h 이상이면: 급정거
        val hard = TripRecorder()
        hard.start()
        hard.onBrakePedal(0, false)
        feed(hard, 0, hold(60.0, 3.0) + ramp(60.0, 30.0, 2.0) + hold(30.0, 3.0))
        assertEquals(1, hard.currentHarshBrake)

        // 페달 신호를 못 받으면 예전처럼 감속만 본다
        val unknown = TripRecorder()
        unknown.start()
        feed(unknown, 0, hold(60.0, 3.0) + ramp(60.0, 36.0, 2.0) + hold(36.0, 3.0))
        assertEquals(1, unknown.currentHarshBrake)
    }

    @Test
    fun vehicleEfficiencyIsUsedWhenTankLevelsCannotMeasure() {
        // 연료 잔량이 그대로(1% 단위)라 잴 수 없을 때: 차량이 계산한 연비를 쓴다
        val start = EnergySnapshot(fuelPercent = 80.0, fuelCapacityL = 50.0)
        val end = EnergySnapshot(fuelPercent = 80.0, fuelCapacityL = 50.0, vehicleFuelKmPerL = 14.5)
        val eff = EnergyMeter.measure(start, end, 3.0)!!
        assertEquals(EnergyKind.FUEL, eff.kind)
        assertEquals(14.5, eff.value, 0.001)
        // 출발 때 값을 못 읽었어도 차량 값이 있으면 쓴다
        assertEquals(5.8, EnergyMeter.measure(null, EnergySnapshot(vehicleEvKmPerKwh = 5.8), 4.0)!!.value, 0.001)
        // 잔량으로 잴 수 있으면 그 값이 먼저다
        val ev = EnergyMeter.measure(
            EnergySnapshot(evBatteryWh = 50_000.0),
            EnergySnapshot(evBatteryWh = 48_000.0, vehicleEvKmPerKwh = 9.9),
            10.0,
        )!!
        assertEquals(5.0, ev.value, 0.001)
        // 말이 안 되는 차량 값은 버린다
        assertNull(EnergyMeter.measure(null, EnergySnapshot(vehicleEvKmPerKwh = 0.0), 4.0))
    }

    @Test
    fun liveTotalMatchesFinalTotal() {
        val r = TripRecorder()
        r.start()
        assertEquals(100, r.currentTotalScore) // 출발 직후
        val t = feed(r, 0, ramp(0.0, 60.0, 20.0) + hold(60.0, 120.0) + ramp(60.0, 0.0, 20.0))
        val liveTotal = r.currentTotalScore
        val liveEco = r.currentEcoScore
        val result = r.finish(0, t)
        assertEquals(result.score, liveTotal)
        assertEquals(result.ecoScore, liveEco)
        assertEquals(ScoreCalculator.total(result.safetyScore, result.ecoScore), result.score)
    }

    // ---- 에코 습관 항목 ----

    /** 속도와 함께 1초마다 제한속도도 넣는다. */
    private fun feedWithLimit(recorder: TripRecorder, startMs: Long, speeds: List<Double>, limit: Int): Long {
        var t = startMs
        for ((i, s) in speeds.withIndex()) {
            if (i % 5 == 0) recorder.onSpeedLimit(t, limit)
            recorder.onSpeed(t, s)
            t += 200
        }
        return t
    }

    @Test
    fun longStopCountsIdleBeyondGrace() {
        val r = TripRecorder()
        r.start()
        var t = feed(r, 0, ramp(0.0, 40.0, 10.0) + hold(40.0, 120.0))
        t = feed(r, t, ramp(40.0, 0.0, 10.0) + hold(0.0, 300.0)) // 5분 정차 → 2분은 신호 대기, 3분이 공회전
        val d = r.currentEcoBreakdown
        assertEquals(3.0, d.idleMin, 0.1)
        assertEquals(70, d.idle)
    }

    @Test
    fun trafficLightStopIsNotIdle() {
        val r = TripRecorder()
        r.start()
        var t = feed(r, 0, ramp(0.0, 40.0, 10.0) + hold(40.0, 120.0))
        t = feed(r, t, ramp(40.0, 0.0, 10.0) + hold(0.0, 90.0) + ramp(0.0, 40.0, 10.0))
        assertEquals(100, r.currentEcoBreakdown.idle)
    }

    @Test
    fun steadyCruiseBeatsWavyCruise() {
        val steady = TripRecorder().apply { start() }
        feed(steady, 0, ramp(0.0, 90.0, 30.0) + hold(90.0, 300.0))
        val wavy = TripRecorder().apply { start() }
        // 90 ↔ 100 km/h 를 5초마다 오르내린다 (1초에 2km/h 씩 변함)
        val wave = (0 until 30).flatMap { ramp(90.0, 100.0, 5.0) + ramp(100.0, 90.0, 5.0) }
        feed(wavy, 0, ramp(0.0, 90.0, 30.0) + wave)
        val s = steady.currentEcoBreakdown.steady
        val w = wavy.currentEcoBreakdown.steady
        assertNotNull(s)
        assertNotNull(w)
        assertEquals(100, s)
        // 계속 오르내리면 정속 점수가 크게 낮다
        assertTrue(w!! <= 40)
    }

    @Test
    fun shortCruiseHasNoSteadyItem() {
        val r = TripRecorder().apply { start() }
        feed(r, 0, ramp(0.0, 70.0, 20.0) + hold(70.0, 30.0) + ramp(70.0, 0.0, 20.0))
        assertNull(r.currentEcoBreakdown.steady)
    }

    @Test
    fun overSpeedUsesRoadSpeedLimit() {
        // 시내(제한 50)에서 70km/h → 고속 주행
        val city = TripRecorder().apply { start() }
        feedWithLimit(city, 0, ramp(0.0, 70.0, 20.0) + hold(70.0, 120.0), 50)
        assertEquals(0, city.currentEcoBreakdown.speed)
        // 고속도로(제한 100)에서 105km/h → 오차 범위 안
        val highway = TripRecorder().apply { start() }
        feedWithLimit(highway, 0, ramp(0.0, 105.0, 30.0) + hold(105.0, 120.0), 100)
        assertEquals(100, highway.currentEcoBreakdown.speed)
    }

    @Test
    fun overSpeedFallsBackTo110WithoutLimit() {
        val r = TripRecorder().apply { start() }
        feed(r, 0, ramp(0.0, 130.0, 30.0) + hold(130.0, 120.0))
        assertTrue(r.currentEcoBreakdown.overSpeedPercent!! >= 80)
        assertEquals(0, r.currentEcoBreakdown.speed)
    }

    @Test
    fun staleSpeedLimitIsIgnored() {
        val r = TripRecorder().apply { start() }
        r.onSpeedLimit(0, 50)
        // 제한속도가 그 뒤로 오지 않으면 (터널, 내비 꺼짐) 모르는 것으로 보고 시속 110 기준을 쓴다
        feed(r, 0, ramp(0.0, 70.0, 20.0) + hold(70.0, 120.0))
        assertEquals(100, r.currentEcoBreakdown.speed)
        // 마지막 제한속도 뒤 5초까지만 그 도로로 본다
        assertTrue(r.currentRoadKm.total < 0.05)
    }

    @Test
    fun coastingWithoutPedalScoresHigh() {
        val coast = TripRecorder().apply { start() }
        coast.onBrakePedal(0, false)
        var t = 0L
        repeat(4) { t = feed(coast, t, ramp(0.0, 50.0, 10.0) + hold(50.0, 20.0) + ramp(50.0, 0.0, 15.0)) }
        assertEquals(100, coast.currentEcoBreakdown.coast)

        val brake = TripRecorder().apply { start() }
        t = 0L
        repeat(4) {
            t = feed(brake, t, ramp(0.0, 50.0, 10.0) + hold(50.0, 20.0))
            brake.onBrakePedal(t, true)
            t = feed(brake, t, ramp(50.0, 0.0, 15.0))
            brake.onBrakePedal(t, false)
        }
        assertEquals(0, brake.currentEcoBreakdown.coast)
    }

    @Test
    fun coastItemNeedsPedalSignal() {
        val r = TripRecorder().apply { start() }
        var t = 0L
        repeat(4) { t = feed(r, t, ramp(0.0, 50.0, 10.0) + hold(50.0, 20.0) + ramp(50.0, 0.0, 15.0)) }
        assertNull(r.currentEcoBreakdown.coast)
    }

    @Test
    fun roadTypeComesFromSpeedLimit() {
        // 막히는 고속도로: 평균 속도로는 시내지만 제한속도로는 고속도로
        val r = TripRecorder().apply { start() }
        val t = feedWithLimit(r, 0, ramp(0.0, 25.0, 10.0) + hold(25.0, 600.0), 100)
        val result = r.finish(0, t)
        assertEquals(RoadType.CITY, RoadType.of(result.averageSpeedKmh))
        assertEquals(RoadType.HIGHWAY, result.roadType)
        assertTrue(result.roadTypeFromLimit)
        assertEquals(RoadType.MIXED, RoadType.ofLimit(80))
        assertEquals(RoadType.CITY, RoadType.ofLimit(50))
    }

    @Test
    fun ecoBreakdownSkipsMissingItems() {
        assertEquals(80, EcoBreakdown(accel = 80).score())
        // 가속 80 (비중 0.4) + 공회전 100 (비중 0.1) → 84
        assertEquals(84, EcoBreakdown(accel = 80, idle = 100).score())
        assertEquals(EcoItem.ACCEL, EcoBreakdown(accel = 70, idle = 100, steady = 95).weakest())
        assertNull(EcoBreakdown(accel = 95, idle = 100).weakest())
    }

    @Test
    fun resumeKeepsEcoHabits() {
        val r = TripRecorder().apply { start() }
        feed(r, 0, ramp(0.0, 40.0, 10.0) + hold(40.0, 60.0) + ramp(40.0, 0.0, 10.0) + hold(0.0, 300.0))
        val before = r.currentEcoBreakdown
        val again = TripRecorder()
        again.resume(r.snapshot())
        assertEquals(before.idleMin, again.currentEcoBreakdown.idleMin, 0.001)
        assertEquals(before.idle, again.currentEcoBreakdown.idle)
    }

    @Test
    fun finishStoresEcoDetail() {
        val r = TripRecorder().apply { start() }
        val t = feed(r, 0, ramp(0.0, 60.0, 20.0) + hold(60.0, 120.0) + ramp(60.0, 0.0, 20.0))
        val live = r.currentEcoBreakdown
        val result = r.finish(0, t)
        assertEquals(live, result.ecoDetail)
        assertEquals(live.score(), result.ecoScore)
    }

    // ---- 리그 공정성 ----

    @Test
    fun leagueIgnoresTinyTripsUsedForGaming() {
        val now = at(2026, 11, 14)
        // 주차장에서 0.5km 짜리 만점 주행을 10번 해도 순위에 오르지 않는다
        val gaming = List(10) { tripAt(now - it * 60_000L, 100, km = 0.5) }
        assertNull(League.standing(gaming, now, seoul).myRank)
        // 평범한 10km 주행 하나에 섞어도 그 주행 점수만 본다
        val mixed = League.standing(gaming + tripAt(now, 70, km = 10.0), now, seoul)
        assertEquals(1, mixed.myTripCount)
        assertEquals(70.0, mixed.myAverage!!, 0.001)
    }

    @Test
    fun simulatedTripsHaveTheirOwnLeague() {
        val now = at(2026, 11, 14)
        val real = tripAt(now, 70, km = 30.0)
        val sim = tripAt(now, 100, km = 30.0).copy(simulated = true)
        val realLeague = League.standing(listOf(real, sim), now, seoul)
        val simLeague = League.standing(listOf(real, sim), now, seoul, simulated = true)
        assertEquals(70.0, realLeague.myAverage!!, 0.001)
        assertEquals(100.0, simLeague.myAverage!!, 0.001)
        assertEquals(1, realLeague.myTripCount)
        assertEquals(1, simLeague.myTripCount)
    }

    // ---- 코칭 ----

    @Test
    fun ecoDeltaComparesWithRecentTrips() {
        val history = listOf(70, 80, 90, 60, 70, 80).map { TripResult(0, 1, 10.0, 0, 0, 60.0, it, ecoScore = it) }
        val now = TripResult(0, 1, 10.0, 0, 0, 60.0, 82, ecoScore = 82)
        // 최근 5번 (80, 90, 60, 70, 80) 평균 76 → +6
        assertEquals(6, Coaching.ecoDelta(now, history))
        assertNull(Coaching.ecoDelta(now, history.take(1)))
        assertEquals("평소보다 에코 +6점", Coaching.deltaText(6))
        assertEquals("평소보다 에코 -3점", Coaching.deltaText(-3))
    }

    @Test
    fun parkingSummaryReadsNaturally() {
        val detail = EcoBreakdown(accel = 95, idle = 60, coast = 90)
        val result = TripResult(0, 1, 10.0, 0, 0, 60.0, 82, ecoScore = 85, ecoDetail = detail)
        assertEquals(
            "주행 끝. 종합 82점이에요. 에코는 평소보다 6점 높아요. 공회전이 아쉬웠어요.",
            Coaching.parkingSummary(result, 6),
        )
        val noWeak = result.copy(ecoDetail = EcoBreakdown(accel = 95))
        assertEquals("주행 끝. 종합 82점이에요. 운전 습관이 모두 좋았어요.", Coaching.parkingSummary(noWeak, null))
        assertEquals("적정 속도가", Coaching.withSubject("적정 속도"))
        assertEquals("미리 감속이", Coaching.withSubject("미리 감속"))
    }

    @Test
    fun hiddenTripsStillCountForLeague() {
        val now = at(2026, 11, 14)
        val trips = listOf(tripAt(now, 90, km = 30.0), tripAt(now - 60_000L, 70, km = 30.0))
        val hidden = trips.mapIndexed { i, t -> if (i == 0) t.copy(hidden = true) else t }
        // 목록에서 지워도 리그 점수는 그대로
        assertEquals(League.standing(trips, now, seoul).myLeagueScore, League.standing(hidden, now, seoul).myLeagueScore)
        assertEquals(2, League.standing(hidden, now, seoul).myTripCount)
    }
}
