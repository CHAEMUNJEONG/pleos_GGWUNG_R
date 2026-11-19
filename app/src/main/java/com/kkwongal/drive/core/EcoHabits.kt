package com.kkwongal.drive.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 에코 점수를 이루는 운전 습관 기준. 가속(PKE) 기준은 ScoreConfig 에 있다.
 * 정속·미리 감속 기준은 공인 기준이 없어 시험값이며, 실제 차량 주행으로 조정한다.
 */
data class EcoConfig(
    /** 이 속도 이상으로 달리는 구간을 "정속 주행" 구간으로 본다. */
    val cruiseMinKmh: Double = 60.0,
    /** 정속 구간이 이보다 짧으면 정속 항목은 매기지 않는다 (초). */
    val minCruiseSec: Double = 60.0,
    /** 정속 구간에서 1초 동안 속도가 평균 이만큼 이하로 변하면 100점 (km/h). */
    val steadyBest: Double = 0.5,
    /** 이만큼 이상 변하면 0점 (km/h). */
    val steadyWorst: Double = 2.0,

    /** 제한속도보다 이만큼 빠르면 고속 주행으로 본다 (계기판·내비 오차 감안). */
    val overSpeedMarginKmh: Double = 10.0,
    /** 제한속도를 모를 때는 이 속도를 넘으면 고속 주행으로 본다 (고속도로 최고 제한속도). */
    val fallbackOverSpeedKmh: Double = 110.0,
    /** 고속 주행 거리가 전체의 이 비율 이상이면 적정 속도 0점. */
    val overSpeedWorstShare: Double = 0.3,
    /** 이보다 짧은 주행은 적정 속도 항목을 매기지 않는다 (km). */
    val minSpeedItemKm: Double = 1.0,

    /** 감속 중 브레이크를 밟지 않고 줄인 비율이 이 이상이면 미리 감속 100점. */
    val coastBestShare: Double = 0.6,
    /** 이 이하면 0점. */
    val coastWorstShare: Double = 0.1,
    /** 감속한 양이 이보다 적으면 (시속 50km 에서 세 번 멈춘 정도) 매기지 않는다. 단위 m²/s². */
    val minDecelEnergy: Double = 500.0,

    /** 한 번 멈춰 서서 이 시간까지는 신호 대기로 보고, 넘는 시간만 공회전으로 센다 (초). */
    val idleGraceSec: Double = 120.0,
    /** 공회전 1분마다 깎는 점수. */
    val idlePenaltyPerMin: Double = 10.0,
    /** 이보다 느리면 멈춰 있는 것으로 본다 (km/h). */
    val stoppedKmh: Double = 1.0,

    /** 항목별 비중. 매기지 않은 항목은 빼고 나머지 비중으로 나눈다. */
    val weightAccel: Double = 0.40,
    val weightSteady: Double = 0.20,
    val weightSpeed: Double = 0.15,
    val weightCoast: Double = 0.15,
    val weightIdle: Double = 0.10,
)

/** 에코 점수 항목. */
enum class EcoItem(val label: String, val tip: String) {
    ACCEL("부드러운 가속", "출발할 때 3초만 더 천천히 밟아 보세요"),
    STEADY("정속 주행", "고속 구간에서는 속도를 일정하게 유지해 보세요"),
    SPEED("적정 속도", "제한속도 안에서 달리면 공기 저항이 줄어요"),
    COAST("미리 감속", "앞이 막히면 일찍 발을 떼고 천천히 줄여 보세요"),
    IDLE("공회전", "오래 설 때는 시동을 꺼 보세요"),
}

/**
 * 에코 점수 세부 항목 (각 0~100점). 매기지 않은 항목은 null.
 * overSpeedPercent·coastPercent·idleMin 은 리포트 설명용 원래 값이다.
 */
data class EcoBreakdown(
    val accel: Int,
    val steady: Int? = null,
    val speed: Int? = null,
    val coast: Int? = null,
    val idle: Int? = null,
    val overSpeedPercent: Int? = null,
    val coastPercent: Int? = null,
    val idleMin: Double = 0.0,
) {
    /** 항목별 점수. 매긴 항목만. */
    fun items(): List<Pair<EcoItem, Int>> = listOfNotNull(
        EcoItem.ACCEL to accel,
        steady?.let { EcoItem.STEADY to it },
        speed?.let { EcoItem.SPEED to it },
        coast?.let { EcoItem.COAST to it },
        idle?.let { EcoItem.IDLE to it },
    )

    /** 가장 아쉬운 항목. 모두 90점 이상이면 null. */
    fun weakest(): EcoItem? = items().filter { it.second < 90 }.minByOrNull { it.second }?.first

    /** 항목을 비중대로 합친 에코 점수. */
    fun score(config: EcoConfig = EcoConfig()): Int {
        var sum = 0.0
        var weight = 0.0
        for ((item, value) in items()) {
            val w = when (item) {
                EcoItem.ACCEL -> config.weightAccel
                EcoItem.STEADY -> config.weightSteady
                EcoItem.SPEED -> config.weightSpeed
                EcoItem.COAST -> config.weightCoast
                EcoItem.IDLE -> config.weightIdle
            }
            sum += value * w
            weight += w
        }
        return if (weight > 0) (sum / weight).roundToInt().coerceIn(0, 100) else accel
    }
}

/** 도로 종류별로 달린 거리 (km). 내비가 알려 준 제한속도로 나눈다. */
data class RoadKm(val city: Double = 0.0, val road: Double = 0.0, val highway: Double = 0.0) {
    val total: Double get() = city + road + highway

    fun add(type: RoadType, km: Double): RoadKm = when (type) {
        RoadType.CITY -> copy(city = city + km)
        RoadType.MIXED -> copy(road = road + km)
        RoadType.HIGHWAY -> copy(highway = highway + km)
    }

    /** 가장 많이 달린 도로 종류. */
    fun dominant(): RoadType? = when {
        total <= 0.0 -> null
        highway >= road && highway >= city -> RoadType.HIGHWAY
        road >= city -> RoadType.MIXED
        else -> RoadType.CITY
    }
}

/** 이어 가기를 위해 저장하는 누적 값. */
data class EcoState(
    val cruiseSec: Double = 0.0,
    val cruiseChange: Double = 0.0,
    val overSpeedKm: Double = 0.0,
    val coastEnergy: Double = 0.0,
    val brakeEnergy: Double = 0.0,
    val idleSec: Double = 0.0,
    val roadKm: RoadKm = RoadKm(),
)

/**
 * 속도·브레이크·제한속도로 에코 운전 습관을 잰다. TripRecorder 가 속도 값마다 호출한다.
 * - 정속 주행: 시속 60km 이상에서 1초마다 속도가 얼마나 변했는지
 * - 적정 속도: 제한속도(모르면 시속 110km)를 넘겨 달린 거리 비율
 * - 미리 감속: 줄인 운동 에너지 중 브레이크 없이(발을 떼고) 줄인 비율. 페달 신호가 있을 때만
 * - 공회전: 한 번에 2분 넘게 멈춰 있던 시간
 * - 도로 종류: 제한속도 90 이상 고속도로(자동차전용도로 포함), 60~80 국도, 50 이하 시내
 */
class EcoTracker(private val config: EcoConfig = EcoConfig()) {
    var state = EcoState()
        private set

    private var cruiseRefMs: Long? = null
    private var cruiseRefKmh = 0.0
    private var stoppedSec = 0.0

    fun reset(from: EcoState = EcoState()) {
        state = from
        cruiseRefMs = null
        stoppedSec = 0.0
    }

    /** 값이 오래 끊겼을 때. 구간 비교를 처음부터 다시 한다. */
    fun gap() {
        cruiseRefMs = null
        stoppedSec = 0.0
    }

    /**
     * 이전 값([prevKmh])에서 지금 값([kmh])까지 [dtMs] 동안 [km] 를 달렸다.
     * [limitKmh] 는 지금 도로의 제한속도 (모르면 null), [pedal] 은 브레이크를 밟고 있는지 (모르면 null).
     */
    fun onSegment(timeMs: Long, dtMs: Long, prevKmh: Double, kmh: Double, km: Double, limitKmh: Int?, pedal: Boolean?) {
        var s = state
        val dtSec = dtMs / 1000.0

        // 공회전: 멈춰 선 지 [idleGraceSec] 를 넘긴 시간만
        if (kmh < config.stoppedKmh && prevKmh < config.stoppedKmh) {
            val before = stoppedSec
            stoppedSec += dtSec
            val extra = (stoppedSec - config.idleGraceSec).coerceAtLeast(0.0) -
                (before - config.idleGraceSec).coerceAtLeast(0.0)
            if (extra > 0) s = s.copy(idleSec = s.idleSec + extra)
        } else {
            stoppedSec = 0.0
        }

        // 적정 속도와 도로 종류
        val avg = (prevKmh + kmh) / 2.0
        val threshold = limitKmh?.let { it + config.overSpeedMarginKmh } ?: config.fallbackOverSpeedKmh
        if (avg > threshold) s = s.copy(overSpeedKm = s.overSpeedKm + km)
        if (limitKmh != null) s = s.copy(roadKm = s.roadKm.add(RoadType.ofLimit(limitKmh), km))

        // 미리 감속: 줄어든 (속도 제곱)을 페달을 밟았는지에 따라 나눠 담는다
        if (pedal != null && kmh < prevKmh) {
            val v0 = prevKmh / 3.6
            val v1 = kmh / 3.6
            val energy = v0 * v0 - v1 * v1
            s = if (pedal) s.copy(brakeEnergy = s.brakeEnergy + energy) else s.copy(coastEnergy = s.coastEnergy + energy)
        }

        // 정속 주행: 시속 60km 이상에서 약 1초마다 속도 변화를 더한다 (센서 잡음을 줄이려고 1초 단위로 본다)
        if (kmh >= config.cruiseMinKmh) {
            val ref = cruiseRefMs
            if (ref == null) {
                cruiseRefMs = timeMs
                cruiseRefKmh = kmh
            } else if (timeMs - ref >= 1_000L) {
                s = s.copy(
                    cruiseSec = s.cruiseSec + (timeMs - ref) / 1000.0,
                    cruiseChange = s.cruiseChange + abs(kmh - cruiseRefKmh),
                )
                cruiseRefMs = timeMs
                cruiseRefKmh = kmh
            }
        } else {
            cruiseRefMs = null
        }
        state = s
    }

    /** 지금까지의 항목별 점수. [accel] 은 가속(PKE) 점수, [distanceKm] 는 전체 주행 거리. */
    fun breakdown(accel: Int, distanceKm: Double): EcoBreakdown {
        val s = state
        val steady = if (s.cruiseSec >= config.minCruiseSec) {
            val perSec = s.cruiseChange / s.cruiseSec
            linear(perSec, config.steadyBest, config.steadyWorst)
        } else null

        val overShare = if (distanceKm >= config.minSpeedItemKm) (s.overSpeedKm / distanceKm).coerceIn(0.0, 1.0) else null
        val speed = overShare?.let { linear(it, 0.0, config.overSpeedWorstShare) }

        val decel = s.coastEnergy + s.brakeEnergy
        val coastShare = if (decel >= config.minDecelEnergy) s.coastEnergy / decel else null
        // 많이 미리 줄일수록 좋으므로 best 가 worst 보다 크다
        val coast = coastShare?.let { linear(it, config.coastBestShare, config.coastWorstShare) }

        val idleMin = s.idleSec / 60.0
        val idle = if (distanceKm >= config.minSpeedItemKm || s.idleSec > 0) {
            (100.0 - idleMin * config.idlePenaltyPerMin).roundToInt().coerceIn(0, 100)
        } else null

        return EcoBreakdown(
            accel = accel,
            steady = steady,
            speed = speed,
            coast = coast,
            idle = idle,
            overSpeedPercent = overShare?.let { (it * 100).roundToInt() },
            coastPercent = coastShare?.let { (it * 100).roundToInt() },
            idleMin = idleMin,
        )
    }

    /** [best] 이면 100점, [worst] 이면 0점, 사이는 직선으로. */
    private fun linear(value: Double, best: Double, worst: Double): Int {
        val ratio = ((value - best) / (worst - best)).coerceIn(0.0, 1.0)
        return (100.0 * (1.0 - ratio)).roundToInt()
    }
}
