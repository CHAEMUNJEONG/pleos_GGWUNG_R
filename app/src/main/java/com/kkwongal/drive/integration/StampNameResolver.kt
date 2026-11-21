package com.kkwongal.drive.integration

import com.kkwongal.drive.core.GeoPoint
import com.kkwongal.drive.core.distanceMeters
import com.kkwongal.drive.vehicle.ArrivedPlace

/** Prototype policy: a recent nearby destination, then a fresh address, then StampBook's default. */
object StampNameResolver {
    const val MAX_AGE_MS = 10 * 60 * 1000L
    const val MAX_DISTANCE_METERS = 500.0

    fun resolve(arrival: ArrivedPlace?, address: String?, addressUpdatedAtMs: Long?,
        parkedAt: GeoPoint, tripStartMs: Long, nowMs: Long): String? {
        if (!valid(parkedAt)) return null
        fun recent(time: Long) = time >= tripStartMs && time <= nowMs && nowMs - time <= MAX_AGE_MS
        val arrivedAt = arrival?.point
        if (arrival != null && recent(arrival.wallTimeMs) && arrivedAt != null && valid(arrivedAt) &&
            distanceMeters(arrivedAt, parkedAt) <= MAX_DISTANCE_METERS) {
            clean(arrival.name)?.let { return it }
        }
        return if (addressUpdatedAtMs != null && recent(addressUpdatedAtMs)) clean(address) else null
    }

    private fun clean(value: String?) = value?.trim()?.take(100)?.takeIf { it.isNotEmpty() }
    private fun valid(point: GeoPoint) = point.latitude.isFinite() && point.longitude.isFinite() &&
        point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0
}
