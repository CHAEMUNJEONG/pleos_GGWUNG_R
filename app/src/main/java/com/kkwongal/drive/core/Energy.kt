package com.kkwongal.drive.core

import com.kkwongal.drive.rust.RustBridge

enum class EnergyKind(val unit: String, val label: String) {
    EV("km/kWh", "전비"),
    FUEL("km/L", "연비"),
}

data class EnergySnapshot(
    val evBatteryWh: Double? = null,
    val fuelPercent: Double? = null,
    val fuelCapacityL: Double? = null,
    val odometerKm: Double? = null,
    val vehicleEvKmPerKwh: Double? = null,
    val vehicleFuelKmPerL: Double? = null,
)

data class Efficiency(val kind: EnergyKind, val value: Double, val distanceKm: Double, val used: Double)

/**
 * 연비·전비 계산과 내 평소 기록 대비 에코 점수 반영. 모든 계산은 Rust 코어(kkwongal-core::energy)에서 수행한다.
 */
object EnergyMeter {
    const val MIN_DISTANCE_KM = 0.3
    const val MIN_EV_KWH = 0.05
    const val MIN_FUEL_L = 0.3
    const val BASELINE_TRIPS = 10

    fun measure(start: EnergySnapshot?, end: EnergySnapshot?, fallbackDistanceKm: Double): Efficiency? =
        RustBridge.nativeEnergyMeasure(RustBridge.toJson(start), RustBridge.toJson(end), fallbackDistanceKm)
            ?.let(RustBridge::parseEfficiency)

    fun improvementPercent(current: Efficiency, previous: List<Double>): Double? =
        RustBridge.nativeEnergyImprovement(RustBridge.toJson(current)!!, RustBridge.toJson(previous)!!)
            ?.toDoubleOrNull()

    fun ecoFromImprovement(improvementPercent: Double): Int =
        RustBridge.nativeEnergyEcoFromImprovement(improvementPercent)

    fun apply(
        result: TripResult,
        efficiency: Efficiency?,
        history: List<TripResult>,
        config: ScoreConfig = ScoreConfig(),
    ): TripResult = RustBridge.parseTripResult(
        RustBridge.nativeEnergyApply(
            RustBridge.toJson(result)!!,
            RustBridge.toJson(efficiency),
            RustBridge.toJson(history)!!,
            RustBridge.toJson(config),
        ),
    )
}
