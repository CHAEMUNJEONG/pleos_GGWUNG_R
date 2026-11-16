package com.kkwongal.drive.data

import android.content.Context
import android.util.Log
import com.kkwongal.drive.core.EnergySnapshot
import com.kkwongal.drive.core.HarshType
import com.kkwongal.drive.core.LiveEvent
import com.kkwongal.drive.core.OngoingTrip
import com.kkwongal.drive.core.Stamp
import com.kkwongal.drive.core.TripSnapshot
import com.kkwongal.drive.core.TripResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedData(
    val trips: List<TripResult> = emptyList(),
    val stamps: List<Stamp> = emptyList(),
)

/**
 * 주행 기록과 스탬프를 기기 안의 파일 하나(JSON)에 저장한다.
 * 방문 장소는 기기 밖으로 나가지 않는다.
 */
class Storage(context: Context) {

    private val file = File(context.filesDir, "kkwongal.json")

    /** 진행 중인 주행. 주행이 끝나면 지운다. */
    private val ongoingFile = File(context.filesDir, "ongoing.json")

    fun load(): SavedData {
        if (!file.exists()) return SavedData()
        return try {
            val root = JSONObject(file.readText())
            SavedData(
                trips = root.optJSONArray("trips").toList { o ->
                    TripResult(
                        startTimeMs = o.getLong("start"),
                        endTimeMs = o.getLong("end"),
                        distanceKm = o.getDouble("distanceKm"),
                        harshAccelCount = o.getInt("accel"),
                        harshBrakeCount = o.getInt("brake"),
                        maxSpeedKmh = o.getDouble("maxSpeed"),
                        score = o.getInt("score"),
                        // 예전 버전으로 저장한 기록에는 없는 값이라 종합 점수로 대신한다
                        safetyScore = o.optInt("safety", o.getInt("score")),
                        ecoScore = o.optInt("eco", o.getInt("score")),
                        harshSteerCount = o.optInt("steer", 0),
                        efficiency = if (o.has("eff")) o.getDouble("eff") else null,
                        efficiencyKind = o.optString("effKind", "").ifBlank { null },
                        efficiencyImprovement = if (o.has("effImp")) o.getDouble("effImp") else null,
                    )
                },
                stamps = root.optJSONArray("stamps").toList { o ->
                    Stamp(
                        id = o.getInt("id"),
                        name = o.getString("name"),
                        latitude = o.getDouble("lat"),
                        longitude = o.getDouble("lon"),
                        timeMs = o.getLong("time"),
                        score = o.getInt("score"),
                        memo = o.optString("memo", ""),
                        perfect = o.optBoolean("perfect", false),
                        tags = o.optJSONArray("tags").let { array ->
                            if (array == null) emptyList() else (0 until array.length()).map { array.getString(it) }
                        },
                    )
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "저장 파일을 읽지 못해 빈 상태로 시작합니다", e)
            SavedData()
        }
    }

    fun save(data: SavedData) {
        try {
            val root = JSONObject()
            root.put("trips", JSONArray().apply {
                data.trips.forEach { t ->
                    put(
                        JSONObject()
                            .put("start", t.startTimeMs)
                            .put("end", t.endTimeMs)
                            .put("distanceKm", t.distanceKm)
                            .put("accel", t.harshAccelCount)
                            .put("brake", t.harshBrakeCount)
                            .put("maxSpeed", t.maxSpeedKmh)
                            .put("score", t.score)
                            .put("safety", t.safetyScore)
                            .put("eco", t.ecoScore)
                            .put("steer", t.harshSteerCount)
                            .apply {
                                t.efficiency?.let { put("eff", it) }
                                t.efficiencyKind?.let { put("effKind", it) }
                                t.efficiencyImprovement?.let { put("effImp", it) }
                            },
                    )
                }
            })
            root.put("stamps", JSONArray().apply {
                data.stamps.forEach { s ->
                    put(
                        JSONObject()
                            .put("id", s.id)
                            .put("name", s.name)
                            .put("lat", s.latitude)
                            .put("lon", s.longitude)
                            .put("time", s.timeMs)
                            .put("score", s.score)
                            .put("memo", s.memo)
                            .put("perfect", s.perfect)
                            .put("tags", JSONArray(s.tags)),
                    )
                }
            })
            writeSafely(file, root.toString())
        } catch (e: Exception) {
            Log.w(TAG, "저장 실패", e)
        }
    }

    fun loadOngoing(): OngoingTrip? {
        if (!ongoingFile.exists()) return null
        return try {
            val o = JSONObject(ongoingFile.readText())
            val snap = o.getJSONObject("snapshot")
            OngoingTrip(
                startWallMs = o.getLong("start"),
                savedAtMs = o.getLong("savedAt"),
                snapshot = TripSnapshot(
                    distanceKm = snap.getDouble("distanceKm"),
                    maxSpeedKmh = snap.getDouble("maxSpeed"),
                    harshAccel = snap.getInt("accel"),
                    harshBrake = snap.getInt("brake"),
                    harshSteer = snap.getInt("steer"),
                    positiveKineticSum = snap.getDouble("pke"),
                ),
                startEnergy = o.optJSONObject("energy")?.let { e ->
                    EnergySnapshot(
                        evBatteryWh = e.optDoubleOrNull("evWh"),
                        fuelPercent = e.optDoubleOrNull("fuelPct"),
                        fuelCapacityL = e.optDoubleOrNull("fuelCapL"),
                        odometerKm = e.optDoubleOrNull("odoKm"),
                    )
                },
                events = o.optJSONArray("events").toList { e ->
                    LiveEvent(
                        type = HarshType.valueOf(e.getString("type")),
                        penalty = e.getInt("penalty"),
                        scoreAfter = e.getInt("after"),
                        wallTimeMs = e.getLong("time"),
                        km = e.getDouble("km"),
                    )
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "진행 중 주행을 읽지 못해 버립니다", e)
            clearOngoing()
            null
        }
    }

    fun saveOngoing(trip: OngoingTrip) {
        try {
            val s = trip.snapshot
            val root = JSONObject()
                .put("start", trip.startWallMs)
                .put("savedAt", trip.savedAtMs)
                .put(
                    "snapshot",
                    JSONObject()
                        .put("distanceKm", s.distanceKm)
                        .put("maxSpeed", s.maxSpeedKmh)
                        .put("accel", s.harshAccel)
                        .put("brake", s.harshBrake)
                        .put("steer", s.harshSteer)
                        .put("pke", s.positiveKineticSum),
                )
                .put("events", JSONArray().apply {
                    trip.events.forEach { e ->
                        put(
                            JSONObject()
                                .put("type", e.type.name)
                                .put("penalty", e.penalty)
                                .put("after", e.scoreAfter)
                                .put("time", e.wallTimeMs)
                                .put("km", e.km),
                        )
                    }
                })
            trip.startEnergy?.let { e ->
                root.put("energy", JSONObject().apply {
                    e.evBatteryWh?.let { put("evWh", it) }
                    e.fuelPercent?.let { put("fuelPct", it) }
                    e.fuelCapacityL?.let { put("fuelCapL", it) }
                    e.odometerKm?.let { put("odoKm", it) }
                })
            }
            writeSafely(ongoingFile, root.toString())
        } catch (e: Exception) {
            Log.w(TAG, "진행 중 주행 저장 실패", e)
        }
    }

    fun clearOngoing() {
        ongoingFile.delete()
    }

    /** 쓰는 도중 꺼져도 기존 파일이 깨지지 않도록 임시 파일에 쓴 뒤 바꾼다. */
    private fun writeSafely(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key)) getDouble(key) else null

    private fun <T> JSONArray?.toList(map: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).map { map(getJSONObject(it)) }
    }

    private companion object {
        const val TAG = "Storage"
    }
}
