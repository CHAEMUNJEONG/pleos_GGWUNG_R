package com.kkwongal.drive.core

import com.kkwongal.drive.rust.RustBridge

data class EcoConfig(
    val cruiseMinKmh: Double = 60.0,
    val minCruiseSec: Double = 60.0,
    val steadyBest: Double = 0.5,
    val steadyWorst: Double = 2.0,
    val overSpeedMarginKmh: Double = 10.0,
    val fallbackOverSpeedKmh: Double = 110.0,
    val overSpeedWorstShare: Double = 0.3,
    val minSpeedItemKm: Double = 1.0,
    val coastBestShare: Double = 0.6,
    val coastWorstShare: Double = 0.1,
    val minDecelEnergy: Double = 500.0,
    val idleGraceSec: Double = 120.0,
    val idlePenaltyPerMin: Double = 10.0,
    val stoppedKmh: Double = 1.0,
    val weightAccel: Double = 0.40,
    val weightSteady: Double = 0.20,
    val weightSpeed: Double = 0.15,
    val weightCoast: Double = 0.15,
    val weightIdle: Double = 0.10,
)

enum class EcoItem(val label: String, val tip: String) {
    ACCEL("부드러운 가속", "출발할 때 3초만 더 천천히 밟아 보세요"),
    STEADY("정속 주행", "고속 구간에서는 속도를 일정하게 유지해 보세요"),
    SPEED("적정 속도", "제한속도 안에서 달리면 공기 저항이 줄어요"),
    COAST("미리 감속", "앞이 막히면 일찍 발을 떼고 천천히 줄여 보세요"),
    IDLE("공회전", "오래 설 때는 시동을 꺼 보세요"),
}

data class EcoBreakdown(
    val accel: Int,
    val steady: Int? = null,
    val speed: Int? = null,
    val coast: Int? = null,
    val idle: Int? = null,
    val overSpeedPercent: Int? = null,
    val coastPercent: Int? = null,
    val idleMin: Double = 0.0,
) {
    fun items(): List<Pair<EcoItem, Int>> = listOfNotNull(
        EcoItem.ACCEL to accel,
        steady?.let { EcoItem.STEADY to it },
        speed?.let { EcoItem.SPEED to it },
        coast?.let { EcoItem.COAST to it },
        idle?.let { EcoItem.IDLE to it },
    )

    fun weakest(): EcoItem? =
        RustBridge.parseEcoItem(RustBridge.nativeEcoBreakdownWeakest(RustBridge.toJson(this)!!))

    fun score(config: EcoConfig = EcoConfig()): Int =
        RustBridge.nativeEcoBreakdownScore(RustBridge.toJson(this)!!, RustBridge.toJson(config))
}

data class RoadKm(val city: Double = 0.0, val road: Double = 0.0, val highway: Double = 0.0) {
    val total: Double get() = city + road + highway

    fun add(type: RoadType, km: Double): RoadKm = when (type) {
        RoadType.CITY -> copy(city = city + km)
        RoadType.MIXED -> copy(road = road + km)
        RoadType.HIGHWAY -> copy(highway = highway + km)
    }

    fun dominant(): RoadType? = when {
        total <= 0.0 -> null
        highway >= road && highway >= city -> RoadType.HIGHWAY
        road >= city -> RoadType.MIXED
        else -> RoadType.CITY
    }
}

data class EcoState(
    val cruiseSec: Double = 0.0,
    val cruiseChange: Double = 0.0,
    val overSpeedKm: Double = 0.0,
    val coastEnergy: Double = 0.0,
    val brakeEnergy: Double = 0.0,
    val idleSec: Double = 0.0,
    val roadKm: RoadKm = RoadKm(),
)
