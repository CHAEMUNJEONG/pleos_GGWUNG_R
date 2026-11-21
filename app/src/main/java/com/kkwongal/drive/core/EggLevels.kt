package com.kkwongal.drive.core

import com.kkwongal.drive.rust.RustBridge

/**
 * 꿩알 성장 단계. 계산은 Rust 코어(kkwongal-core::egg_levels)에서 수행한다.
 */
object EggLevels {
    data class Level(val name: String, val minEggs: Int)

    val LEVELS = listOf(
        Level("알", 0),
        Level("아기 꿩", 5),
        Level("어른 꿩", 15),
        Level("황금 꿩", 30),
    )

    fun current(eggs: Int): Level =
        RustBridge.parseEggLevelInfo(RustBridge.nativeEggLevelInfo(eggs)).current

    fun next(eggs: Int): Level? =
        RustBridge.parseEggLevelInfo(RustBridge.nativeEggLevelInfo(eggs)).next

    fun remaining(eggs: Int): Int =
        RustBridge.parseEggLevelInfo(RustBridge.nativeEggLevelInfo(eggs)).remaining

    fun progress(eggs: Int): Float =
        RustBridge.parseEggLevelInfo(RustBridge.nativeEggLevelInfo(eggs)).progress
}
