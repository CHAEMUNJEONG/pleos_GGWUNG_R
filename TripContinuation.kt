package com.kkwongal.drive.core

/**
 * 진행 중이던 주행. 앱이 갑자기 꺼져도 주행이 사라지지 않도록 주기적으로 저장한다.
 * savedAtMs 는 마지막으로 저장한 실제 시각.
 */
data class OngoingTrip(
    val startWallMs: Long,
    val savedAtMs: Long,
    val snapshot: TripSnapshot,
    val startEnergy: EnergySnapshot?,
    val events: List<LiveEvent>,
)

/** 주행을 이어 붙일지 정하는 규칙. */
object TripContinuation {
    /** 주차 후 이 시간 안에 다시 출발하면 같은 주행으로 본다 (주유, 짐 싣기, 주차 자리 옮기기). */
    const val RESUME_WINDOW_MS = 3 * 60_000L

    /** 이어 붙이려면 주차했던 곳에서 이 거리 안에서 다시 출발해야 한다. */
    const val RESUME_RADIUS_M = 300.0

    /** 앱이 다시 켜졌을 때, 마지막 저장이 이 시간 안이고 차가 달리는 중이면 그 주행을 이어 간다. */
    const val RECOVER_WINDOW_MS = 10 * 60_000L

    /** 잠깐 정차 후 다시 출발했을 때 앞 주행에 이어 붙일지. 위치를 모르면 시간만 본다. */
    fun canContinue(stoppedAtMs: Long, nowMs: Long, stoppedAt: GeoPoint?, here: GeoPoint?): Boolean {
        val gap = nowMs - stoppedAtMs
        if (gap < 0 || gap > RESUME_WINDOW_MS) return false
        if (stoppedAt != null && here != null && distanceMeters(stoppedAt, here) > RESUME_RADIUS_M) return false
        return true
    }

    /** 앱이 다시 켜졌을 때 저장해 둔 주행을 이어 갈지. */
    fun canRecover(savedAtMs: Long, nowMs: Long): Boolean {
        val gap = nowMs - savedAtMs
        return gap in 0..RECOVER_WINDOW_MS
    }

    /** 감점 기록을 리포트 시간 막대의 점으로 바꾼다. */
    fun marks(events: List<LiveEvent>, startWallMs: Long, endWallMs: Long): List<Pair<HarshType, Float>> {
        val duration = (endWallMs - startWallMs).coerceAtLeast(1L).toFloat()
        return events.map { it.type to ((it.wallTimeMs - startWallMs) / duration).coerceIn(0f, 1f) }
    }
}
