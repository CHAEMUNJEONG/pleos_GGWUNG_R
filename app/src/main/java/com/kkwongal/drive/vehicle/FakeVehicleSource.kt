package com.kkwongal.drive.vehicle

import com.kkwongal.drive.core.GeoPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 차 없이 앱을 돌려 보기 위한 가짜 차량. 화면의 시뮬레이션 패널이 값을 넣는다.
 * 일반 안드로이드 에뮬레이터나 휴대폰에서도 동작한다.
 */
class FakeVehicleSource(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val tickMs: Long = 200L,
) : VehicleSource {

    private val _driveState = MutableStateFlow(DriveState.PARKED)
    private val _speedKmh = MutableStateFlow<Double?>(0.0)
    private val _speedSamples = MutableSharedFlow<SpeedSample>(extraBufferCapacity = 64)
    private val _location = MutableStateFlow<GeoPoint?>(null)
    private val _uxRestricted = MutableStateFlow(false)
    private val _status = MutableStateFlow("시뮬레이션 모드")

    override val driveState: StateFlow<DriveState> = _driveState.asStateFlow()
    override val speedKmh: StateFlow<Double?> = _speedKmh.asStateFlow()
    override val speedSamples: SharedFlow<SpeedSample> = _speedSamples.asSharedFlow()
    override val location: StateFlow<GeoPoint?> = _location.asStateFlow()
    override val uxRestricted: StateFlow<Boolean> = _uxRestricted.asStateFlow()
    override val status: StateFlow<String> = _status.asStateFlow()

    private var scope: CoroutineScope? = null
    private var ticker: Job? = null

    override fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        // 실제 차량처럼 일정 간격으로 속도 값을 내보낸다
        ticker = s.launch {
            while (isActive) {
                _speedSamples.tryEmit(SpeedSample(clockMs(), _speedKmh.value ?: 0.0))
                delay(tickMs)
            }
        }
    }

    override fun stop() {
        ticker?.cancel()
        scope?.cancel()
        ticker = null
        scope = null
    }

    // 아래는 시뮬레이션 패널에서 호출한다

    fun setSpeed(kmh: Double) {
        _speedKmh.value = kmh.coerceIn(0.0, 200.0)
    }

    fun setParked(parked: Boolean) {
        if (parked) _speedKmh.value = 0.0
        _driveState.value = if (parked) DriveState.PARKED else DriveState.DRIVING
        // 실제 차량에서는 플랫폼이 알려 준다. 여기서는 주행 중이면 제한된 것으로 본다.
        _uxRestricted.value = !parked
    }

    fun setLocation(point: GeoPoint?) {
        _location.value = point
    }
}
