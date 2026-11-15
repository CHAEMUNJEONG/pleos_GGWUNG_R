package com.kkwongal.drive.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.random.Random

data class LeagueConfig(
    /** 같은 리그의 다른 운전자 수. */
    val rivalCount: Int = 19,
    /** 한 주에 이만큼은 달려야 순위에 오른다. */
    val minWeeklyKm: Double = 1.0,
    /** 상위 10% 리워드. */
    val topTierPoints: Int = 500,
    /** 상위 30% 리워드. */
    val midTierPoints: Int = 200,
    /** 순위에 오른 모든 참가자 리워드. */
    val participationPoints: Int = 50,
)

data class LeagueEntry(val name: String, val averageScore: Double, val isMe: Boolean)

data class LeagueStanding(
    val weekStartMs: Long,
    /** 점수가 높은 순서. */
    val entries: List<LeagueEntry>,
    /** 내 순위(1부터). 이번 주 주행 거리가 모자라면 null. */
    val myRank: Int?,
    val myAverage: Double?,
    val myDistanceKm: Double,
    val myTripCount: Int,
    /** 이 순위로 주가 끝나면 받는 포인트. */
    val rewardPoints: Int,
)

/**
 * 주간 리그. 한 주(월요일 0시 시작)의 평균 주행 점수로 순위를 매긴다.
 * 주행 거리 합계가 아니라 평균 점수라서, 많이 달린다고 유리해지지 않는다.
 *
 * 시연용: 서버가 없으므로 다른 운전자는 주마다 고정된 가상 데이터이고,
 * 포인트는 앱 안에서만 쓰는 가상 포인트다. 실제 서비스에서는 rivals() 를
 * 서버 응답으로 바꾸면 된다.
 */
object League {

    private val RIVAL_NAMES = listOf(
        "부드러운코너", "새벽드라이버", "에코마스터", "한강러너", "느긋한출근길",
        "주말탐험가", "정속주행", "초록신호", "바람따라", "동해일출",
        "남도여행", "첫차타는사람", "조용한엔진", "노을길", "고속도로산책",
        "안전제일", "커피한잔", "별보는밤", "제주한바퀴", "골목대장",
    )

    /** 그 시각이 속한 주의 시작(월요일 0시) 시각. */
    fun weekStartMs(timeMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atStartOfDay(zone).toInstant().toEpochMilli()

    /** 순위에 따른 리워드. */
    fun rewardFor(rank: Int, size: Int, config: LeagueConfig = LeagueConfig()): Int = when {
        rank <= ceil(size * 0.1).toInt() -> config.topTierPoints
        rank <= ceil(size * 0.3).toInt() -> config.midTierPoints
        else -> config.participationPoints
    }

    /** 가상 운전자들. 같은 주에는 항상 같은 결과가 나온다. */
    private fun rivals(weekStartMs: Long, config: LeagueConfig): List<LeagueEntry> {
        val random = Random(weekStartMs / 86_400_000L)
        return List(config.rivalCount) { index ->
            val name = RIVAL_NAMES[index % RIVAL_NAMES.size] +
                if (index >= RIVAL_NAMES.size) "${index / RIVAL_NAMES.size + 1}" else ""
            val score = ((62.0 + random.nextDouble() * 34.0) * 10).roundToInt() / 10.0
            LeagueEntry(name, score, isMe = false)
        }
    }

    /** [nowMs] 가 속한 주의 순위표. */
    fun standing(
        trips: List<TripResult>,
        nowMs: Long,
        zone: ZoneId,
        config: LeagueConfig = LeagueConfig(),
    ): LeagueStanding {
        val weekStart = weekStartMs(nowMs, zone)
        val mine = trips.filter { weekStartMs(it.endTimeMs, zone) == weekStart }
        val distance = mine.sumOf { it.distanceKm }
        val qualified = mine.isNotEmpty() && distance >= config.minWeeklyKm
        val average = if (mine.isEmpty()) null else mine.map { it.score }.average()

        val entries = buildList {
            addAll(rivals(weekStart, config))
            if (qualified) add(LeagueEntry("나", average!!, isMe = true))
        }.sortedWith(compareByDescending<LeagueEntry> { it.averageScore }.thenByDescending { it.isMe })

        val rank = entries.indexOfFirst { it.isMe }.takeIf { it >= 0 }?.plus(1)
        return LeagueStanding(
            weekStartMs = weekStart,
            entries = entries,
            myRank = rank,
            myAverage = average,
            myDistanceKm = distance,
            myTripCount = mine.size,
            rewardPoints = if (rank == null) 0 else rewardFor(rank, entries.size, config),
        )
    }

    /** 이미 끝난 주들에서 받은 포인트 합계. 이번 주는 아직 끝나지 않았으므로 넣지 않는다. */
    fun earnedPoints(
        trips: List<TripResult>,
        nowMs: Long,
        zone: ZoneId,
        config: LeagueConfig = LeagueConfig(),
    ): Int {
        val currentWeek = weekStartMs(nowMs, zone)
        return trips.map { weekStartMs(it.endTimeMs, zone) }
            .distinct()
            .filter { it < currentWeek }
            .sumOf { standing(trips, it, zone, config).rewardPoints }
    }
}
