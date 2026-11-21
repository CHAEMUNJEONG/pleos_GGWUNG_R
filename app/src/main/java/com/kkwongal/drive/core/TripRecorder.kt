package com.kkwongal.drive.core

import com.kkwongal.drive.rust.RustBridge
import java.io.Closeable

data class ScoreConfig(
    val harshAccelKmhPerSec: Double = 11.0,
    val harshBrakeKmhPerSec: Double = 11.0,
    val harshBrakeNoPedalKmhPerSec: Double = 14.0,
    val brakePedalGraceMs: Long = 1_500L,
    val accelPenalty: Double = 4.0,
    val brakePenalty: Double = 5.0,
    val harshSteerDegPerSec: Double = 60.0,
    val steerMinSpeedKmh: Double = 30.0,
    val steerPenalty: Double = 4.0,
    val windowMs: Long = 1_000L,
    val minSpanMs: Long = 800L,
    val maxGapMs: Long = 5_000L,
    val minDistanceForRateKm: Double = 10.0,
    val ecoBestPke: Double = 0.20,
    val ecoWorstPke: Double = 0.60,
    val safetyWeight: Double = 0.4,
    val eco: EcoConfig = EcoConfig(),
    val speedLimitStaleMs: Long = 5_000L,
)

object ScoreCalculator {
    fun score(
        distanceKm: Double,
        harshAccel: Int,
        harshBrake: Int,
        config: ScoreConfig = ScoreConfig(),
        harshSteer: Int = 0,
    ): Int = RustBridge.nativeScoreSafety(
        distanceKm,
        harshAccel,
        harshBrake,
        harshSteer,
        RustBridge.toJson(config),
    )

    fun eco(pke: Double, config: ScoreConfig = ScoreConfig()): Int =
        RustBridge.nativeScoreEco(pke, RustBridge.toJson(config))

    fun total(safety: Int, eco: Int, config: ScoreConfig = ScoreConfig()): Int =
        RustBridge.nativeScoreTotal(safety, eco, RustBridge.toJson(config))
}

enum class HarshType(val label: String) { ACCEL("급출발"), BRAKE("급정거"), STEER("급조향") }

data class HarshEvent(val type: HarshType, val penalty: Int, val safetyAfter: Int)

data class LiveEvent(
    val type: HarshType,
    val penalty: Int,
    val scoreAfter: Int,
    val wallTimeMs: Long,
    val km: Double,
)

data class TripSnapshot(
    val distanceKm: Double,
    val maxSpeedKmh: Double,
    val harshAccel: Int,
    val harshBrake: Int,
    val harshSteer: Int,
    val positiveKineticSum: Double,
    val eco: EcoState = EcoState(),
)

/**
 * 주행 한 번을 기록한다. 모든 실시간 적분·급조작 감지·에코 습관 누적은
 * Rust 네이티브 핸들(kkwongal-core::trip_recorder::TripRecorder)에서 수행한다.
 */
class TripRecorder(config: ScoreConfig = ScoreConfig()) : Closeable {

    private var handle: Long = RustBridge.nativeRecorderNew(RustBridge.toJson(config))

    private fun state() = RustBridge.parseRecorderState(RustBridge.nativeRecorderStateJson(handle))

    val isActive: Boolean get() = state().active
    val currentDistanceKm: Double get() = state().distanceKm
    val currentHarshAccel: Int get() = state().harshAccel
    val currentHarshBrake: Int get() = state().harshBrake
    val currentHarshSteer: Int get() = state().harshSteer
    val currentSafetyScore: Int get() = state().safetyScore
    val currentEcoBreakdown: EcoBreakdown get() = state().ecoBreakdown
    val currentEcoScore: Int get() = state().ecoScore
    val currentRoadKm: RoadKm get() = state().roadKm
    val currentTotalScore: Int get() = state().totalScore

    fun start() {
        RustBridge.nativeRecorderStart(handle)
    }

    fun snapshot(): TripSnapshot = state().snapshot

    fun resume(snapshot: TripSnapshot) {
        RustBridge.nativeRecorderResume(handle, RustBridge.toJson(snapshot)!!)
    }

    fun onSpeedLimit(timeMs: Long, limitKmh: Int) {
        RustBridge.nativeRecorderOnSpeedLimit(handle, timeMs, limitKmh)
    }

    fun onBrakePedal(timeMs: Long, pressed: Boolean) {
        RustBridge.nativeRecorderOnBrakePedal(handle, timeMs, pressed)
    }

    fun onSpeed(timeMs: Long, speedKmh: Double): HarshEvent? =
        RustBridge.nativeRecorderOnSpeed(handle, timeMs, speedKmh)?.let(RustBridge::parseHarshEvent)

    fun onSteering(timeMs: Long, angleDeg: Double): HarshEvent? =
        RustBridge.nativeRecorderOnSteering(handle, timeMs, angleDeg)?.let(RustBridge::parseHarshEvent)

    fun finish(startWallTimeMs: Long, endWallTimeMs: Long): TripResult =
        RustBridge.parseTripResult(RustBridge.nativeRecorderFinish(handle, startWallTimeMs, endWallTimeMs))

    override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            RustBridge.nativeRecorderFree(h)
        }
    }

    @Suppress("removal")
    protected fun finalize() {
        close()
    }
}
