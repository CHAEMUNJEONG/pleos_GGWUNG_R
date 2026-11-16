package com.kkwongal.drive

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kkwongal.drive.core.Badge
import com.kkwongal.drive.core.BadgeRules
import com.kkwongal.drive.core.Channel
import com.kkwongal.drive.core.ChannelState
import com.kkwongal.drive.core.EnergyMeter
import com.kkwongal.drive.core.EnergySnapshot
import com.kkwongal.drive.core.GeoPoint
import com.kkwongal.drive.core.HarshEvent
import com.kkwongal.drive.core.HarshType
import com.kkwongal.drive.core.League
import com.kkwongal.drive.core.LiveEvent
import com.kkwongal.drive.core.OngoingTrip
import com.kkwongal.drive.core.LeagueStanding
import com.kkwongal.drive.core.Stamp
import com.kkwongal.drive.core.StampBook
import com.kkwongal.drive.core.TripContinuation
import com.kkwongal.drive.core.TripRecorder
import com.kkwongal.drive.core.TripResult
import com.kkwongal.drive.core.TripSnapshot
import com.kkwongal.drive.data.SavedData
import com.kkwongal.drive.data.Storage
import com.kkwongal.drive.ui.ThemeMode
import com.kkwongal.drive.voice.VoiceGuide
import com.kkwongal.drive.vehicle.DriveState
import com.kkwongal.drive.vehicle.FakeVehicleSource
import com.kkwongal.drive.vehicle.RealVehicleSource
import com.kkwongal.drive.vehicle.VehicleSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/** 주행 중 급조작이 일어난 시점. fraction 은 주행 시간 중 위치 (0 = 출발, 1 = 도착). */
data class TripMark(val type: HarshType, val fraction: Float)

data class UiState(
    val simulation: Boolean = false,
    val sourceStatus: String = "",
    val driveState: DriveState = DriveState.UNKNOWN,
    val speedKmh: Double? = null,
    val uxRestricted: Boolean = false,
    val tripActive: Boolean = false,
    /** 주행 중 실시간 안전 점수. */
    val liveSafety: Int = 100,
    /** 주행 중 종합 점수 (에코 60% + 안전 40%). 주행 화면의 원형 게이지에 쓴다. */
    val liveTotal: Int = 100,
    val liveHarshAccel: Int = 0,
    val liveHarshBrake: Int = 0,
    val liveHarshSteer: Int = 0,
    /** 가장 최근 급조작. 화면에 잠깐 감점을 띄우는 데 쓴다. */
    val lastHarshEvent: HarshEvent? = null,
    /** 이번 주행의 감점 기록. 일어난 순서대로. */
    val liveEvents: List<LiveEvent> = emptyList(),
    /** 급조작이 일어날 때마다 1씩 늘어난다. 같은 감점이 연달아 나와도 화면이 다시 띄우도록 쓴다. */
    val harshEventSeq: Int = 0,
    /** 방금 끝난 주행. 다음 주행을 시작하거나 닫으면 null. */
    val lastResult: TripResult? = null,
    /** 방금 끝난 주행에서 새로 찍힌 스탬프. */
    val newStamp: Stamp? = null,
    val notice: String? = null,
    val trips: List<TripResult> = emptyList(),
    val stamps: List<Stamp> = emptyList(),
    val badges: Set<Badge> = emptySet(),
    /** 이번 주 리그 순위표. */
    val league: LeagueStanding? = null,
    /** 지난 주들까지 받은 가상 포인트 합계. */
    val earnedPoints: Int = 0,
    /** 화면 테마: 자동(차량 주야간을 따름) / 밝게 / 어둡게. */
    val themeMode: ThemeMode = ThemeMode.AUTO,
    /** 방금 끝난 주행에서 급조작이 일어난 시점들. 리포트의 시간 막대에 쓴다. */
    val lastTripMarks: List<TripMark> = emptyList(),
    /** 차량 데이터 종류별 연결 상태. 연결 점검 화면에 쓴다. */
    val diagnostics: Map<Channel, ChannelState> = emptyMap(),
    /** 방금 끝난 주행의 감점 기록. 리포트에 보여 준다. */
    val lastTripEvents: List<LiveEvent> = emptyList(),
    /** 주행 중 감점을 음성으로 알려 줄지. 설정에서 끌 수 있다. */
    val voiceGuide: Boolean = true,
    /** 지금 쓰는 음성 엔진 이름 (설정 화면 안내용). */
    val voiceEngine: String = "음성 준비 중",
)

class KkwongalViewModel(app: Application) : AndroidViewModel(app) {

    private val storage = Storage(app)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val stampBook: StampBook
    private var trips: List<TripResult>

    private val recorder = TripRecorder()
    private var tripStartWallMs = 0L
    private var tripStartEnergy: EnergySnapshot? = null
    private var energyJob: Job? = null

    /** 도착 처리(연비 읽기·저장) 중인 작업. 끝나기 전에 다시 출발하면 끝난 뒤에 출발을 처리한다. */
    private var finishJob: Job? = null

    /** 진행 중 주행을 마지막으로 저장한 시각. */
    private var lastOngoingSaveMs = 0L

    /** 앱이 꺼지기 전에 진행 중이던 주행. 차 상태를 처음 받았을 때 이어 갈지 정한다. */
    private var pendingRecovery: OngoingTrip?

    /** 방금 끝난 주행. 잠깐 정차 후 다시 출발하면 여기에 이어 붙인다. */
    private var lastFinished: FinishedTrip? = null

    private data class FinishedTrip(
        /** 기록에 저장한 결과. 너무 짧아 저장하지 않았으면 null. */
        val result: TripResult?,
        val snapshot: TripSnapshot,
        val startWallMs: Long,
        val endWallMs: Long,
        val startEnergy: EnergySnapshot?,
        val events: List<LiveEvent>,
        /** 이 주행으로 새로 찍힌 스탬프와, 찍힐 때의 이름. */
        val stamp: Stamp?,
        val stoppedAt: GeoPoint?,
    )

    private var source: VehicleSource? = null
    private var collectJob: Job? = null

    private val _ui: MutableStateFlow<UiState>
    val ui: StateFlow<UiState>

    init {
        val saved = storage.load()
        trips = saved.trips
        stampBook = StampBook(saved.stamps)
        pendingRecovery = storage.loadOngoing()
        val savedVoice = prefs.getBoolean("voiceGuide", true)
        val savedTheme = runCatching { ThemeMode.valueOf(prefs.getString("theme", null) ?: "AUTO") }
            .getOrDefault(ThemeMode.AUTO)
        _ui = MutableStateFlow(
            UiState(
                themeMode = savedTheme,
                voiceGuide = savedVoice,
                trips = trips,
                stamps = stampBook.stamps,
                badges = BadgeRules.evaluate(trips, stampBook.stamps),
                league = leagueNow(),
                earnedPoints = pointsNow(),
            ),
        )
        ui = _ui.asStateFlow()
    }

    private fun leagueNow(): LeagueStanding =
        League.standing(trips, System.currentTimeMillis(), ZoneId.systemDefault())

    private fun pointsNow(): Int =
        League.earnedPoints(trips, System.currentTimeMillis(), ZoneId.systemDefault())

    /** 주가 바뀌었을 수 있으니 화면에 돌아올 때 순위표를 다시 계산한다. */
    fun refreshLeague() {
        _ui.update { it.copy(league = leagueNow(), earnedPoints = pointsNow()) }
    }

    /** 실제 차량 연결을 시도하고, 안 되면 시뮬레이션으로 넘어간다. 이미 시작했으면 아무것도 하지 않는다. */
    fun startVehicle() {
        if (source != null) return
        setSimulation(false)
    }

    fun setSimulation(enabled: Boolean) {
        if (_ui.value.tripActive) return // 주행 중에는 바꾸지 않는다
        detach()
        lastFinished = null // 실제 차량 주행과 시뮬레이션 주행을 이어 붙이지 않는다
        var simulation = enabled
        var fallbackNotice: String? = null
        val newSource: VehicleSource = if (enabled) {
            FakeVehicleSource().also { it.start() }
        } else {
            try {
                RealVehicleSource(getApplication()).also { it.start() }
            } catch (t: Throwable) { // 차량용 API 가 없는 기기에서는 클래스를 찾지 못하는 오류(Error)가 난다
                Log.w(TAG, "실제 차량 연결 실패, 시뮬레이션으로 전환", t)
                simulation = true
                fallbackNotice = "차량에 연결할 수 없어 시뮬레이션 모드로 실행합니다"
                FakeVehicleSource().also { it.start() }
            }
        }
        source = newSource
        _ui.update { it.copy(simulation = simulation, notice = fallbackNotice) }
        attach(newSource)
    }

    private fun attach(s: VehicleSource) {
        collectJob = viewModelScope.launch {
            launch { s.status.collect { v -> _ui.update { it.copy(sourceStatus = v) } } }
            launch { s.diagnostics.collect { v -> _ui.update { it.copy(diagnostics = v) } } }
            launch { s.speedKmh.collect { v -> _ui.update { it.copy(speedKmh = v) } } }
            launch { s.uxRestricted.collect { v -> _ui.update { it.copy(uxRestricted = v) } } }
            launch {
                s.steeringSamples.collect { sample ->
                    recorder.onSteering(sample.timeMs, sample.angleDeg)?.let { onHarshEvent(it) }
                }
            }
            launch {
                // 브레이크 페달 신호 (Pleos Vehicle SDK). 급정거를 "브레이크를 밟으며 감속"으로 판정하는 데 쓴다.
                s.brakeSamples.collect { sample -> recorder.onBrakePedal(sample.timeMs, sample.pressed) }
            }
            launch {
                s.speedSamples.collect { sample ->
                    val event = recorder.onSpeed(sample.timeMs, sample.speedKmh)
                    if (event != null) {
                        onHarshEvent(event)
                    } else if (recorder.isActive &&
                        (recorder.currentSafetyScore != _ui.value.liveSafety || recorder.currentTotalScore != _ui.value.liveTotal)
                    ) {
                        // 주행 거리가 늘면 같은 감점도 비중이 줄어 점수가 조금씩 회복되고, 에코 점수는 가속 패턴에 따라 바뀐다
                        _ui.update { it.copy(liveSafety = recorder.currentSafetyScore, liveTotal = recorder.currentTotalScore) }
                    }
                    if (recorder.isActive && System.currentTimeMillis() - lastOngoingSaveMs >= ONGOING_SAVE_INTERVAL_MS) {
                        saveOngoing()
                    }
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

    /** 연결 점검 화면에서: 연료·배터리 잔량을 지금 한 번 읽어 본다. 주행 중에는 하지 않는다. */
    fun recheckEnergy() {
        if (recorder.isActive) return
        viewModelScope.launch { source?.readEnergy() }
    }

    /** 감점 음성 안내. 기기 안 음성 엔진을 쓰므로 인터넷이 없어도 된다. */
    private val voice = VoiceGuide(app) { engine -> _ui.update { it.copy(voiceEngine = engine) } }.also { it.start() }

    fun setVoiceGuide(on: Boolean) {
        prefs.edit().putBoolean("voiceGuide", on).apply()
        if (!on) voice.stopSpeaking()
        _ui.update { it.copy(voiceGuide = on) }
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString("theme", mode.name).apply()
        _ui.update { it.copy(themeMode = mode) }
    }

    /** 급조작이 감지되면 실시간 점수와 감점 기록을 갱신하고, 바로 저장해 둔다. */
    private fun onHarshEvent(event: HarshEvent) {
        // 화면 대신 귀로: 어떤 급조작으로 몇 점이 깎였는지 음성으로 알려 준다
        if (_ui.value.voiceGuide) voice.speak(VoiceGuide.penaltyMessage(event.type.label, event.penalty))
        _ui.update {
            it.copy(
                liveSafety = event.safetyAfter,
                liveTotal = recorder.currentTotalScore,
                liveHarshAccel = recorder.currentHarshAccel,
                liveHarshBrake = recorder.currentHarshBrake,
                liveHarshSteer = recorder.currentHarshSteer,
                lastHarshEvent = event,
                liveEvents = it.liveEvents + LiveEvent(
                    event.type, event.penalty, event.safetyAfter,
                    System.currentTimeMillis(), recorder.currentDistanceKm,
                ),
                harshEventSeq = it.harshEventSeq + 1,
            )
        }
        saveOngoing()
    }

    private fun detach() {
        collectJob?.cancel()
        collectJob = null
        source?.stop()
        source = null
    }

    private fun onDriveState(state: DriveState) {
        when (state) {
            DriveState.DRIVING -> if (!recorder.isActive) onDepart()
            DriveState.PARKED -> {
                // 앱이 꺼져 있는 동안 주행이 끝났다: 그때까지의 기록을 남긴다
                pendingRecovery?.let {
                    pendingRecovery = null
                    saveRecovered(it)
                }
                if (recorder.isActive) finishTrip()
            }
            else -> Unit
        }
    }

    /** 출발. 이어 갈 주행이 있으면 이어 가고, 없으면 새 주행을 시작한다. */
    private fun onDepart() {
        val finishing = finishJob
        if (finishing != null && finishing.isActive) {
            // 도착 처리가 끝난 뒤에 다시 판단한다 (그사이 다시 주차했을 수도 있다)
            viewModelScope.launch {
                finishing.join()
                if (source?.driveState?.value == DriveState.DRIVING && !recorder.isActive) onDepart()
            }
            return
        }
        val now = System.currentTimeMillis()
        val recovery = pendingRecovery
        pendingRecovery = null
        val previous = lastFinished
        lastFinished = null
        when {
            recovery != null && TripContinuation.canRecover(recovery.savedAtMs, now) -> {
                resumeTrip(
                    recovery.startWallMs, recovery.snapshot, recovery.startEnergy, recovery.events,
                    "앱이 다시 켜져서 하던 주행을 이어서 기록해요",
                )
            }
            previous != null &&
                TripContinuation.canContinue(previous.endWallMs, now, previous.stoppedAt, source?.location?.value) -> {
                undoFinished(previous)
                resumeTrip(
                    previous.startWallMs, previous.snapshot, previous.startEnergy, previous.events,
                    "잠깐 멈췄다 다시 출발해서 앞 주행에 이어서 기록해요",
                )
            }
            else -> {
                recovery?.let { saveRecovered(it) }
                startNewTrip()
            }
        }
    }

    private fun startNewTrip() {
        energyJob?.cancel() // 앞 주행에서 아직 끝나지 않은 잔량 읽기가 새 주행 값을 덮지 않도록
        recorder.start()
        tripStartWallMs = System.currentTimeMillis()
        tripStartEnergy = null
        // 출발 시점의 연료·배터리 잔량. 실제 연비 계산에 쓴다.
        energyJob = viewModelScope.launch {
            tripStartEnergy = source?.readEnergy()
            saveOngoing()
        }
        showTripStarted(emptyList(), notice = null)
    }

    private fun resumeTrip(
        startWallMs: Long,
        snapshot: TripSnapshot,
        startEnergy: EnergySnapshot?,
        events: List<LiveEvent>,
        notice: String,
    ) {
        recorder.resume(snapshot)
        tripStartWallMs = startWallMs
        // 출발 때 잔량을 모르면 지금 읽으면 안 된다 (앞부분 주행이 빠진다). 이 주행은 추정 에코 점수만 쓴다.
        tripStartEnergy = startEnergy
        energyJob = null
        showTripStarted(events, notice)
    }

    private fun showTripStarted(events: List<LiveEvent>, notice: String?) {
        _ui.update {
            it.copy(
                tripActive = true,
                lastResult = null,
                newStamp = null,
                notice = notice,
                liveSafety = recorder.currentSafetyScore,
                liveTotal = recorder.currentTotalScore,
                liveHarshAccel = recorder.currentHarshAccel,
                liveHarshBrake = recorder.currentHarshBrake,
                liveHarshSteer = recorder.currentHarshSteer,
                lastHarshEvent = null,
                liveEvents = events,
            )
        }
        saveOngoing()
    }

    /** 진행 중인 주행을 파일에 저장한다. 앱이 갑자기 꺼져도 이어 갈 수 있도록. */
    private fun saveOngoing() {
        if (!recorder.isActive) return
        val now = System.currentTimeMillis()
        lastOngoingSaveMs = now
        storage.saveOngoing(
            OngoingTrip(tripStartWallMs, now, recorder.snapshot(), tripStartEnergy, _ui.value.liveEvents),
        )
    }

    /** 앱이 꺼져 있던 동안 끝난 주행을 기록에 남긴다. 도착 위치를 모르므로 스탬프는 찍지 않는다. */
    private fun saveRecovered(trip: OngoingTrip) {
        storage.clearOngoing()
        val r = TripRecorder()
        r.resume(trip.snapshot)
        val measured = r.finish(trip.startWallMs, trip.savedAtMs)
        if (measured.distanceKm < MIN_TRIP_KM) return
        trips = trips + EnergyMeter.apply(measured, null, trips)
        persist()
        _ui.update {
            it.copy(
                notice = "앱이 꺼져 있던 동안 끝난 주행을 기록에 남겼어요",
                trips = trips,
                badges = BadgeRules.evaluate(trips, stampBook.stamps),
                league = leagueNow(),
                earnedPoints = pointsNow(),
            )
        }
    }

    /** 이어 붙이기 위해 방금 끝낸 주행의 기록과, 그때 자동으로 찍힌 스탬프를 되돌린다. */
    private fun undoFinished(previous: FinishedTrip) {
        previous.result?.let { result ->
            val index = trips.lastIndexOf(result)
            if (index >= 0) trips = trips.toMutableList().apply { removeAt(index) }
        }
        previous.stamp?.let { stamp ->
            // 이름·한마디·태그를 직접 남긴 스탬프는 지우지 않는다
            val current = stampBook.stamps.firstOrNull { it.id == stamp.id }
            if (current != null && current.name == stamp.name && current.memo.isBlank() && current.tags.isEmpty()) {
                stampBook.remove(stamp.id)
            }
        }
        persist()
        _ui.update {
            it.copy(
                trips = trips,
                stamps = stampBook.stamps,
                badges = BadgeRules.evaluate(trips, stampBook.stamps),
                league = leagueNow(),
                earnedPoints = pointsNow(),
            )
        }
    }

    private fun finishTrip() {
        val endWallMs = System.currentTimeMillis()
        val startWallMs = tripStartWallMs
        val measured = recorder.finish(startWallMs, endWallMs)
        val snapshot = recorder.snapshot()
        val events = _ui.value.liveEvents
        val stoppedAt = source?.location?.value
        storage.clearOngoing()
        if (measured.distanceKm < MIN_TRIP_KM) {
            lastFinished = FinishedTrip(null, snapshot, startWallMs, endWallMs, tripStartEnergy, events, null, stoppedAt)
            _ui.update {
                it.copy(tripActive = false, notice = "주행 거리가 너무 짧아 기록하지 않았습니다")
            }
            return
        }
        finishJob = viewModelScope.launch {
            // 도착 시점의 잔량을 읽어 출발 때와 비교한다 (최대 몇 초). 못 읽으면 추정 에코 점수 그대로.
            energyJob?.join()
            val startEnergy = tripStartEnergy
            val endEnergy = source?.readEnergy()
            val efficiency = EnergyMeter.measure(startEnergy, endEnergy, measured.distanceKm)
            completeTrip(
                EnergyMeter.apply(measured, efficiency, trips),
                FinishedTrip(null, snapshot, startWallMs, endWallMs, startEnergy, events, null, stoppedAt),
            )
        }
    }

    private fun completeTrip(result: TripResult, finished: FinishedTrip) {
        trips = trips + result
        val stamp = finished.stoppedAt?.let {
            val perfect = result.harshAccelCount + result.harshBrakeCount + result.harshSteerCount == 0
            stampBook.tryAdd(it, result.endTimeMs, result.score, placeNameFor(finished.startWallMs), perfect)
        }
        persist()
        lastFinished = finished.copy(result = result, stamp = stamp)
        _ui.update {
            it.copy(
                tripActive = recorder.isActive,
                lastResult = result,
                lastTripEvents = finished.events,
                lastTripMarks = TripContinuation.marks(finished.events, finished.startWallMs, finished.endWallMs)
                    .map { (type, fraction) -> TripMark(type, fraction) },
                newStamp = stamp,
                trips = trips,
                stamps = stampBook.stamps,
                badges = BadgeRules.evaluate(trips, stampBook.stamps),
                league = leagueNow(),
                earnedPoints = pointsNow(),
            )
        }
    }

    /**
     * 스탬프 이름을 자동으로 정한다. 이번 주행 중에 내비가 알려 준 목적지 이름을 먼저 쓰고,
     * 없으면 현재 위치의 주소를 쓴다. 둘 다 없으면 null (기본 이름 "새로운 장소 N").
     */
    private fun placeNameFor(tripStartMs: Long): String? {
        val arrived = source?.arrivedPlace?.value
        if (arrived != null && arrived.wallTimeMs >= tripStartMs && arrived.name.isNotBlank()) {
            return arrived.name.trim()
        }
        return source?.address?.value?.trim()?.takeIf { it.isNotBlank() }
    }

    /** 스탬프의 이름·메모·태그를 저장한다. */
    /** 여행 지도에서: 이 알이 있는 곳으로 내비 길 안내를 시작한다 (Pleos NaviHelper requestRoute). */
    fun navigateTo(stampId: Int) {
        val stamp = stampBook.stamps.firstOrNull { it.id == stampId } ?: return
        val sent = source?.requestRoute(
            id = "kkwongal-egg-${stamp.id}",
            name = stamp.name,
            point = GeoPoint(stamp.latitude, stamp.longitude),
            address = stamp.name,
        ) ?: false
        val message = when {
            !sent -> "내비에 연결되지 않아 길 안내를 시작하지 못했어요"
            _ui.value.simulation -> "시뮬레이션: 목적지를 ${stamp.name}(으)로 정했어요. 출발 후 주차하면 도착해요"
            else -> "${stamp.name}(으)로 길 안내를 요청했어요"
        }
        _ui.update { it.copy(notice = message) }
    }

    fun saveStamp(id: Int, name: String, memo: String, tags: List<String>) {
        val updated = stampBook.update(id, name, memo, tags) ?: return
        persist()
        _ui.update {
            it.copy(
                stamps = stampBook.stamps,
                newStamp = if (it.newStamp?.id == id) updated else it.newStamp,
            )
        }
    }

    fun dismissResult() {
        _ui.update { it.copy(lastResult = null, newStamp = null, notice = null) }
    }

    private fun persist() {
        storage.save(SavedData(trips = trips, stamps = stampBook.stamps))
    }

    // 시뮬레이션 패널에서 호출한다. 실제 차량 모드에서는 아무 일도 하지 않는다.

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

    override fun onCleared() {
        voice.release()
        saveOngoing()
        detach()
    }

    private companion object {
        const val TAG = "KkwongalViewModel"

        /** 이보다 짧은 주행은 기록하지 않는다. */
        const val MIN_TRIP_KM = 0.3

        /** 진행 중인 주행을 이 간격마다 저장한다. */
        const val ONGOING_SAVE_INTERVAL_MS = 30_000L
    }
}
