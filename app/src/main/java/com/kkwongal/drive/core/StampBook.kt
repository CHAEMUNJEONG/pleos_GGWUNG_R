package com.kkwongal.drive.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 두 지점 사이의 거리(미터). 하버사인 공식. */
fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val earthRadiusM = 6_371_000.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2)
    return 2 * earthRadiusM * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/**
 * 스탬프 모음. 이미 찍힌 스탬프에서 반경 [newPlaceRadiusM] 안이면 같은 장소로 본다.
 */
class StampBook(
    initial: List<Stamp> = emptyList(),
    private val newPlaceRadiusM: Double = 500.0,
) {
    private val items = initial.toMutableList()

    val stamps: List<Stamp> get() = items.toList()

    /** 이 위치 근처에 이미 찍힌 스탬프. 없으면 null. */
    fun findNearby(point: GeoPoint): Stamp? =
        items.firstOrNull { distanceMeters(it.point, point) <= newPlaceRadiusM }

    /** 처음 온 곳이면 스탬프를 찍고 돌려준다. 이미 온 곳이면 null. */
    fun tryAdd(point: GeoPoint, timeMs: Long, score: Int, name: String? = null): Stamp? {
        if (findNearby(point) != null) return null
        val id = (items.maxOfOrNull { it.id } ?: 0) + 1
        val stamp = Stamp(
            id = id,
            name = name?.takeIf { it.isNotBlank() } ?: "새로운 장소 $id",
            latitude = point.latitude,
            longitude = point.longitude,
            timeMs = timeMs,
            score = score,
        )
        items += stamp
        return stamp
    }

    /** 스탬프 이름을 바꾼다. 바뀐 스탬프를 돌려주고, 없는 id 거나 빈 이름이면 null. */
    fun rename(id: Int, name: String): Stamp? {
        if (name.isBlank()) return null
        val current = items.firstOrNull { it.id == id } ?: return null
        return update(id, name, current.memo, current.tags)
    }

    /**
     * 스탬프의 이름·메모·태그를 한 번에 고친다. 바뀐 스탬프를 돌려주고, 없는 id 면 null.
     * 이름이 비어 있으면 기존 이름을 유지하고, 목록에 없는 태그는 버린다.
     */
    fun update(id: Int, name: String, memo: String, tags: List<String>): Stamp? {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return null
        val current = items[index]
        val updated = current.copy(
            name = name.trim().ifBlank { current.name },
            memo = memo.trim().take(StampTags.MAX_MEMO_LENGTH),
            tags = StampTags.ALL.filter { it in tags },
        )
        items[index] = updated
        return updated
    }
}

object BadgeRules {
    fun evaluate(trips: List<TripResult>, stamps: List<Stamp>): Set<Badge> {
        val earned = mutableSetOf<Badge>()
        if (stamps.isNotEmpty()) earned += Badge.FIRST_STAMP
        if (stamps.size >= 5) earned += Badge.EXPLORER_5
        if (trips.count { it.score >= 90 } >= 3) earned += Badge.SMOOTH_3
        if (trips.any { it.harshAccelCount == 0 && it.harshBrakeCount == 0 }) earned += Badge.PERFECT
        return earned
    }
}
