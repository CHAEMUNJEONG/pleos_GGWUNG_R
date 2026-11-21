package com.kkwongal.drive.data

import android.content.Context
import android.util.Log
import com.kkwongal.drive.core.Stamp
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
                            .put("eco", t.ecoScore),
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
                            .put("tags", JSONArray(s.tags)),
                    )
                }
            })
            // 쓰는 도중 꺼져도 기존 파일이 깨지지 않도록 임시 파일에 쓴 뒤 바꾼다
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "저장 실패", e)
        }
    }

    private fun <T> JSONArray?.toList(map: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).map { map(getJSONObject(it)) }
    }

    private companion object {
        const val TAG = "Storage"
    }
}
