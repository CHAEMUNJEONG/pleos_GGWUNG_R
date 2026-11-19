package com.kkwongal.drive.core

import kotlin.math.roundToInt

/**
 * 주행이 끝난 뒤의 코칭. 점수만 보여 주지 않고, 내 평소와 비교하고 다음에 고칠 한 가지를 알려 준다.
 * 다른 사람이 아니라 나와 비교하므로 차종·출퇴근 길이 달라도 공정하다.
 */
object Coaching {
    /** 평소 값으로 쓸 최근 주행 수. */
    const val RECENT_TRIPS = 5

    /**
     * 이번 에코 점수가 최근 [RECENT_TRIPS]번 평균보다 몇 점 높은지. 앞선 기록이 2번 미만이면 null.
     * [history] 에는 이번 주행이 들어 있지 않아야 한다.
     */
    fun ecoDelta(result: TripResult, history: List<TripResult>): Int? {
        val recent = history.takeLast(RECENT_TRIPS)
        if (recent.size < 2) return null
        return (result.ecoScore - recent.map { it.ecoScore }.average()).roundToInt()
    }

    /** 리포트에 쓰는 평소 대비 한 줄. */
    fun deltaText(delta: Int): String = when {
        delta >= 1 -> "평소보다 에코 +${delta}점"
        delta <= -1 -> "평소보다 에코 ${delta}점"
        else -> "에코 점수가 평소와 같아요"
    }

    /**
     * 주차했을 때 읽어 주는 한 줄 요약. 짧게: 종합 점수, 평소 대비, 아쉬운 습관 하나.
     * 예: "주행 끝. 종합 82점이에요. 에코는 평소보다 6점 높아요. 미리 감속이 아쉬웠어요."
     */
    fun parkingSummary(result: TripResult, delta: Int?): String = buildString {
        append("주행 끝. 종합 ${result.score}점이에요.")
        when {
            delta == null -> Unit
            delta >= 1 -> append(" 에코는 평소보다 ${delta}점 높아요.")
            delta <= -1 -> append(" 에코는 평소보다 ${-delta}점 낮아요.")
            else -> append(" 에코는 평소와 같아요.")
        }
        val weakest = result.ecoDetail?.weakest()
        if (weakest != null) {
            append(" ${withSubject(weakest.label)} 아쉬웠어요.")
        } else if (result.ecoDetail != null) {
            append(" 운전 습관이 모두 좋았어요.")
        }
    }

    /** 받침이 있으면 "이", 없으면 "가"를 붙인다. 예: 공회전이, 적정 속도가. */
    fun withSubject(word: String): String {
        val last = word.lastOrNull() ?: return word
        if (last !in '가'..'힣') return "${word}이"
        val hasFinal = (last - '가') % 28 != 0
        return word + if (hasFinal) "이" else "가"
    }
}
