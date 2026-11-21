package com.kkwongal.drive.core

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
    /** 종합 점수 (안전 + 에코). */
    val score: Int,
    /** 안전 점수: 급가속·급제동 기준. */
    val safetyScore: Int = score,
    /** 에코 점수(추정): 속도 변화 패턴 기준. */
    val ecoScore: Int = score,
) {
    val durationMin: Int get() = ((endTimeMs - startTimeMs) / 60_000L).toInt()
}

/** 처음 가 본 장소에 찍히는 스탬프. */
data class Stamp(
    val id: Int,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val timeMs: Long,
    val score: Int,
    /** 이곳에 대해 남긴 한마디. */
    val memo: String = "",
    /** 고른 태그. StampTags.ALL 중에서 고른다. */
    val tags: List<String> = emptyList(),
) {
    val point: GeoPoint get() = GeoPoint(latitude, longitude)
}

/** 스탬프에 붙일 수 있는 태그. 운전자 관점의 정보 위주. */
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

/** 배지. 조건은 BadgeRules 에 있다. */
enum class Badge(val title: String, val description: String) {
    FIRST_STAMP("첫 발자국", "첫 스탬프를 찍었어요"),
    EXPLORER_5("탐험가", "스탬프 5개를 모았어요"),
    SMOOTH_3("부드러운 손길", "90점 이상 주행을 3번 했어요"),
    PERFECT("무결점 주행", "급가속·급제동 없이 주행을 마쳤어요"),
}
