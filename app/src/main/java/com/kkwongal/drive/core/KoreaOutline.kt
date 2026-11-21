package com.kkwongal.drive.core

import com.kkwongal.drive.rust.RustBridge

/**
 * 대한민국 윤곽선. 좌표 데이터는 Rust 코어(kkwongal-core::korea_outline)에서 로드한다.
 */
object KoreaOutline {
    const val MIN_LON = 124.5
    const val MAX_LON = 132.3
    const val MIN_LAT = 32.9
    const val MAX_LAT = 38.9

    private val wire by lazy {
        RustBridge.parseKoreaOutline(RustBridge.nativeKoreaOutlineJson())
    }

    val DOKDO: FloatArray get() = wire.dokdo
    val POLYGONS: List<FloatArray> get() = wire.polygons
}
