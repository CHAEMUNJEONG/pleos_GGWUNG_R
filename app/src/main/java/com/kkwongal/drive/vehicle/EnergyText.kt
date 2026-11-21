package com.kkwongal.drive.vehicle

import com.kkwongal.drive.core.EnergySnapshot
import com.kkwongal.drive.rust.RustBridge

/** 연결 점검 화면에 보여 줄 에너지 값. 포맷팅은 Rust 코어에서 수행한다. */
internal fun energyText(e: EnergySnapshot): String =
    RustBridge.nativeEnergyText(RustBridge.toJson(e)!!)
