package com.kkwongal.drive.core

import com.kkwongal.drive.rust.RustBridge

/** 위도·경도 한 점. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/** 주행 한 번의 결과. */
data class TripResult(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val distanceKm: Double,
    val harshAccelCount: Int,
    val harshBrakeCount: Int,
    val maxSpeedKmh: Double,
    val score: Int,
    val safetyScore: Int = score,
    val ecoScore: Int = score,
    val harshSteerCount: Int = 0,
    val efficiency: Double? = null,
    val efficiencyKind: String? = null,
    val efficiencyImprovement: Double? = null,
    val ecoDetail: EcoBreakdown? = null,
    val roadKm: RoadKm = RoadKm(),
    val simulated: Boolean = false,
    val events: List<LiveEvent> = emptyList(),
    val hidden: Boolean = false,
) {
    val durationMin: Int get() = ((endTimeMs - startTimeMs) / 60_000L).toInt()

    val averageSpeedKmh: Double
        get() {
            val hours = (endTimeMs - startTimeMs) / 3_600_000.0
            return if (hours > 0) distanceKm / hours else 0.0
        }

    val roadType: RoadType
        get() = roadKm.takeIf { distanceKm > 0 && it.total >= distanceKm * 0.5 }?.dominant()
            ?: RoadType.of(averageSpeedKmh)

    val roadTypeFromLimit: Boolean get() = distanceKm > 0 && roadKm.total >= distanceKm * 0.5
}

/** 처음 가 본 장소에 찍히는 스탬프. */
data class Stamp(
    val id: Int,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val timeMs: Long,
    val score: Int,
    val memo: String = "",
    val tags: List<String> = emptyList(),
    val perfect: Boolean = false,
) {
    val point: GeoPoint get() = GeoPoint(latitude, longitude)
}

/** 스탬프에 붙일 수 있는 태그. */
object StampTags {
    val ALL = listOf(
        "주차가 편해요",
        "가는 길이 예뻐요",
        "다시 올래요",
        "경치가 좋아요",
        "조용해요",
        "아이와 가기 좋아요",
    )

    const val MAX_MEMO_LENGTH = 200
}

/** 배지. 조건은 BadgeRules(Rust) 에 있다. */
enum class Badge(val title: String, val description: String) {
    FIRST_STAMP("첫 발자국", "첫 스탬프를 찍었어요"),
    EXPLORER_5("탐험가", "스탬프 5개를 모았어요"),
    SMOOTH_3("부드러운 손길", "90점 이상 주행을 3번 했어요"),
    PERFECT("무결점 주행", "급가속·급제동·급조향 없이 주행을 마쳤어요"),
}

/** 도로 종류. 판정 로직은 Rust 코어를 호출한다. */
enum class RoadType(val label: String) {
    CITY("시내"),
    MIXED("국도"),
    HIGHWAY("고속도로");

    companion object {
        fun of(averageSpeedKmh: Double): RoadType =
            RustBridge.parseRoadType(RustBridge.nativeRoadTypeOf(averageSpeedKmh))

        fun ofLimit(limitKmh: Int): RoadType =
            RustBridge.parseRoadType(RustBridge.nativeRoadTypeOfLimit(limitKmh))
    }
}
