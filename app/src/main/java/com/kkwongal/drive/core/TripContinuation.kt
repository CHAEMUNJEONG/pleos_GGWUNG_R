package com.kkwongal.drive.core

import com.kkwongal.drive.rust.RustBridge

data class OngoingTrip(
    val startWallMs: Long,
    val savedAtMs: Long,
    val snapshot: TripSnapshot,
    val startEnergy: EnergySnapshot?,
    val events: List<LiveEvent>,
)

/** 주행을 이어 붙일지 정하는 규칙. 모든 판정은 Rust 코어(kkwongal-core::trip_continuation)에서 수행한다. */
object TripContinuation {
    const val RESUME_WINDOW_MS = 3 * 60_000L
    const val RESUME_RADIUS_M = 300.0
    const val RECOVER_WINDOW_MS = 10 * 60_000L

    fun canContinue(stoppedAtMs: Long, nowMs: Long, stoppedAt: GeoPoint?, here: GeoPoint?): Boolean =
        RustBridge.nativeTripCanContinue(
            stoppedAtMs,
            nowMs,
            RustBridge.toJson(stoppedAt),
            RustBridge.toJson(here),
        )

    fun canRecover(savedAtMs: Long, nowMs: Long): Boolean =
        RustBridge.nativeTripCanRecover(savedAtMs, nowMs)

    fun marks(events: List<LiveEvent>, startWallMs: Long, endWallMs: Long): List<Pair<HarshType, Float>> =
        RustBridge.parseMarks(RustBridge.nativeTripMarks(RustBridge.toJson(events)!!, startWallMs, endWallMs))
            .map { it.type to it.fraction }
}
