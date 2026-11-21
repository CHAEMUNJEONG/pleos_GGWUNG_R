package com.kkwongal.drive.vehicle

import com.kkwongal.drive.core.EnergySnapshot
import java.util.Locale

/** 연결 점검 화면에 보여 줄 에너지 값. */
internal fun energyText(e: EnergySnapshot): String = buildList {
    e.evBatteryWh?.let { add(String.format(Locale.US, "배터리 %.1f kWh", it / 1000.0)) }
    e.fuelPercent?.let { add(String.format(Locale.US, "연료 %.0f%%", it)) }
    e.odometerKm?.let { add(String.format(Locale.US, "누적 %,.0f km", it)) }
}.joinToString(" · ").ifBlank { "값 없음" }
