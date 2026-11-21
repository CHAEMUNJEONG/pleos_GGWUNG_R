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
    /** 급조향 횟수. 이전 버전 기록에는 없어서 기본값 0. */
    val harshSteerCount: Int = 0,
    /** 실제 측정한 연비(전비). 측정하지 못했으면 null. 단위는 efficiencyKind 에 따라 km/kWh 또는 km/L. */
    val efficiency: Double? = null,
    /** EnergyKind 이름 (EV, FUEL). */
    val efficiencyKind: String? = null,
    /** 내 평소 기록 대비 개선률(%). 비교할 기록이 부족하면 null. */
    val efficiencyImprovement: Double? = null,
    /** 에코 점수 세부 항목. 이전 버전 기록에는 없어서 null. */
    val ecoDetail: EcoBreakdown? = null,
    /** 내비 제한속도로 나눈 도로 종류별 거리. 제한속도를 받지 못했으면 모두 0. */
    val roadKm: RoadKm = RoadKm(),
    /** 시뮬레이션으로 만든 기록이면 true. 실제 주행 리그와 섞지 않는다. */
    val simulated: Boolean = false,
    /** 이번 주행의 감점 기록. 지난 주행의 리포트를 다시 볼 때 쓴다. 이전 버전 기록에는 없다. */
    val events: List<LiveEvent> = emptyList(),
    /**
     * 사용자가 최근 주행 목록에서 지운 기록이면 true. 목록에서만 숨기고,
     * 평균 점수·리그·포인트·배지·평소 대비 비교에는 그대로 쓴다.
     */
    val hidden: Boolean = false,
) {
    val durationMin: Int get() = ((endTimeMs - startTimeMs) / 60_000L).toInt()

    /** 정차 시간까지 포함한 평균 속도 (km/h). */
    val averageSpeedKmh: Double
        get() {
            val hours = (endTimeMs - startTimeMs) / 3_600_000.0
            return if (hours > 0) distanceKm / hours else 0.0
        }

    /**
     * 이번 주행의 도로 종류. 연비는 도로 종류에 따라 크게 달라서 같은 종류끼리 비교한다.
     * 내비가 알려 준 제한속도로 주행 거리의 절반 이상을 나눌 수 있으면 가장 많이 달린 도로,
     * 아니면 평균 속도로 추정한다.
     */
    val roadType: RoadType
        get() = roadKm.takeIf { distanceKm > 0 && it.total >= distanceKm * 0.5 }?.dominant()
            ?: RoadType.of(averageSpeedKmh)

    /** 도로 종류를 제한속도로 정했으면 true, 평균 속도로 추정했으면 false. */
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
    /** 이곳에 대해 남긴 한마디. */
    val memo: String = "",
    /** 고른 태그. StampTags.ALL 중에서 고른다. */
    val tags: List<String> = emptyList(),
    /** 급조작 없이 도착해 받은 황금알이면 true. */
    val perfect: Boolean = false,
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
    PERFECT("무결점 주행", "급가속·급제동·급조향 없이 주행을 마쳤어요"),
}

/** 도로 종류. 내비 제한속도로 나누고, 모르면 평균 속도(정차 포함)로 추정한다. */
enum class RoadType(val label: String) {
    CITY("시내"),
    MIXED("국도"),
    HIGHWAY("고속도로");

    companion object {
        fun of(averageSpeedKmh: Double): RoadType = when {
            averageSpeedKmh < 30.0 -> CITY
            averageSpeedKmh < 65.0 -> MIXED
            else -> HIGHWAY
        }

        /** 제한속도로 나눈다. 90 이상 고속도로(자동차전용도로 포함), 60~80 국도·간선, 50 이하 시내. */
        fun ofLimit(limitKmh: Int): RoadType = when {
            limitKmh >= 90 -> HIGHWAY
            limitKmh >= 60 -> MIXED
            else -> CITY
        }
    }
}
