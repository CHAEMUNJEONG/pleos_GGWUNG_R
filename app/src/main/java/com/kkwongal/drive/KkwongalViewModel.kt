package com.kkwongal.drive

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kkwongal.drive.core.GeoPoint
import com.kkwongal.drive.core.League
import com.kkwongal.drive.core.TripResult
import com.kkwongal.drive.integration.LeagueClient
import com.kkwongal.drive.integration.LeagueReply
import com.kkwongal.drive.integration.UploadReply
import com.kkwongal.drive.rust.RustBridge
import com.kkwongal.drive.ui.ThemeMode
import com.kkwongal.drive.vehicle.DriveState
import com.kkwongal.drive.vehicle.FakeVehicleSource
import com.kkwongal.drive.vehicle.RealVehicleSource
import com.kkwongal.drive.vehicle.VehicleSource
import com.kkwongal.drive.voice.VoiceGuide
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * 앱 상태 어댑터.
 * 출발·주차 판정, 주행 기록 시작·이어 가기·복구·종료, 알 찍기, 코칭 문장, 리그 계산, JSON 저장은
 * 모두 Rust 네이티브 엔진(kkwongal-core::engine::KkwongalEngine)에서 실행하고,
 * 이 클래스는 Android 플랫폼(Pleos VehicleSource, VoiceGuide, SharedPreferences, Compose StateFlow)만 연결한다.
 */
class KkwongalViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val remoteLeague = BuildConfig.LEAGUE_API_BASE_URL.isNotBlank()
    private var engineHandle: Long
    private var energyJob: Job? = null
    private var finishJob: Job? = null
    private var source: VehicleSource? = null
    private var collectJob: Job? = null
    private var demoJob: Job? = null
    private var leagueClient: LeagueClient? = null
    private var leagueJob: Job? = null

    private val _ui: MutableStateFlow<UiState>
    val ui: StateFlow<UiState>

    init {
        Log.i(TAG, "Rust core loaded: ${RustBridge.nativeHealthCheck()}")
        val savedVoice = prefs.getBoolean("voiceGuide", true)
        val savedTheme = runCatching { ThemeMode.valueOf(prefs.getString("theme", null) ?: "AUTO") }
            .getOrDefault(ThemeMode.AUTO)
        engineHandle = RustBridge.nativeEngineNew(
            app.filesDir.absolutePath,
            remoteLeague,
            savedVoice,
            System.currentTimeMillis(),
        )
        val initialSnap = RustBridge.parseEngineSnapshot(RustBridge.nativeEngineSnapshot(engineHandle))
        _ui = MutableStateFlow(
            UiState(
                themeMode = savedTheme,
                voiceGuide = savedVoice,
                remoteLeague = remoteLeague,
                leagueStatus = if (remoteLeague) "접속 토큰을 입력해 주세요" else "시연용 가상 리그 · 가상 포인트",
            ).applyEngineSnapshot(initialSnap),
        )
        ui = _ui.asStateFlow()
    }

    private fun UiState.applyEngineSnapshot(s: RustBridge.EngineSnapshotWire): UiState = copy(
        simulation = s.simulation,
        tripActive = s.tripActive,
        liveSafety = s.liveSafety,
        liveTotal = s.liveTotal,
        liveHarshAccel = s.liveHarshAccel,
        liveHarshBrake = s.liveHarshBrake,
        liveHarshSteer = s.liveHarshSteer,
        lastHarshEvent = s.lastHarshEvent,
        liveEvents = s.liveEvents,
        harshEventSeq = s.harshEventSeq,
        lastResult = s.lastResult,
        newStamp = s.newStamp,
        notice = s.notice,
        trips = s.trips,
        stamps = s.stamps,
        badges = s.badges,
        league = if (remoteLeague) league else s.league,
        earnedPoints = if (remoteLeague) earnedPoints else s.earnedPoints,
        lastTripMarks = s.lastTripMarks,
        lastTripEvents = s.lastTripEvents,
        lastEcoDelta = s.lastEcoDelta,
        pastReport = s.pastReport,
        voiceGuide = s.voiceGuide,
    )

    private fun updateFromSnapshotJson(json: String) {
        val snap = RustBridge.parseEngineSnapshot(json)
        _ui.update { it.applyEngineSnapshot(snap) }
    }

    fun refreshLeague() {
        if (_ui.value.leagueBusy) return
        if (!remoteLeague) {
            updateFromSnapshotJson(
                RustBridge.nativeEngineRefreshLocalLeague(engineHandle, System.currentTimeMillis()),
            )
            return
        }
        val client = leagueClient ?: return
        val mode = _ui.value.simulation
        _ui.update { it.copy(leagueBusy = true, leagueStatus = "서버 리그 불러오는 중") }
        leagueJob = viewModelScope.launch { loadRemoteLeague(client, mode) }
    }

    fun connectLeague(token: String) {
        if (!remoteLeague) return
        val validToken = RustBridge.nativeEngineValidateToken(token)
        if (validToken == null) {
            _ui.update { it.copy(leagueStatus = "발급받은 접속 토큰을 확인해 주세요") }
            return
        }
        val client = runCatching {
            LeagueClient.create(
                BuildConfig.LEAGUE_API_BASE_URL,
                token = { validToken },
                allowLocalHttp = BuildConfig.DEBUG && BuildConfig.ALLOW_LOCAL_LEAGUE_HTTP,
            )
        }.getOrNull()
        if (client == null) {
            _ui.update { it.copy(leagueStatus = "서버 주소 설정을 확인해 주세요") }
            return
        }
        leagueJob?.cancel()
        leagueClient = client
        _ui.update { it.copy(league = null, earnedPoints = 0, leagueBusy = false, leagueConnected = false) }
        refreshLeague()
    }

    fun disconnectLeague() {
        leagueJob?.cancel()
        leagueClient = null
        _ui.update {
            it.copy(
                league = null,
                earnedPoints = 0,
                leagueBusy = false,
                leagueConnected = false,
                leagueStatus = "서버 연결 해제됨",
            )
        }
    }

    fun uploadLatestTrip() {
        val client = leagueClient ?: return
        if (_ui.value.leagueBusy || !_ui.value.leagueConnected || _ui.value.tripActive) return
        val mode = _ui.value.simulation
        val wire = RustBridge.parseLatestUpload(
            RustBridge.nativeEngineLatestTripUpload(engineHandle, System.currentTimeMillis()),
        )
        when (wire.status) {
            "empty" -> return
            "error" -> {
                _ui.update { it.copy(leagueStatus = wire.message ?: "전송할 수 없는 주행이에요") }
                return
            }
        }
        val dto = wire.trip ?: return
        _ui.update { it.copy(leagueBusy = true, leagueStatus = "최근 주행 1건 전송 중") }
        leagueJob = viewModelScope.launch {
            when (val reply = client.submit(dto)) {
                is UploadReply.Success -> loadRemoteLeague(
                    client,
                    mode,
                    if (reply.duplicate) "이미 전송된 주행 · 중복 집계하지 않음" else "전송 완료",
                )
                is UploadReply.Failed -> if (leagueClient === client && _ui.value.simulation == mode) {
                    _ui.update {
                        it.copy(
                            leagueBusy = false,
                            leagueStatus = "전송 실패 (${reply.httpStatus ?: reply.reason}) · 재시도 가능",
                        )
                    }
                }
            }
        }
    }

    private suspend fun loadRemoteLeague(client: LeagueClient, mode: Boolean, prefix: String = "개발 서버") {
        val week = League.weekStartMs(System.currentTimeMillis(), ZoneId.of("Asia/Seoul"))
        val reply = client.weekly(week, mode)
        if (leagueClient !== client || _ui.value.simulation != mode) return
        when (reply) {
            is LeagueReply.Success -> _ui.update {
                it.copy(
                    league = reply.snapshot.standing,
                    earnedPoints = reply.snapshot.earnedPoints,
                    leagueBusy = false,
                    leagueConnected = true,
                    leagueStatus = "$prefix · ${if (mode) "시뮬레이션" else "실제 주행"} 리그 · 가상 포인트",
                )
            }
            is LeagueReply.Failed -> _ui.update {
                it.copy(
                    league = null,
                    earnedPoints = 0,
                    leagueBusy = false,
                    leagueConnected = false,
                    leagueStatus = "서버 조회 실패 (${reply.httpStatus ?: reply.reason})",
                )
            }
        }
    }

    fun startVehicle() {
        if (source != null) return
        setSimulation(false)
    }

    fun setSimulation(enabled: Boolean) {
        if (_ui.value.tripActive) return
        stopDemo()
        detach()
        var simulation = enabled
        var fallbackNotice: String? = null
        val newSource: VehicleSource = if (enabled) {
            FakeVehicleSource().also { it.start() }
        } else {
            try {
                RealVehicleSource(getApplication()).also { it.start() }
            } catch (t: Throwable) {
                Log.w(TAG, "실제 차량 연결 실패, 시뮬레이션으로 전환", t)
                simulation = true
                fallbackNotice = "차량에 연결할 수 없어 시뮬레이션 모드로 실행합니다"
                FakeVehicleSource().also { it.start() }
            }
        }
        source = newSource
        if (remoteLeague) {
            leagueJob?.cancel()
            _ui.update { it.copy(league = null, earnedPoints = 0, leagueBusy = false, leagueConnected = false) }
        }
        val snapJson = RustBridge.nativeEngineSetSimulation(
            engineHandle,
            simulation,
            fallbackNotice,
            System.currentTimeMillis(),
        )
        val snap = RustBridge.parseEngineSnapshot(snapJson)
        _ui.update { it.copy(demoStep = null).applyEngineSnapshot(snap) }
        attach(newSource)
        if (remoteLeague) refreshLeague()
    }

    private fun attach(s: VehicleSource) {
        collectJob = viewModelScope.launch {
            launch { s.status.collect { v -> _ui.update { it.copy(sourceStatus = v) } } }
            launch { s.diagnostics.collect { v -> _ui.update { it.copy(diagnostics = v) } } }
            launch { s.speedKmh.collect { v -> _ui.update { it.copy(speedKmh = v) } } }
            launch { s.uxRestricted.collect { v -> _ui.update { it.copy(uxRestricted = v) } } }
            launch {
                s.steeringSamples.collect { sample ->
                    val step = RustBridge.parseEngineStep(
                        RustBridge.nativeEngineOnSteering(
                            engineHandle,
                            sample.timeMs,
                            sample.angleDeg,
                            System.currentTimeMillis(),
                        ),
                    )
                    applyEngineStep(step)
                }
            }
            launch {
                s.brakeSamples.collect { sample ->
                    RustBridge.nativeEngineOnBrakePedal(engineHandle, sample.timeMs, sample.pressed)
                }
            }
            launch {
                s.speedLimitSamples.collect { sample ->
                    RustBridge.nativeEngineOnSpeedLimit(engineHandle, sample.timeMs, sample.limitKmh)
                }
            }
            launch {
                s.speedSamples.collect { sample ->
                    val step = RustBridge.parseEngineStep(
                        RustBridge.nativeEngineOnSpeed(
                            engineHandle,
                            sample.timeMs,
                            sample.speedKmh,
                            System.currentTimeMillis(),
                        ),
                    )
                    applyEngineStep(step)
                }
            }
            launch {
                s.driveState.collect { state ->
                    _ui.update { it.copy(driveState = state) }
                    onDriveState(state)
                }
            }
        }
    }

    private fun applyEngineStep(step: RustBridge.EngineStepWire) {
        _ui.update { it.applyEngineSnapshot(step.snapshot) }
        step.speak?.let { voice.speak(it) }
        if (step.needsStartEnergy) {
            energyJob?.cancel()
            energyJob = viewModelScope.launch {
                val energy = source?.readEnergy()
                RustBridge.nativeEngineSetStartEnergy(
                    engineHandle,
                    RustBridge.toJson(energy),
                    System.currentTimeMillis(),
                )
            }
        }
        if (step.needsFinishEnergy) {
            finishJob = viewModelScope.launch {
                energyJob?.join()
                val endEnergy = source?.readEnergy()
                val arrival = source?.arrivedPlace?.value
                val arrivedPt = arrival?.point
                val addr = source?.address?.value
                val addrAt = source?.addressUpdatedAtMs?.value
                val finishStep = RustBridge.parseEngineStep(
                    RustBridge.nativeEngineCompleteFinish(
                        handle = engineHandle,
                        endEnergyJson = RustBridge.toJson(endEnergy),
                        hasArrival = arrival != null,
                        arrivalName = arrival?.name,
                        arrivalTimeMs = arrival?.wallTimeMs ?: 0L,
                        hasArrivalPoint = arrivedPt != null,
                        arrivalLat = arrivedPt?.latitude ?: 0.0,
                        arrivalLon = arrivedPt?.longitude ?: 0.0,
                        address = addr,
                        hasAddrTime = addrAt != null,
                        addrTime = addrAt ?: 0L,
                        nowMs = System.currentTimeMillis(),
                    ),
                )
                applyEngineStep(finishStep)
            }
        }
    }

    private fun onDriveState(state: DriveState) {
        val finishing = finishJob
        if (state == DriveState.DRIVING && finishing != null && finishing.isActive) {
            viewModelScope.launch {
                finishing.join()
                if (source?.driveState?.value == DriveState.DRIVING && !_ui.value.tripActive) {
                    dispatchDriveState(DriveState.DRIVING)
                }
            }
            return
        }
        dispatchDriveState(state)
    }

    private fun dispatchDriveState(state: DriveState) {
        val loc = source?.location?.value
        val step = RustBridge.parseEngineStep(
            RustBridge.nativeEngineOnDriveState(
                engineHandle,
                state.name,
                System.currentTimeMillis(),
                loc != null,
                loc?.latitude ?: 0.0,
                loc?.longitude ?: 0.0,
            ),
        )
        applyEngineStep(step)
    }

    fun recheckEnergy() {
        if (_ui.value.tripActive) return
        viewModelScope.launch { source?.readEnergy() }
    }

    private val voice = VoiceGuide(app) { engine -> _ui.update { it.copy(voiceEngine = engine) } }.also { it.start() }

    fun setVoiceGuide(on: Boolean) {
        prefs.edit().putBoolean("voiceGuide", on).apply()
        if (!on) voice.stopSpeaking()
        updateFromSnapshotJson(RustBridge.nativeEngineSetVoiceGuide(engineHandle, on))
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString("theme", mode.name).apply()
        _ui.update { it.copy(themeMode = mode) }
    }

    private fun detach() {
        collectJob?.cancel()
        collectJob = null
        source?.stop()
        source = null
    }

    fun navigateTo(stampId: Int) {
        val stamp = _ui.value.stamps.firstOrNull { it.id == stampId } ?: return
        val sent = source?.requestRoute(
            id = "kkwongal-egg-${stamp.id}",
            name = stamp.name,
            point = GeoPoint(stamp.latitude, stamp.longitude),
            address = stamp.name,
        ) ?: false
        updateFromSnapshotJson(RustBridge.nativeEngineNavigateMessage(engineHandle, stampId, sent))
    }

    fun saveStamp(id: Int, name: String, memo: String, tags: List<String>) {
        updateFromSnapshotJson(
            RustBridge.nativeEngineSaveStamp(engineHandle, id, name, memo, RustBridge.toJson(tags)!!),
        )
    }

    fun openTrip(trip: TripResult) {
        updateFromSnapshotJson(
            RustBridge.nativeEngineOpenTrip(engineHandle, trip.startTimeMs, trip.endTimeMs),
        )
    }

    fun deleteTrip(trip: TripResult) {
        updateFromSnapshotJson(
            RustBridge.nativeEngineDeleteTrip(
                engineHandle,
                trip.startTimeMs,
                trip.endTimeMs,
                System.currentTimeMillis(),
            ),
        )
    }

    fun closeTrip() {
        updateFromSnapshotJson(RustBridge.nativeEngineCloseTrip(engineHandle))
    }

    fun dismissResult() {
        updateFromSnapshotJson(RustBridge.nativeEngineDismissResult(engineHandle))
    }

    fun simSetSpeed(kmh: Double) {
        (source as? FakeVehicleSource)?.setSpeed(kmh)
    }

    fun simSetParked(parked: Boolean) {
        (source as? FakeVehicleSource)?.setParked(parked)
    }

    fun simSetLocation(point: GeoPoint, name: String? = null, address: String? = null) {
        (source as? FakeVehicleSource)?.setLocation(point, name, address)
    }

    fun simJerkSteering() {
        (source as? FakeVehicleSource)?.jerkSteering()
    }

    fun simRunDemo() {
        val fake = source as? FakeVehicleSource ?: return
        if (demoJob?.isActive == true || _ui.value.tripActive) return
        updateFromSnapshotJson(RustBridge.nativeEngineClearForDemo(engineHandle))
        demoJob = viewModelScope.launch {
            try {
                fake.runDemo { step -> _ui.update { it.copy(demoStep = step) } }
            } finally {
                _ui.update { it.copy(demoStep = null) }
            }
        }
    }

    fun simStopDemo() = stopDemo()

    private fun stopDemo() {
        demoJob?.cancel()
        demoJob = null
        (source as? FakeVehicleSource)?.endDemo()
    }

    override fun onCleared() {
        leagueJob?.cancel()
        leagueClient = null
        stopDemo()
        voice.release()
        if (engineHandle != 0L) {
            RustBridge.nativeEngineSaveOngoing(engineHandle, System.currentTimeMillis())
            RustBridge.nativeEngineFree(engineHandle)
            engineHandle = 0L
        }
        detach()
    }

    private companion object {
        const val TAG = "KkwongalViewModel"
    }
}
