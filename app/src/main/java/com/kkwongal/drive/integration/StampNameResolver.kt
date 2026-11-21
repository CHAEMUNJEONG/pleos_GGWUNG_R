package com.kkwongal.drive.integration

import com.kkwongal.drive.core.GeoPoint
import com.kkwongal.drive.rust.RustBridge
import com.kkwongal.drive.vehicle.ArrivedPlace

/** Prototype policy: a recent nearby destination, then a fresh address, then StampBook's default (executed in Rust). */
object StampNameResolver {
    const val MAX_AGE_MS = 10 * 60 * 1000L
    const val MAX_DISTANCE_METERS = 500.0

    fun resolve(
        arrival: ArrivedPlace?,
        address: String?,
        addressUpdatedAtMs: Long?,
        parkedAt: GeoPoint,
        tripStartMs: Long,
        nowMs: Long,
    ): String? {
        val arrivedPoint = arrival?.point
        return RustBridge.nativeStampNameResolve(
            hasArrival = arrival != null,
            arrivalName = arrival?.name,
            arrivalTimeMs = arrival?.wallTimeMs ?: 0L,
            hasArrivalPoint = arrivedPoint != null,
            arrivalLat = arrivedPoint?.latitude ?: 0.0,
            arrivalLon = arrivedPoint?.longitude ?: 0.0,
            address = address,
            hasAddrTime = addressUpdatedAtMs != null,
            addrTime = addressUpdatedAtMs ?: 0L,
            parkedLat = parkedAt.latitude,
            parkedLon = parkedAt.longitude,
            tripStartMs = tripStartMs,
            nowMs = nowMs,
        )
    }
}
