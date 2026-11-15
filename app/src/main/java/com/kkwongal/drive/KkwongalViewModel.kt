package com.kkwongal.drive

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kkwongal.drive.core.Badge
import com.kkwongal.drive.core.BadgeRules
import com.kkwongal.drive.core.GeoPoint
import com.kkwongal.drive.core.HarshEvent
import com.kkwongal.drive.core.League
import com.kkwongal.drive.core.LeagueStanding
import com.kkwongal.drive.core.Stamp
import com.kkwongal.drive.core.StampBook
import com.kkwongal.drive.core.TripRecorder
import com.kkwongal.drive.core.TripResult
import com.kkwongal.drive.data.SavedData
import com.kkwongal.drive.data.Storage
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

data class UiState(
    val simulation: Boolean = false,
    val sourceStatus: String = "",
    val driveState: DriveState = DriveState.UNKNOWN,
    val speedKmh: Double? = null,
    val uxRestricted: Boolean = false,
    val tripActive: Boolean = false,
    /** 주행 중 실시간 안전 점수. */
    val liveSafety: Int = 100,
    val liveHarshAccel: Int = 0,
    val liveHarshBrake: Int = 0,
    /** 가장 최근 급조작. 화면에 잠깐 감점을 띄우는 데 쓴다. */
    val lastHarshEvent: HarshEvent? = null,
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
)

class KkwongalViewModel(app: Application) : AndroidViewModel(app) {

    private val storage = Storage(app)
    private val stampBook: StampBook
    private var trips: List<TripResult>

    private val recorder = TripRecorder()
    private var tripStartWallMs = 0L

    private var source: VehicleSource? = null
    private var collectJob: Job? = null

    private val _ui: MutableStateFlow<UiState>
    val ui: StateFlow<UiState>

    init {
        val saved = storage.load()
        trips = saved.trips
        stampBook = StampBook(saved.stamps)
        _ui = MutableStateFlow(
            UiState(
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
            launch { s.speedKmh.collect { v -> _ui.update { it.copy(speedKmh = v) } } }
            launch { s.uxRestricted.collect { v -> _ui.update { it.copy(uxRestricted = v) } } }
            launch {
                s.speedSamples.collect { sample ->
                    val event = recorder.onSpeed(sample.timeMs, sample.speedKmh)
                    if (event != null) {
                        _ui.update {
                            it.copy(
                                liveSafety = event.safetyAfter,
                                liveHarshAccel = recorder.currentHarshAccel,
                                liveHarshBrake = recorder.currentHarshBrake,
                                lastHarshEvent = event,
                                harshEventSeq = it.harshEventSeq + 1,
                            )
                        }
                    } else if (recorder.isActive && recorder.currentSafetyScore != _ui.value.liveSafety) {
                        // 주행 거리가 늘면 같은 감점도 비중이 줄어 점수가 조금씩 회복된다
                        _ui.update { it.copy(liveSafety = recorder.currentSafetyScore) }
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

    private fun detach() {
        collectJob?.cancel()
        collectJob = null
        source?.stop()
        source = null
    }

    private fun onDriveState(state: DriveState) {
        when {
            state == DriveState.DRIVING && !recorder.isActive -> {
                recorder.start()
                tripStartWallMs = System.currentTimeMillis()
                _ui.update {
                    it.copy(
                        tripActive = true,
                        lastResult = null,
                        newStamp = null,
                        notice = null,
                        liveSafety = 100,
                        liveHarshAccel = 0,
                        liveHarshBrake = 0,
                        lastHarshEvent = null,
                    )
                }
            }
            state == DriveState.PARKED && recorder.isActive -> finishTrip()
        }
    }

    private fun finishTrip() {
        val result = recorder.finish(tripStartWallMs, System.currentTimeMillis())
        if (result.distanceKm < MIN_TRIP_KM) {
            _ui.update {
                it.copy(tripActive = false, notice = "주행 거리가 너무 짧아 기록하지 않았습니다")
            }
            return
        }
        trips = trips + result
        val stamp = source?.location?.value?.let { stampBook.tryAdd(it, result.endTimeMs, result.score) }
        persist()
        _ui.update {
            it.copy(
                tripActive = false,
                lastResult = result,
                newStamp = stamp,
                trips = trips,
                stamps = stampBook.stamps,
                badges = BadgeRules.evaluate(trips, stampBook.stamps),
                league = leagueNow(),
                earnedPoints = pointsNow(),
            )
        }
    }

    /** 스탬프의 이름·메모·태그를 저장한다. */
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

    fun simSetLocation(point: GeoPoint) {
        (source as? FakeVehicleSource)?.setLocation(point)
    }

    override fun onCleared() {
        detach()
    }

    private companion object {
        const val TAG = "KkwongalViewModel"

        /** 이보다 짧은 주행은 기록하지 않는다. */
        const val MIN_TRIP_KM = 0.3
    }
}
