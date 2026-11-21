package com.kkwongal.drive.core

import com.kkwongal.drive.rust.RustBridge

/**
 * 주행이 끝난 뒤의 코칭. 모든 계산과 문장 조합은 Rust 코어(kkwongal-core::coaching)에서 수행한다.
 */
object Coaching {
    const val RECENT_TRIPS = 5

    fun ecoDelta(result: TripResult, history: List<TripResult>): Int? =
        RustBridge.nativeCoachingEcoDelta(RustBridge.toJson(result)!!, RustBridge.toJson(history)!!)
            ?.toIntOrNull()

    fun deltaText(delta: Int): String =
        RustBridge.nativeCoachingDeltaText(delta)

    fun parkingSummary(result: TripResult, delta: Int?): String =
        RustBridge.nativeCoachingParkingSummary(RustBridge.toJson(result)!!, delta != null, delta ?: 0)

    fun withSubject(word: String): String =
        RustBridge.nativeCoachingWithSubject(word)
}
