package com.kkwongal.drive.core

import com.kkwongal.drive.rust.RustBridge
import java.time.ZoneId

data class LeagueConfig(
    val rivalCount: Int = 19,
    val minWeeklyKm: Double = 1.0,
    val minTripKm: Double = 1.0,
    val priorScore: Double = 80.0,
    val priorKm: Double = 20.0,
    val topTierPoints: Int = 500,
    val midTierPoints: Int = 200,
    val participationPoints: Int = 50,
)

data class LeagueEntry(val name: String, val averageScore: Double, val isMe: Boolean)

data class LeagueStanding(
    val weekStartMs: Long,
    val entries: List<LeagueEntry>,
    val myRank: Int?,
    val myAverage: Double?,
    val myLeagueScore: Double?,
    val myDistanceKm: Double,
    val myTripCount: Int,
    val rewardPoints: Int,
)

/**
 * 주간 리그. 순위·보정·가상 운전자(XorWowRandom)·리워드 포인트 계산은 모두 Rust 코어(kkwongal-core::league)에서 수행한다.
 */
object League {
    fun weekStartMs(timeMs: Long, zone: ZoneId): Long =
        RustBridge.nativeLeagueWeekStartMs(timeMs, zone.id)

    fun rewardFor(rank: Int, size: Int, config: LeagueConfig = LeagueConfig()): Int =
        RustBridge.nativeLeagueRewardFor(rank, size, RustBridge.toJson(config))

    fun eligible(trips: List<TripResult>, simulated: Boolean, config: LeagueConfig = LeagueConfig()): List<TripResult> =
        trips.filter { it.simulated == simulated && it.distanceKm >= config.minTripKm }

    fun standing(
        trips: List<TripResult>,
        nowMs: Long,
        zone: ZoneId,
        config: LeagueConfig = LeagueConfig(),
        simulated: Boolean = false,
    ): LeagueStanding = RustBridge.parseLeagueStanding(
        RustBridge.nativeLeagueStanding(
            RustBridge.toJson(trips)!!,
            nowMs,
            zone.id,
            RustBridge.toJson(config),
            simulated,
        ),
    )

    fun earnedPoints(
        trips: List<TripResult>,
        nowMs: Long,
        zone: ZoneId,
        config: LeagueConfig = LeagueConfig(),
        simulated: Boolean = false,
    ): Int = RustBridge.nativeLeagueEarnedPoints(
        RustBridge.toJson(trips)!!,
        nowMs,
        zone.id,
        RustBridge.toJson(config),
        simulated,
    )
}
