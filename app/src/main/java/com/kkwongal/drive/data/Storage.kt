package com.kkwongal.drive.data

import android.content.Context
import com.kkwongal.drive.core.OngoingTrip
import com.kkwongal.drive.core.Stamp
import com.kkwongal.drive.core.TripResult
import com.kkwongal.drive.rust.RustBridge
import java.io.File

data class SavedData(
    val trips: List<TripResult> = emptyList(),
    val stamps: List<Stamp> = emptyList(),
)

/**
 * 주행 기록과 스탬프를 기기 안의 JSON 파일(kkwongal.json, ongoing.json)에 읽고 쓴다.
 * 실제 직렬화·역직렬화·원자적 파일 교체는 Rust 코어(kkwongal-core::storage)에서 수행한다.
 */
class Storage(private val dir: File) {
    constructor(context: Context) : this(context.filesDir)

    fun load(): SavedData =
        RustBridge.parseSavedData(RustBridge.nativeStorageLoad(dir.absolutePath))

    fun save(data: SavedData) {
        RustBridge.nativeStorageSave(dir.absolutePath, RustBridge.toJson(data)!!)
    }

    fun loadOngoing(): OngoingTrip? =
        RustBridge.nativeStorageLoadOngoing(dir.absolutePath)?.let(RustBridge::parseOngoing)

    fun saveOngoing(trip: OngoingTrip) {
        RustBridge.nativeStorageSaveOngoing(dir.absolutePath, RustBridge.toJson(trip)!!)
    }

    fun clearOngoing() {
        RustBridge.nativeStorageClearOngoing(dir.absolutePath)
    }
}
