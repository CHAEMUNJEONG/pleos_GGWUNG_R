package com.kkwongal.drive.core

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 점수 계산에 쓰는 기준값. 전부 임시 기본값이므로 팀에서 조정한다.
 * 가속도 단위는 "1초에 몇 km/h 변했는가" (km/h/s).
 */
data class ScoreConfig(
    /** 이 값 이상으로 속도가 빨라지면 급가속 1회. */
    val harshAccelKmhPerSec: Double = 8.0,
    /** 이 값 이상으로 속도가 줄면 급제동 1회. */
    val harshBrakeKmhPerSec: Double = 8.0,
    /** 급가속 1회당 감점 (10km 기준). */
    val accelPenalty: Double = 4.0,
    /** 급제동 1회당 감점 (10km 기준). */
    val brakePenalty: Double = 5.0,
    /** 가속도를 구할 때 비교하는 시간 간격. */
    val windowMs: Long = 1_000L,
    /** 이보다 짧은 간격의 변화는 잡음으로 보고 무시한다. */
    val minSpanMs: Long = 800L,
    /** 속도 값이 이보다 오래 끊기면 그 구간은 거리·가속도 계산에서 뺀다. */
    val maxGapMs: Long = 5_000L,
    /** 짧은 주행이 감점을 과하게 받지 않도록, 감점 환산 시 최소 이 거리로 본다. */
    val minDistanceForRateKm: Double = 5.0,
    /** 가속 에너지 지표가 이 값 이하면 에코 100점. 단위 m/s². */
    val ecoBestPke: Double = 0.20,
    /** 가속 에너지 지표가 이 값 이상이면 에코 0점. 단위 m/s². */
    val ecoWorstPke: Double = 0.60,
    /** 종합 점수에서 안전 점수가 차지하는 비율. 나머지는 에코 점수. */
    val safetyWeight: Double = 0.6,
)

object ScoreCalculator {
    /** 안전 점수: 급가속·급제동 횟수를 10km당 감점으로 환산해 100점에서 뺀다. 0~100점. */
    fun score(distanceKm: Double, harshAccel: Int, harshBrake: Int, config: ScoreConfig = ScoreConfig()): Int {
        val per10Km = 10.0 / max(distanceKm, config.minDistanceForRateKm)
        val penalty = (harshAccel * config.accelPenalty + harshBrake * config.brakePenalty) * per10Km
        return (100.0 - penalty).roundToInt().coerceIn(0, 100)
    }

    /**
     * 에코 점수(추정): 1m 를 가는 동안 속도를 올리는 데 쓴 에너지가 적을수록 높다.
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
enum class HarshType(val label: String) { ACCEL("급출발"), BRAKE("급정거") }

/** 급조작 한 번과 그로 인해 깎인 안전 점수. */
data class HarshEvent(val type: HarshType, val penalty: Int, val safetyAfter: Int)

/**
 * 주행 한 번을 기록한다. 안드로이드에 의존하지 않는 순수 계산 코드.
 *
 * 사용 순서: start() → onSpeed() 를 속도 값이 올 때마다 호출 → finish().
 * 시간은 계속 증가하는 시계(SystemClock.elapsedRealtime 등)의 밀리초를 넣는다.
 */
class TripRecorder(private val config: ScoreConfig = ScoreConfig()) {

    private data class Sample(val timeMs: Long, val speedKmh: Double)

    private val window = ArrayDeque<Sample>()
    private var active = false
    private var distanceKm = 0.0
    private var maxSpeedKmh = 0.0
    private var harshAccel = 0
    private var harshBrake = 0
    private var inAccelEvent = false
    private var inBrakeEvent = false

    /** 속도를 올릴 때마다 늘어난 (속도 제곱)의 합. 단위 m²/s². */
    private var positiveKineticSum = 0.0

    val isActive: Boolean get() = active
    val currentDistanceKm: Double get() = distanceKm
    val currentHarshAccel: Int get() = harshAccel
    val currentHarshBrake: Int get() = harshBrake

    /** 지금까지의 안전 점수. 주행 중 화면에 실시간으로 보여 준다. */
    val currentSafetyScore: Int get() = ScoreCalculator.score(distanceKm, harshAccel, harshBrake, config)

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
    }

    /** 속도 값을 하나 넣는다. 이 값으로 급출발·급정거가 새로 감지되면 그 내용을 돌려준다. */
    fun onSpeed(timeMs: Long, speedKmh: Double): HarshEvent? {
        if (!active) return null
        val speed = speedKmh.coerceAtLeast(0.0)
        maxSpeedKmh = max(maxSpeedKmh, speed)

        val last = window.lastOrNull()
        if (last != null) {
            val dtMs = timeMs - last.timeMs
            if (dtMs <= 0) return null
            if (dtMs <= config.maxGapMs) {
                // 사다리꼴 적분: 평균 속도 x 시간
                distanceKm += (last.speedKmh + speed) / 2.0 * dtMs / 3_600_000.0
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

        if (-ratePerSec >= config.harshBrakeKmhPerSec) {
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

    /** 주행을 끝내고 결과를 돌려준다. 시작·종료 시각은 기록용 실제 시각(밀리초). */
    fun finish(startWallTimeMs: Long, endWallTimeMs: Long): TripResult {
        active = false
        val distanceM = distanceKm * 1000.0
        // 가속 에너지 지표: 1m 당 속도를 올리는 데 쓴 에너지. 아주 짧은 주행은 계산하지 않는다.
        val pke = if (distanceM >= 50.0) positiveKineticSum / distanceM else 0.0
        val safety = ScoreCalculator.score(distanceKm, harshAccel, harshBrake, config)
        val eco = ScoreCalculator.eco(pke, config)
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
        )
    }
}
