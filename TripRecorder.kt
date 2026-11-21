package com.kkwongal.drive.core

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 점수 계산에 쓰는 기준값. 가속도 단위는 "1초에 몇 km/h 변했는가" (km/h/s).
 *
 * 급가속·급제동 기준은 한국교통안전공단 운행기록분석시스템(eTAS)의 위험운전행동 기준
 * (급가속: 초당 11km/h 이상 가속, 급제동: 초당 11km/h 이상 감속)을 따른다.
 * 초당 11km/h 는 약 0.31g 로, 해외 운전습관 연계 보험이 쓰는 급제동 기준(0.3g)과도 같다.
 * 급조향 기준은 공인 기준이 없어 시험값이며, 실제 차량 주행으로 조정한다.
 */
data class ScoreConfig(
    /** 이 값 이상으로 속도가 빨라지면 급가속 1회. */
    val harshAccelKmhPerSec: Double = 11.0,
    /** 이 값 이상으로 속도가 줄면 급제동 1회. */
    val harshBrakeKmhPerSec: Double = 11.0,
    /**
     * 브레이크 페달 신호를 받을 수 있을 때, 페달을 밟지 않은 감속(회생제동·엔진 브레이크)은
     * 이 값 이상일 때만 급정거로 센다. 공단 기준의 급제동은 "브레이크를 밟으며" 감속한 경우다.
     */
    val harshBrakeNoPedalKmhPerSec: Double = 14.0,
    /** 페달을 뗀 뒤 이 시간 안의 감속까지는 페달을 밟은 감속으로 본다. */
    val brakePedalGraceMs: Long = 1_500L,
    /** 급가속 1회당 감점 (10km 기준). */
    val accelPenalty: Double = 4.0,
    /** 급제동 1회당 감점 (10km 기준). */
    val brakePenalty: Double = 5.0,
    /** 핸들이 1초에 이 각도 이상 돌아가면 급조향 1회. 단위: 핸들 각도(도). */
    val harshSteerDegPerSec: Double = 60.0,
    /** 이보다 느릴 때의 핸들 조작(주차, 골목 회전)은 급조향으로 보지 않는다. */
    val steerMinSpeedKmh: Double = 30.0,
    /** 급조향 1회당 감점 (10km 기준). */
    val steerPenalty: Double = 4.0,
    /** 가속도를 구할 때 비교하는 시간 간격. */
    val windowMs: Long = 1_000L,
    /** 이보다 짧은 간격의 변화는 잡음으로 보고 무시한다. */
    val minSpanMs: Long = 800L,
    /** 속도 값이 이보다 오래 끊기면 그 구간은 거리·가속도 계산에서 뺀다. */
    val maxGapMs: Long = 5_000L,
    /**
     * 감점을 10km당으로 환산할 때 최소 이 거리로 본다.
     * 10km 이하 주행에서는 급조작 1회가 정해진 감점(4·5·4점) 그대로 깎이고,
     * 그보다 긴 주행에서는 달린 거리만큼 감점이 나뉘어 점수가 조금씩 회복된다.
     */
    val minDistanceForRateKm: Double = 10.0,
    /** 가속 에너지 지표가 이 값 이하면 에코 100점. 단위 m/s². */
    val ecoBestPke: Double = 0.20,
    /** 가속 에너지 지표가 이 값 이상이면 에코 0점. 단위 m/s². */
    val ecoWorstPke: Double = 0.60,
    /**
     * 종합 점수에서 안전 점수가 차지하는 비율. 나머지는 에코 점수.
     * 꿩알의 주제는 에코 드라이빙이라 에코 60%, 안전 40%. 부드럽게 운전하면 안전은 자연히 따라온다.
     */
    val safetyWeight: Double = 0.4,
    /** 정속·적정 속도·미리 감속·공회전 기준과 항목별 비중. */
    val eco: EcoConfig = EcoConfig(),
    /** 내비 제한속도가 이 시간 넘게 오지 않으면 모르는 것으로 본다. */
    val speedLimitStaleMs: Long = 5_000L,
)

object ScoreCalculator {
    /** 안전 점수: 급가속·급제동·급조향 횟수를 10km당 감점으로 환산해 100점에서 뺀다. 0~100점. */
    fun score(
        distanceKm: Double,
        harshAccel: Int,
        harshBrake: Int,
        config: ScoreConfig = ScoreConfig(),
        harshSteer: Int = 0,
    ): Int {
        val per10Km = 10.0 / max(distanceKm, config.minDistanceForRateKm)
        val penalty = (
            harshAccel * config.accelPenalty +
                harshBrake * config.brakePenalty +
                harshSteer * config.steerPenalty
            ) * per10Km
        return (100.0 - penalty).roundToInt().coerceIn(0, 100)
    }

    /**
     * 가속 점수: 1m 를 가는 동안 속도를 올리는 데 쓴 에너지가 적을수록 높다.
     * 연료 소비를 직접 재는 것이 아니라 속도 변화 패턴으로 추정한 값이다.
     */
    fun eco(pke: Double, config: ScoreConfig = ScoreConfig()): Int {
        val ratio = ((pke - config.ecoBestPke) / (config.ecoWorstPke - config.ecoBestPke)).coerceIn(0.0, 1.0)
        return (100.0 * (1.0 - ratio)).roundToInt()
    }

    /** 종합 점수: 안전과 에코를 정해진 비율로 합친다. */
    fun total(safety: Int, eco: Int, config: ScoreConfig = ScoreConfig()): Int =
        (safety * config.safetyWeight + eco * (1.0 - config.safetyWeight)).roundToInt().coerceIn(0, 100)
}

/** 주행 중 감지된 급조작. */
enum class HarshType(val label: String) { ACCEL("급출발"), BRAKE("급정거"), STEER("급조향") }

/** 급조작 한 번과 그로 인해 깎인 안전 점수. */
data class HarshEvent(val type: HarshType, val penalty: Int, val safetyAfter: Int)

/** 감점 기록 한 줄. 주행 화면의 감점 기록과 리포트의 시간 막대에 쓴다. */
data class LiveEvent(
    val type: HarshType,
    val penalty: Int,
    val scoreAfter: Int,
    val wallTimeMs: Long,
    val km: Double,
)

/** 진행 중인 주행의 누적 값. 앱이 다시 켜지거나 잠깐 정차 후 다시 출발할 때 이어 가는 데 쓴다. */
data class TripSnapshot(
    val distanceKm: Double,
    val maxSpeedKmh: Double,
    val harshAccel: Int,
    val harshBrake: Int,
    val harshSteer: Int,
    val positiveKineticSum: Double,
    /** 에코 습관 누적 값. 이전 버전 저장 파일에는 없어서 기본값. */
    val eco: EcoState = EcoState(),
)

/**
 * 주행 한 번을 기록한다. 안드로이드에 의존하지 않는 순수 계산 코드.
 *
 * 사용 순서: start() → onSpeed() 를 속도 값이 올 때마다 호출 → finish().
 * 시간은 계속 증가하는 시계(SystemClock.elapsedRealtime 등)의 밀리초를 넣는다.
 */
class TripRecorder(private val config: ScoreConfig = ScoreConfig()) {

    private data class Sample(val timeMs: Long, val speedKmh: Double)
    private data class SteerSample(val timeMs: Long, val angleDeg: Double)

    private val window = ArrayDeque<Sample>()
    private var active = false
    private var distanceKm = 0.0
    private var maxSpeedKmh = 0.0
    private var harshAccel = 0
    private var harshBrake = 0
    private var inAccelEvent = false
    private var inBrakeEvent = false
    private val steerWindow = ArrayDeque<SteerSample>()

    /** 이번 주행에서 브레이크 페달 신호를 한 번이라도 받았는지. 못 받으면 감속만으로 판단한다. */
    private var brakeSignalKnown = false
    private var brakePressed = false
    private var brakeReleasedAtMs: Long? = null
    private var harshSteer = 0
    private var inSteerEvent = false
    private var lastSpeedKmh = 0.0

    /** 속도를 올릴 때마다 늘어난 (속도 제곱)의 합. 단위 m²/s². */
    private var positiveKineticSum = 0.0

    private val ecoTracker = EcoTracker(config.eco)
    private var speedLimitKmh: Int? = null
    private var speedLimitAtMs = 0L

    val isActive: Boolean get() = active
    val currentDistanceKm: Double get() = distanceKm
    val currentHarshAccel: Int get() = harshAccel
    val currentHarshBrake: Int get() = harshBrake
    val currentHarshSteer: Int get() = harshSteer

    /** 지금까지의 안전 점수. 주행 중 화면에 실시간으로 보여 준다. */
    val currentSafetyScore: Int get() = ScoreCalculator.score(distanceKm, harshAccel, harshBrake, config, harshSteer)

    /** 지금까지의 에코 항목별 점수. */
    val currentEcoBreakdown: EcoBreakdown
        get() = ecoTracker.breakdown(ScoreCalculator.eco(currentPke(), config), distanceKm)

    /** 지금까지의 에코 점수 (운전 습관 항목의 가중 평균). 주행이 끝나면 실제 연비가 반영될 수 있다. */
    val currentEcoScore: Int get() = currentEcoBreakdown.score(config.eco)

    /** 내비 제한속도로 나눈 도로 종류별 거리. */
    val currentRoadKm: RoadKm get() = ecoTracker.state.roadKm

    /** 지금까지의 종합 점수 (에코 60% + 안전 40%). 주행 중 화면의 게이지에 쓴다. */
    val currentTotalScore: Int get() = ScoreCalculator.total(currentSafetyScore, currentEcoScore, config)

    /** 가속 에너지 지표: 1m 당 속도를 올리는 데 쓴 에너지. 아주 짧은 주행(50m 미만)은 0 으로 본다. */
    private fun currentPke(): Double {
        val distanceM = distanceKm * 1000.0
        return if (distanceM >= 50.0) positiveKineticSum / distanceM else 0.0
    }

    fun start() {
        window.clear()
        active = true
        distanceKm = 0.0
        maxSpeedKmh = 0.0
        harshAccel = 0
        harshBrake = 0
        inAccelEvent = false
        inBrakeEvent = false
        positiveKineticSum = 0.0
        steerWindow.clear()
        harshSteer = 0
        brakeSignalKnown = false
        brakePressed = false
        brakeReleasedAtMs = null
        inSteerEvent = false
        lastSpeedKmh = 0.0
        ecoTracker.reset()
        speedLimitKmh = null
        speedLimitAtMs = 0L
    }

    /** 지금까지의 누적 값. */
    fun snapshot(): TripSnapshot = TripSnapshot(
        distanceKm = distanceKm,
        maxSpeedKmh = maxSpeedKmh,
        harshAccel = harshAccel,
        harshBrake = harshBrake,
        harshSteer = harshSteer,
        positiveKineticSum = positiveKineticSum,
        eco = ecoTracker.state,
    )

    /**
     * 저장해 둔 누적 값에서 주행을 이어 간다. 직전 속도·핸들 값은 비교하지 않으므로
     * 이어 붙인 순간이 급조작으로 잡히지는 않는다.
     */
    fun resume(snapshot: TripSnapshot) {
        start()
        distanceKm = snapshot.distanceKm
        maxSpeedKmh = snapshot.maxSpeedKmh
        harshAccel = snapshot.harshAccel
        harshBrake = snapshot.harshBrake
        harshSteer = snapshot.harshSteer
        positiveKineticSum = snapshot.positiveKineticSum
        ecoTracker.reset(snapshot.eco)
    }

    /**
     * 내비가 알려 준 지금 도로의 제한속도 (Pleos NaviHelper onCurrentRoadSpeedLimit, 1초마다).
     * 0 이하는 모르는 것으로 본다. [timeMs] 는 속도 값과 같은 시계.
     */
    fun onSpeedLimit(timeMs: Long, limitKmh: Int) {
        speedLimitKmh = limitKmh.takeIf { it > 0 }
        speedLimitAtMs = timeMs
    }

    private fun limitAt(timeMs: Long): Int? =
        speedLimitKmh?.takeIf { timeMs - speedLimitAtMs <= config.speedLimitStaleMs }

    /** 브레이크를 밟고 있는지 (뗀 직후 포함). 페달 신호를 받지 못했으면 null. */
    private fun pedalAt(timeMs: Long): Boolean? {
        if (!brakeSignalKnown) return null
        if (brakePressed) return true
        val released = brakeReleasedAtMs
        return released != null && timeMs - released <= config.brakePedalGraceMs
    }

    /** 브레이크 페달 상태가 바뀔 때. [timeMs] 는 속도 값과 같은 시계. */
    fun onBrakePedal(timeMs: Long, pressed: Boolean) {
        brakeSignalKnown = true
        if (brakePressed && !pressed) brakeReleasedAtMs = timeMs
        brakePressed = pressed
    }

    /** 급제동 기준을 넘는 감속을 급정거로 셀지. 페달 신호가 없으면 감속만 본다. */
    private fun brakeCounts(timeMs: Long, decelKmhPerSec: Double): Boolean {
        val pedal = pedalAt(timeMs) ?: return true
        return pedal || decelKmhPerSec >= config.harshBrakeNoPedalKmhPerSec
    }

    /** 속도 값을 하나 넣는다. 이 값으로 급출발·급정거가 새로 감지되면 그 내용을 돌려준다. */
    fun onSpeed(timeMs: Long, speedKmh: Double): HarshEvent? {
        if (!active) return null
        val speed = speedKmh.coerceAtLeast(0.0)
        maxSpeedKmh = max(maxSpeedKmh, speed)
        lastSpeedKmh = speed

        val last = window.lastOrNull()
        if (last != null) {
            val dtMs = timeMs - last.timeMs
            if (dtMs <= 0) return null
            if (dtMs <= config.maxGapMs) {
                // 사다리꼴 적분: 평균 속도 x 시간
                val km = (last.speedKmh + speed) / 2.0 * dtMs / 3_600_000.0
                distanceKm += km
                ecoTracker.onSegment(timeMs, dtMs, last.speedKmh, speed, km, limitAt(timeMs), pedalAt(timeMs))
                if (speed > last.speedKmh) {
                    val before = last.speedKmh / 3.6
                    val after = speed / 3.6
                    positiveKineticSum += after * after - before * before
                }
            } else {
                // 값이 오래 끊겼으면 이전 기록과 비교하지 않는다
                window.clear()
                inAccelEvent = false
                inBrakeEvent = false
                ecoTracker.gap()
            }
        }
        window.addLast(Sample(timeMs, speed))

        // 맨 앞에는 "약 1초 전" 값 하나만 남긴다
        while (window.size > 2 && timeMs - window[1].timeMs >= config.windowMs) {
            window.removeFirst()
        }
        val reference = window.first()
        val spanMs = timeMs - reference.timeMs
        if (spanMs < config.minSpanMs) return null

        val ratePerSec = (speed - reference.speedKmh) / (spanMs / 1000.0)
        var event: HarshEvent? = null

        // 기준을 넘는 순간 1회로 세고, 기준의 절반 아래로 내려가야 다음 번을 센다
        if (ratePerSec >= config.harshAccelKmhPerSec) {
            if (!inAccelEvent) {
                val before = currentSafetyScore
                harshAccel++
                event = HarshEvent(HarshType.ACCEL, before - currentSafetyScore, currentSafetyScore)
                inAccelEvent = true
            }
        } else if (ratePerSec < config.harshAccelKmhPerSec / 2) {
            inAccelEvent = false
        }

        if (-ratePerSec >= config.harshBrakeKmhPerSec && brakeCounts(timeMs, -ratePerSec)) {
            if (!inBrakeEvent) {
                val before = currentSafetyScore
                harshBrake++
                event = HarshEvent(HarshType.BRAKE, before - currentSafetyScore, currentSafetyScore)
                inBrakeEvent = true
            }
        } else if (-ratePerSec < config.harshBrakeKmhPerSec / 2) {
            inBrakeEvent = false
        }
        return event
    }

    /**
     * 핸들 각도 값을 하나 넣는다. 시속 [ScoreConfig.steerMinSpeedKmh] 이상에서 핸들을 1초에
     * [ScoreConfig.harshSteerDegPerSec] 이상 돌리면 급조향으로 보고, 그 내용을 돌려준다.
     */
    fun onSteering(timeMs: Long, angleDeg: Double): HarshEvent? {
        if (!active) return null
        val last = steerWindow.lastOrNull()
        if (last != null) {
            val dtMs = timeMs - last.timeMs
            if (dtMs <= 0) return null
            if (dtMs > config.maxGapMs) {
                steerWindow.clear()
                inSteerEvent = false
            }
        }
        steerWindow.addLast(SteerSample(timeMs, angleDeg))
        while (steerWindow.size > 2 && timeMs - steerWindow[1].timeMs >= config.windowMs) {
            steerWindow.removeFirst()
        }
        val reference = steerWindow.first()
        val spanMs = timeMs - reference.timeMs
        if (spanMs < config.minSpanMs) return null

        // 1초 사이 가장 크게 벌어진 각도로 본다 (한쪽으로 확 꺾었다가 돌아온 경우도 잡는다)
        val swing = steerWindow.maxOf { kotlin.math.abs(it.angleDeg - reference.angleDeg) }
        val ratePerSec = swing / (spanMs / 1000.0)
        var event: HarshEvent? = null
        if (ratePerSec >= config.harshSteerDegPerSec && lastSpeedKmh >= config.steerMinSpeedKmh) {
            if (!inSteerEvent) {
                val before = currentSafetyScore
                harshSteer++
                event = HarshEvent(HarshType.STEER, before - currentSafetyScore, currentSafetyScore)
                inSteerEvent = true
            }
        } else if (ratePerSec < config.harshSteerDegPerSec / 2) {
            inSteerEvent = false
        }
        return event
    }

    /** 주행을 끝내고 결과를 돌려준다. 시작·종료 시각은 기록용 실제 시각(밀리초). */
    fun finish(startWallTimeMs: Long, endWallTimeMs: Long): TripResult {
        active = false
        val safety = currentSafetyScore
        val detail = currentEcoBreakdown
        val eco = detail.score(config.eco)
        return TripResult(
            startTimeMs = startWallTimeMs,
            endTimeMs = endWallTimeMs,
            distanceKm = distanceKm,
            harshAccelCount = harshAccel,
            harshBrakeCount = harshBrake,
            maxSpeedKmh = maxSpeedKmh,
            score = ScoreCalculator.total(safety, eco, config),
            safetyScore = safety,
            ecoScore = eco,
            harshSteerCount = harshSteer,
            ecoDetail = detail,
            roadKm = currentRoadKm,
        )
    }
}
