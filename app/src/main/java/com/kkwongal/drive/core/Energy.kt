package com.kkwongal.drive.core

import kotlin.math.roundToInt

/** 어떤 에너지로 달리는 차인가. */
enum class EnergyKind(val unit: String, val label: String) {
    EV("km/kWh", "전비"),
    FUEL("km/L", "연비"),
}

/**
 * 한 시점의 에너지 상태. 차량에서 읽을 수 없는 값은 null.
 * - evBatteryWh: 전기차 배터리 남은 양 (Wh)
 * - fuelPercent, fuelCapacityL: 연료 남은 비율(%)과 연료 탱크 용량(L)
 * - odometerKm: 누적 주행거리
 */
data class EnergySnapshot(
    val evBatteryWh: Double? = null,
    val fuelPercent: Double? = null,
    val fuelCapacityL: Double? = null,
    val odometerKm: Double? = null,
    /** 차량이 직접 계산한 이번 주행(시동 이후)의 전비 (km/kWh). Pleos Vehicle SDK getCurrentEvEfficiency. */
    val vehicleEvKmPerKwh: Double? = null,
    /** 차량이 직접 계산한 이번 주행(시동 이후)의 연비 (km/L). Pleos Vehicle SDK getCurrentFuelEconomy 를 환산. */
    val vehicleFuelKmPerL: Double? = null,
)

/** 실제로 측정한 연비(전비). value 단위는 kind.unit. */
data class Efficiency(val kind: EnergyKind, val value: Double, val distanceKm: Double, val used: Double)

object EnergyMeter {
    /** 이보다 짧은 주행은 연비를 재지 않는다. */
    const val MIN_DISTANCE_KM = 0.3

    /** 전기차: 이보다 적게 쓰면 오차가 커서 재지 않는다 (kWh). */
    const val MIN_EV_KWH = 0.05

    /** 내연기관차: 연료 잔량이 1% 단위라 이보다 적게 쓰면 재지 않는다 (L). */
    const val MIN_FUEL_L = 0.3

    /**
     * 주행 시작과 끝의 에너지 상태로 실제 연비(전비)를 계산한다.
     * 거리는 누적 주행거리 차이를 먼저 쓰고, 없으면 속도로 계산한 거리를 쓴다.
     * 측정하기에 주행이 짧거나 값이 없으면 null.
     */
    fun measure(start: EnergySnapshot?, end: EnergySnapshot?, fallbackDistanceKm: Double): Efficiency? {
        val odoDelta = if (start?.odometerKm != null && end?.odometerKm != null) end.odometerKm - start.odometerKm else null
        val distance = if (odoDelta != null && odoDelta > 0) odoDelta else fallbackDistanceKm
        if (distance < MIN_DISTANCE_KM) return null
        return measureFromLevels(start, end, distance) ?: fromVehicle(end, distance)
    }

    /** 출발·도착 때 잔량 차이로 잰다. 가장 정확하지만, 쓴 양이 너무 적으면 재지 않는다. */
    private fun measureFromLevels(start: EnergySnapshot?, end: EnergySnapshot?, distance: Double): Efficiency? {
        if (start == null || end == null) return null
        if (start.evBatteryWh != null && end.evBatteryWh != null) {
            val usedKWh = (start.evBatteryWh - end.evBatteryWh) / 1000.0
            if (usedKWh < MIN_EV_KWH) return null
            return Efficiency(EnergyKind.EV, distance / usedKWh, distance, usedKWh)
        }
        val capacity = end.fuelCapacityL ?: start.fuelCapacityL
        if (start.fuelPercent != null && end.fuelPercent != null && capacity != null && capacity > 0) {
            val usedL = (start.fuelPercent - end.fuelPercent) / 100.0 * capacity
            if (usedL < MIN_FUEL_L) return null
            return Efficiency(EnergyKind.FUEL, distance / usedL, distance, usedL)
        }
        return null
    }

    /**
     * 잔량으로 잴 수 없을 때(연료 잔량이 1% 단위라 짧은 주행에서 변화가 없는 경우 등)
     * 차량이 직접 계산한 이번 주행의 전비·연비를 쓴다.
     */
    private fun fromVehicle(end: EnergySnapshot?, distance: Double): Efficiency? {
        if (end == null) return null
        end.vehicleEvKmPerKwh?.takeIf { it in 0.5..20.0 }?.let {
            return Efficiency(EnergyKind.EV, it, distance, distance / it)
        }
        end.vehicleFuelKmPerL?.takeIf { it in 1.0..60.0 }?.let {
            return Efficiency(EnergyKind.FUEL, it, distance, distance / it)
        }
        return null
    }

    /** 평소 값으로 쓸 최근 측정 개수. */
    const val BASELINE_TRIPS = 10

    /**
     * 내 차의 평소 기록과 비교한 개선률(%). 이전 측정이 2번 이상 있어야 계산한다.
     * 평소 값은 최근 [BASELINE_TRIPS]번의 중앙값이라, 유난히 좋거나 나빴던 한 번에 흔들리지 않는다.
     * 다른 사람과 비교하지 않으므로 차종이 달라도 공정하다.
     */
    fun improvementPercent(current: Efficiency, previous: List<Double>): Double? {
        if (previous.size < 2) return null
        val baseline = median(previous.takeLast(BASELINE_TRIPS))
        if (baseline <= 0) return null
        return (current.value / baseline - 1.0) * 100.0
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /** 개선률을 에코 점수로 바꾼다. 평소와 같으면 75점, 20% 좋으면 100점, 40% 나쁘면 25점. */
    fun ecoFromImprovement(improvementPercent: Double): Int =
        (75.0 + improvementPercent * 1.25).roundToInt().coerceIn(0, 100)

    /**
     * 실제 측정값을 주행 결과에 반영한다. 평소 기록과 비교할 수 있으면 에코 점수를
     * (속도 패턴 추정 + 실제 측정) 평균으로 바꾸고, 종합 점수도 다시 계산한다.
     */
    fun apply(
        result: TripResult,
        efficiency: Efficiency?,
        history: List<TripResult>,
        config: ScoreConfig = ScoreConfig(),
    ): TripResult {
        if (efficiency == null) return result
        val sameKind = history.filter { it.efficiencyKind == efficiency.kind.name && it.efficiency != null }
        // 시내끼리, 고속도로끼리 비교한다. 같은 도로 종류 기록이 모자라면 전체 기록과 비교한다.
        val sameRoad = sameKind.filter { it.roadType == result.roadType }
        val previous = (if (sameRoad.size >= 2) sameRoad else sameKind).mapNotNull { it.efficiency }
        val improvement = improvementPercent(efficiency, previous)
        val eco = if (improvement == null) {
            result.ecoScore
        } else {
            ((result.ecoScore + ecoFromImprovement(improvement)) / 2.0).roundToInt()
        }
        return result.copy(
            ecoScore = eco,
            score = ScoreCalculator.total(result.safetyScore, eco, config),
            efficiency = efficiency.value,
            efficiencyKind = efficiency.kind.name,
            efficiencyImprovement = improvement,
        )
    }
}
