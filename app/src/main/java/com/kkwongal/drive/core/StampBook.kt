package com.kkwongal.drive.core

import com.kkwongal.drive.rust.RustBridge
import java.io.Closeable

/** 두 지점 사이의 거리(미터). 하버사인 공식은 Rust 코어에서 계산한다. */
fun distanceMeters(a: GeoPoint, b: GeoPoint): Double =
    RustBridge.nativeDistanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)

/**
 * 스탬프 모음. 모든 반경 판정·추가·수정·삭제는 Rust 네이티브 핸들(kkwongal-core::stamp_book::StampBook)에서 수행한다.
 */
class StampBook(
    initial: List<Stamp> = emptyList(),
    newPlaceRadiusM: Double = 500.0,
) : Closeable {
    private var handle: Long = RustBridge.nativeStampBookNew(RustBridge.toJson(initial), newPlaceRadiusM)

    val stamps: List<Stamp>
        get() = RustBridge.parseStamps(RustBridge.nativeStampBookList(handle))

    fun findNearby(point: GeoPoint): Stamp? =
        RustBridge.nativeStampBookFindNearby(handle, point.latitude, point.longitude)
            ?.let { RustBridge.gson.fromJson(it, Stamp::class.java) }

    fun tryAdd(point: GeoPoint, timeMs: Long, score: Int, name: String? = null, perfect: Boolean = false): Stamp? =
        RustBridge.nativeStampBookTryAdd(handle, point.latitude, point.longitude, timeMs, score, name, perfect)
            ?.let { RustBridge.gson.fromJson(it, Stamp::class.java) }

    fun rename(id: Int, name: String): Stamp? =
        RustBridge.nativeStampBookRename(handle, id, name)
            ?.let { RustBridge.gson.fromJson(it, Stamp::class.java) }

    fun remove(id: Int): Boolean =
        RustBridge.nativeStampBookRemove(handle, id)

    fun update(id: Int, name: String, memo: String, tags: List<String>): Stamp? =
        RustBridge.nativeStampBookUpdate(handle, id, name, memo, RustBridge.toJson(tags)!!)
            ?.let { RustBridge.gson.fromJson(it, Stamp::class.java) }

    override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            RustBridge.nativeStampBookFree(h)
        }
    }

    @Suppress("removal")
    protected fun finalize() {
        close()
    }
}

object BadgeRules {
    fun evaluate(trips: List<TripResult>, stamps: List<Stamp>): Set<Badge> =
        RustBridge.parseBadges(
            RustBridge.nativeBadgesEvaluate(RustBridge.toJson(trips)!!, RustBridge.toJson(stamps)!!),
        )
}
