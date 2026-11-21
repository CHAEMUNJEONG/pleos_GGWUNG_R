package com.kkwongal.drive.core

/**
 * 꿩알 성장 단계. 새로운 장소의 스탬프 하나가 알 하나이고, 알이 모이면 꿩이 자란다.
 */
object EggLevels {
    data class Level(val name: String, val minEggs: Int)

    val LEVELS = listOf(
        Level("알", 0),
        Level("아기 꿩", 5),
        Level("어른 꿩", 15),
        Level("황금 꿩", 30),
    )

    fun current(eggs: Int): Level = LEVELS.last { eggs >= it.minEggs }

    /** 다음 단계. 마지막 단계면 null. */
    fun next(eggs: Int): Level? = LEVELS.firstOrNull { it.minEggs > eggs }

    /** 다음 단계까지 남은 알 개수. 마지막 단계면 0. */
    fun remaining(eggs: Int): Int = next(eggs)?.let { it.minEggs - eggs } ?: 0

    /** 현재 단계에서 다음 단계까지 얼마나 왔는지 (0~1). 마지막 단계면 1. */
    fun progress(eggs: Int): Float {
        val now = current(eggs)
        val nxt = next(eggs) ?: return 1f
        return (eggs - now.minEggs).toFloat() / (nxt.minEggs - now.minEggs)
    }
}
