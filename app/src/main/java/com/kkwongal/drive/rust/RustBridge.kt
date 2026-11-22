package com.kkwongal.drive.rust

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.kkwongal.drive.PastReport
import com.kkwongal.drive.TripMark
import com.kkwongal.drive.core.Badge
import com.kkwongal.drive.core.Channel
import com.kkwongal.drive.core.ChannelState
import com.kkwongal.drive.core.EcoBreakdown
import com.kkwongal.drive.core.EcoConfig
import com.kkwongal.drive.core.EcoItem
import com.kkwongal.drive.core.EggLevels
import com.kkwongal.drive.core.Efficiency
import com.kkwongal.drive.core.EnergySnapshot
import com.kkwongal.drive.core.GeoPoint
import com.kkwongal.drive.core.HarshEvent
import com.kkwongal.drive.core.LeagueConfig
import com.kkwongal.drive.core.LeagueStanding
import com.kkwongal.drive.core.LiveEvent
import com.kkwongal.drive.core.OngoingTrip
import com.kkwongal.drive.core.RoadKm
import com.kkwongal.drive.core.RoadType
import com.kkwongal.drive.core.ScoreConfig
import com.kkwongal.drive.core.SpeedDriveDetector
import com.kkwongal.drive.core.Stamp
import com.kkwongal.drive.core.TripResult
import com.kkwongal.drive.core.TripSnapshot
import com.kkwongal.drive.data.SavedData
import com.kkwongal.drive.integration.ApiFailure
import com.kkwongal.drive.integration.LeagueReply
import com.kkwongal.drive.integration.LeagueSnapshot
import com.kkwongal.drive.integration.TripUploadDto
import com.kkwongal.drive.integration.UploadReply
import com.kkwongal.drive.vehicle.ArrivedPlace
import com.kkwongal.drive.vehicle.BrakeSample
import com.kkwongal.drive.vehicle.DriveState
import com.kkwongal.drive.vehicle.SpeedLimitSample
import com.kkwongal.drive.vehicle.SpeedSample
import com.kkwongal.drive.vehicle.SteeringSample

/**
 * JNI bridge into the compiled Rust core (`libkkwongal_jni.so` on Android, `kkwongal_jni.dll` on host JVM tests).
 * All domain scoring, eco habits, energy, coaching, stamp book, weekly league, storage,
 * simulation physics, league HTTP client, and app state transitions execute in Rust.
 */
object RustBridge {
    init {
        System.loadLibrary("kkwongal_jni")
    }

    val gson: Gson = GsonBuilder().create()

    // ---- Native JNI declarations ----

    @JvmStatic external fun nativeHealthCheck(): String

    // Stateless core
    @JvmStatic external fun nativeScoreSafety(
        distanceKm: Double,
        harshAccel: Int,
        harshBrake: Int,
        harshSteer: Int,
        configJson: String?,
    ): Int

    @JvmStatic external fun nativeScoreEco(pke: Double, configJson: String?): Int
    @JvmStatic external fun nativeScoreTotal(safety: Int, eco: Int, configJson: String?): Int
    @JvmStatic external fun nativeEcoBreakdownScore(breakdownJson: String, configJson: String?): Int
    @JvmStatic external fun nativeEcoBreakdownWeakest(breakdownJson: String): String?
    @JvmStatic external fun nativeRoadTypeOf(avgSpeedKmh: Double): String
    @JvmStatic external fun nativeRoadTypeOfLimit(limitKmh: Int): String
    @JvmStatic external fun nativeEnergyMeasure(startJson: String?, endJson: String?, fallbackDistanceKm: Double): String?
    @JvmStatic external fun nativeEnergyImprovement(effJson: String, prevJson: String): String?
    @JvmStatic external fun nativeEnergyEcoFromImprovement(improvementPercent: Double): Int
    @JvmStatic external fun nativeEnergyApply(resultJson: String, effJson: String?, historyJson: String, configJson: String?): String
    @JvmStatic external fun nativeEnergyText(energyJson: String): String
    @JvmStatic external fun nativeCoachingEcoDelta(resultJson: String, historyJson: String): String?
    @JvmStatic external fun nativeCoachingDeltaText(delta: Int): String
    @JvmStatic external fun nativeCoachingParkingSummary(resultJson: String, hasDelta: Boolean, delta: Int): String
    @JvmStatic external fun nativeCoachingWithSubject(word: String): String
    @JvmStatic external fun nativeEggLevelInfo(eggs: Int): String
    @JvmStatic external fun nativeDistanceMeters(latA: Double, lonA: Double, latB: Double, lonB: Double): Double
    @JvmStatic external fun nativeBadgesEvaluate(tripsJson: String, stampsJson: String): String
    @JvmStatic external fun nativeTripCanContinue(stoppedAtMs: Long, nowMs: Long, stoppedJson: String?, hereJson: String?): Boolean
    @JvmStatic external fun nativeTripCanRecover(savedAtMs: Long, nowMs: Long): Boolean
    @JvmStatic external fun nativeTripMarks(eventsJson: String, startWallMs: Long, endWallMs: Long): String
    @JvmStatic external fun nativeLeagueWeekStartMs(timeMs: Long, zoneId: String): Long
    @JvmStatic external fun nativeLeagueRewardFor(rank: Int, size: Int, configJson: String?): Int
    @JvmStatic external fun nativeLeagueStanding(tripsJson: String, nowMs: Long, zoneId: String, configJson: String?, simulated: Boolean): String
    @JvmStatic external fun nativeLeagueEarnedPoints(tripsJson: String, nowMs: Long, zoneId: String, configJson: String?, simulated: Boolean): Int
    @JvmStatic external fun nativeStampNameResolve(
        hasArrival: Boolean,
        arrivalName: String?,
        arrivalTimeMs: Long,
        hasArrivalPoint: Boolean,
        arrivalLat: Double,
        arrivalLon: Double,
        address: String?,
        hasAddrTime: Boolean,
        addrTime: Long,
        parkedLat: Double,
        parkedLon: Double,
        tripStartMs: Long,
        nowMs: Long,
    ): String?
    @JvmStatic external fun nativeKoreaOutlineJson(): String

    // League HTTP client
    @JvmStatic external fun nativeTripUploadFromTrip(tripJson: String): String
    @JvmStatic external fun nativeValidateLeagueBaseUrl(baseUrl: String, allowLocalHttp: Boolean): String?
    @JvmStatic external fun nativeLeagueClientSubmit(baseUrl: String, token: String?, allowLocalHttp: Boolean, tripUploadJson: String): String
    @JvmStatic external fun nativeLeagueClientWeekly(baseUrl: String, token: String?, allowLocalHttp: Boolean, weekStartMs: Long, simulated: Boolean): String
    @JvmStatic external fun nativeValidateWeeklyResponse(body: String, requestedWeek: Long, requestedSimulation: Boolean): String
    @JvmStatic external fun nativeValidateUploadReceipt(tripUploadJson: String, body: String): String

    // TripRecorder handle
    @JvmStatic external fun nativeRecorderNew(configJson: String?): Long
    @JvmStatic external fun nativeRecorderFree(handle: Long)
    @JvmStatic external fun nativeRecorderStart(handle: Long)
    @JvmStatic external fun nativeRecorderResume(handle: Long, snapshotJson: String)
    @JvmStatic external fun nativeRecorderOnSpeed(handle: Long, timeMs: Long, speedKmh: Double): String?
    @JvmStatic external fun nativeRecorderOnSteering(handle: Long, timeMs: Long, angleDeg: Double): String?
    @JvmStatic external fun nativeRecorderOnBrakePedal(handle: Long, timeMs: Long, pressed: Boolean)
    @JvmStatic external fun nativeRecorderOnSpeedLimit(handle: Long, timeMs: Long, limitKmh: Int)
    @JvmStatic external fun nativeRecorderStateJson(handle: Long): String
    @JvmStatic external fun nativeRecorderFinish(handle: Long, startWallMs: Long, endWallMs: Long): String

    // StampBook handle
    @JvmStatic external fun nativeStampBookNew(initialJson: String?, radiusM: Double): Long
    @JvmStatic external fun nativeStampBookFree(handle: Long)
    @JvmStatic external fun nativeStampBookList(handle: Long): String
    @JvmStatic external fun nativeStampBookFindNearby(handle: Long, lat: Double, lon: Double): String?
    @JvmStatic external fun nativeStampBookTryAdd(handle: Long, lat: Double, lon: Double, timeMs: Long, score: Int, name: String?, perfect: Boolean): String?
    @JvmStatic external fun nativeStampBookRename(handle: Long, id: Int, name: String): String?
    @JvmStatic external fun nativeStampBookUpdate(handle: Long, id: Int, name: String, memo: String, tagsJson: String): String?
    @JvmStatic external fun nativeStampBookRemove(handle: Long, id: Int): Boolean

    // DiagnosticsBoard & SpeedDriveDetector handles
    @JvmStatic external fun nativeDiagNew(minIntervalMs: Long): Long
    @JvmStatic external fun nativeDiagFree(handle: Long)
    @JvmStatic external fun nativeDiagCurrent(handle: Long): String
    @JvmStatic external fun nativeDiagSet(handle: Long, channel: String, status: String, detail: String, nowMs: Long): String?
    @JvmStatic external fun nativeSpeedDetectorNew(startKmh: Double, stopKmh: Double, parkAfterMs: Long): Long
    @JvmStatic external fun nativeSpeedDetectorFree(handle: Long)
    @JvmStatic external fun nativeSpeedDetectorOnSpeed(handle: Long, timeMs: Long, kmh: Double): String
    @JvmStatic external fun nativeSpeedDetectorOnTick(handle: Long, timeMs: Long): String

    // Storage
    @JvmStatic external fun nativeStorageLoad(dirPath: String): String
    @JvmStatic external fun nativeStorageSave(dirPath: String, dataJson: String): Boolean
    @JvmStatic external fun nativeStorageLoadOngoing(dirPath: String): String?
    @JvmStatic external fun nativeStorageSaveOngoing(dirPath: String, ongoingJson: String): Boolean
    @JvmStatic external fun nativeStorageClearOngoing(dirPath: String)

    // FakeVehicleEngine handle
    @JvmStatic external fun nativeFakeNew(realMs: Long, wallMs: Long): Long
    @JvmStatic external fun nativeFakeFree(handle: Long)
    @JvmStatic external fun nativeFakeTick(handle: Long, realMs: Long, wallMs: Long): String
    @JvmStatic external fun nativeFakeReadEnergy(handle: Long, wallMs: Long): String
    @JvmStatic external fun nativeFakeSetSpeed(handle: Long, kmh: Double, realMs: Long, wallMs: Long): String
    @JvmStatic external fun nativeFakeSetParked(handle: Long, parked: Boolean, wallMs: Long): String
    @JvmStatic external fun nativeFakeSetLocation(handle: Long, hasPoint: Boolean, lat: Double, lon: Double, name: String?, address: String?, wallMs: Long): String
    @JvmStatic external fun nativeFakeJerkSteering(handle: Long, realMs: Long): String
    @JvmStatic external fun nativeFakeSetDemoActive(handle: Long, active: Boolean, realMs: Long, wallMs: Long): String
    @JvmStatic external fun nativeFakeCurrentState(handle: Long): String
    @JvmStatic external fun nativeFakeDemoScriptJson(): String

    // KkwongalEngine handle
    @JvmStatic external fun nativeEngineNew(storageDir: String, remoteLeague: Boolean, voiceGuide: Boolean, nowMs: Long): Long
    @JvmStatic external fun nativeEngineFree(handle: Long)
    @JvmStatic external fun nativeEngineSnapshot(handle: Long): String
    @JvmStatic external fun nativeEngineSetVoiceGuide(handle: Long, on: Boolean): String
    @JvmStatic external fun nativeEngineSetSimulation(handle: Long, enabled: Boolean, fallbackNotice: String?, nowMs: Long): String
    @JvmStatic external fun nativeEngineClearForDemo(handle: Long): String
    @JvmStatic external fun nativeEngineRefreshLocalLeague(handle: Long, nowMs: Long): String
    @JvmStatic external fun nativeEngineOnSpeed(handle: Long, monoMs: Long, speedKmh: Double, wallMs: Long): String
    @JvmStatic external fun nativeEngineOnSteering(handle: Long, monoMs: Long, angleDeg: Double, wallMs: Long): String
    @JvmStatic external fun nativeEngineOnBrakePedal(handle: Long, monoMs: Long, pressed: Boolean)
    @JvmStatic external fun nativeEngineOnSpeedLimit(handle: Long, monoMs: Long, limitKmh: Int)
    @JvmStatic external fun nativeEngineOnDriveState(handle: Long, state: String, wallMs: Long, hasLoc: Boolean, lat: Double, lon: Double): String
    @JvmStatic external fun nativeEngineSetStartEnergy(handle: Long, energyJson: String?, wallMs: Long)
    @JvmStatic external fun nativeEngineCompleteFinish(
        handle: Long,
        endEnergyJson: String?,
        hasArrival: Boolean,
        arrivalName: String?,
        arrivalTimeMs: Long,
        hasArrivalPoint: Boolean,
        arrivalLat: Double,
        arrivalLon: Double,
        address: String?,
        hasAddrTime: Boolean,
        addrTime: Long,
        nowMs: Long,
    ): String
    @JvmStatic external fun nativeEngineSaveOngoing(handle: Long, wallMs: Long)
    @JvmStatic external fun nativeEngineSaveStamp(handle: Long, id: Int, name: String, memo: String, tagsJson: String): String
    @JvmStatic external fun nativeEngineOpenTrip(handle: Long, startMs: Long, endMs: Long): String
    @JvmStatic external fun nativeEngineDeleteTrip(handle: Long, startMs: Long, endMs: Long, nowMs: Long): String
    @JvmStatic external fun nativeEngineCloseTrip(handle: Long): String
    @JvmStatic external fun nativeEngineDismissResult(handle: Long): String
    @JvmStatic external fun nativeEngineSetNotice(handle: Long, notice: String?): String
    @JvmStatic external fun nativeEngineNavigateMessage(handle: Long, stampId: Int, sent: Boolean): String
    @JvmStatic external fun nativeEngineValidateToken(token: String): String?
    @JvmStatic external fun nativeEngineLatestTripUpload(handle: Long, nowMs: Long): String

    // ---- Typed deserialization helpers ----

    data class RecorderStateWire(
        val active: Boolean,
        val distanceKm: Double,
        val harshAccel: Int,
        val harshBrake: Int,
        val harshSteer: Int,
        val safetyScore: Int,
        val ecoScore: Int,
        val totalScore: Int,
        val ecoBreakdown: EcoBreakdown,
        val roadKm: RoadKm,
        val snapshot: TripSnapshot,
    )

    data class EggLevelInfoWire(
        val current: EggLevels.Level,
        val next: EggLevels.Level?,
        val remaining: Int,
        val progress: Float,
    )

    data class KoreaOutlineWire(
        val minLon: Double,
        val maxLon: Double,
        val minLat: Double,
        val maxLat: Double,
        val dokdo: FloatArray,
        val polygons: List<FloatArray>,
    )

    data class FakeTickWire(
        val speedSample: SpeedSample,
        val steeringSample: SteeringSample,
        val speedLimitSample: SpeedLimitSample,
        val brakeEvents: List<BrakeSample>,
        val driveState: DriveState,
        val speedKmh: Double,
        val uxRestricted: Boolean,
        val location: GeoPoint?,
        val address: String?,
        val addressUpdatedAtMs: Long?,
        val arrivedPlace: ArrivedPlace?,
        val diagnostics: Map<Channel, ChannelState>,
    )

    data class FakeControlWire(
        val brakeEvents: List<BrakeSample>,
        val driveState: DriveState,
        val speedKmh: Double,
        val uxRestricted: Boolean,
        val location: GeoPoint?,
        val address: String?,
        val addressUpdatedAtMs: Long?,
        val arrivedPlace: ArrivedPlace?,
        val diagnostics: Map<Channel, ChannelState>,
    )

    data class FakeEnergyWire(
        val energy: EnergySnapshot,
        val diagnostics: Map<Channel, ChannelState>,
    )

    data class DemoActionWire(
        val kind: String,
        val label: String? = null,
        val point: GeoPoint? = null,
        val name: String? = null,
        val address: String? = null,
        val parked: Boolean? = null,
        val kmh: Double? = null,
        val target: Double? = null,
        val rate: Double? = null,
        val virtualSeconds: Int? = null,
    )

    data class EngineSnapshotWire(
        val simulation: Boolean,
        val tripActive: Boolean,
        val liveSafety: Int,
        val liveTotal: Int,
        val liveHarshAccel: Int,
        val liveHarshBrake: Int,
        val liveHarshSteer: Int,
        val lastHarshEvent: HarshEvent?,
        val liveEvents: List<LiveEvent>,
        val harshEventSeq: Int,
        val lastResult: TripResult?,
        val newStamp: Stamp?,
        val notice: String?,
        val trips: List<TripResult>,
        val stamps: List<Stamp>,
        val badges: Set<Badge>,
        val league: LeagueStanding?,
        val earnedPoints: Int,
        val remoteLeague: Boolean,
        val lastTripMarks: List<TripMark>,
        val lastTripEvents: List<LiveEvent>,
        val lastEcoDelta: Int?,
        val pastReport: PastReport?,
        val voiceGuide: Boolean,
    )

    data class EngineStepWire(
        val snapshot: EngineSnapshotWire,
        val speak: String?,
        val needsStartEnergy: Boolean,
        val needsFinishEnergy: Boolean,
    )

    data class LeagueReplyWire(
        val kind: String,
        val snapshot: LeagueSnapshot? = null,
        val reason: ApiFailure? = null,
        val httpStatus: Int? = null,
    ) {
        fun toDomain(): LeagueReply = if (kind == "success" && snapshot != null) {
            LeagueReply.Success(snapshot)
        } else {
            LeagueReply.Failed(reason ?: ApiFailure.INVALID_RESPONSE, httpStatus)
        }
    }

    data class UploadReplyWire(
        val kind: String,
        val duplicate: Boolean? = null,
        val reason: ApiFailure? = null,
        val httpStatus: Int? = null,
    ) {
        fun toDomain(): UploadReply = if (kind == "success" && duplicate != null) {
            UploadReply.Success(duplicate)
        } else {
            UploadReply.Failed(reason ?: ApiFailure.INVALID_RESPONSE, httpStatus)
        }
    }

    data class LatestUploadWire(
        val status: String,
        val trip: TripUploadDto? = null,
        val message: String? = null,
    )

    fun parseDiagnostics(json: String): Map<Channel, ChannelState> {
        val type = object : TypeToken<Map<Channel, ChannelState>>() {}.type
        return gson.fromJson(json, type)
    }

    fun parseStamps(json: String): List<Stamp> {
        val type = object : TypeToken<List<Stamp>>() {}.type
        return gson.fromJson(json, type)
    }

    fun parseBadges(json: String): Set<Badge> {
        val type = object : TypeToken<Set<Badge>>() {}.type
        return gson.fromJson(json, type)
    }

    fun parseMarks(json: String): List<TripMark> {
        val type = object : TypeToken<List<TripMark>>() {}.type
        return gson.fromJson(json, type)
    }

    fun parseDemoScript(json: String): List<DemoActionWire> {
        val type = object : TypeToken<List<DemoActionWire>>() {}.type
        return gson.fromJson(json, type)
    }

    fun parseSavedData(json: String): SavedData = gson.fromJson(json, SavedData::class.java)
    fun parseOngoing(json: String): OngoingTrip = gson.fromJson(json, OngoingTrip::class.java)
    fun parseRecorderState(json: String): RecorderStateWire = gson.fromJson(json, RecorderStateWire::class.java)
    fun parseTripResult(json: String): TripResult = gson.fromJson(json, TripResult::class.java)
    fun parseHarshEvent(json: String): HarshEvent = gson.fromJson(json, HarshEvent::class.java)
    fun parseEfficiency(json: String): Efficiency = gson.fromJson(json, Efficiency::class.java)
    fun parseEggLevelInfo(json: String): EggLevelInfoWire = gson.fromJson(json, EggLevelInfoWire::class.java)
    fun parseLeagueStanding(json: String): LeagueStanding = gson.fromJson(json, LeagueStanding::class.java)
    fun parseKoreaOutline(json: String): KoreaOutlineWire = gson.fromJson(json, KoreaOutlineWire::class.java)
    fun parseTripUploadDto(json: String): TripUploadDto = gson.fromJson(json, TripUploadDto::class.java)
    fun parseLeagueReply(json: String): LeagueReply = gson.fromJson(json, LeagueReplyWire::class.java).toDomain()
    fun parseUploadReply(json: String): UploadReply = gson.fromJson(json, UploadReplyWire::class.java).toDomain()
    fun parseFakeTick(json: String): FakeTickWire = gson.fromJson(json, FakeTickWire::class.java)
    fun parseFakeControl(json: String): FakeControlWire = gson.fromJson(json, FakeControlWire::class.java)
    fun parseFakeEnergy(json: String): FakeEnergyWire = gson.fromJson(json, FakeEnergyWire::class.java)
    fun parseEngineSnapshot(json: String): EngineSnapshotWire = gson.fromJson(json, EngineSnapshotWire::class.java)
    fun parseEngineStep(json: String): EngineStepWire = gson.fromJson(json, EngineStepWire::class.java)
    fun parseLatestUpload(json: String): LatestUploadWire = gson.fromJson(json, LatestUploadWire::class.java)

    fun toJson(value: Any?): String? = value?.let { gson.toJson(it) }
    fun parseRoadType(name: String): RoadType = RoadType.valueOf(name)
    fun parseEcoItem(name: String?): EcoItem? = name?.let { EcoItem.valueOf(it) }
    fun parseDriveMode(name: String): SpeedDriveDetector.DriveMode = SpeedDriveDetector.DriveMode.valueOf(name)
}
