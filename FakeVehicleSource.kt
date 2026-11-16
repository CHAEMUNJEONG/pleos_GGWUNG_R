package com.kkwongal.drive.vehicle

import com.kkwongal.drive.core.Channel
import com.kkwongal.drive.core.ChannelState
import com.kkwongal.drive.core.DiagnosticsBoard
import com.kkwongal.drive.core.EnergySnapshot
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
    private val _steeringSamples = MutableSharedFlow<SteeringSample>(extraBufferCapacity = 64)
    private val _brakeSamples = MutableSharedFlow<BrakeSample>(extraBufferCapacity = 16)
    private var brakeRelease: Job? = null
    private val _location = MutableStateFlow<GeoPoint?>(null)
    private val _address = MutableStateFlow<String?>(null)
    private val _arrivedPlace = MutableStateFlow<ArrivedPlace?>(null)
    @Volatile private var steeringAngle = 0.0
    /** 시뮬레이션 위치 버튼으로 정한 목적지 이름. 주차할 때 "도착"으로 알린다. */
    @Volatile private var destinationName: String? = null

    // 가상 전기차: 배터리 72kWh, 누적 주행거리 12,000km 에서 시작한다.
    @Volatile private var batteryWh = 72_000.0
    @Volatile private var odometerKm = 12_000.0
    private var lastTickMs: Long? = null
    private var lastTickSpeed = 0.0
    private val _uxRestricted = MutableStateFlow(false)
    private val _status = MutableStateFlow("시뮬레이션 모드")
    private val _diagnostics = MutableStateFlow<Map<Channel, ChannelState>>(emptyMap())
    private val board = DiagnosticsBoard(clockMs = { System.currentTimeMillis() }) { _diagnostics.value = it }

    override val driveState: StateFlow<DriveState> = _driveState.asStateFlow()
    override val speedKmh: StateFlow<Double?> = _speedKmh.asStateFlow()
    override val speedSamples: SharedFlow<SpeedSample> = _speedSamples.asSharedFlow()
    override val steeringSamples: SharedFlow<SteeringSample> = _steeringSamples.asSharedFlow()
    override val brakeSamples: SharedFlow<BrakeSample> = _brakeSamples.asSharedFlow()
    override val location: StateFlow<GeoPoint?> = _location.asStateFlow()
    override val address: StateFlow<String?> = _address.asStateFlow()
    override val arrivedPlace: StateFlow<ArrivedPlace?> = _arrivedPlace.asStateFlow()
    override val uxRestricted: StateFlow<Boolean> = _uxRestricted.asStateFlow()
    override val status: StateFlow<String> = _status.asStateFlow()
    override val diagnostics: StateFlow<Map<Channel, ChannelState>> = _diagnostics.asStateFlow()

    init {
        board.ok(Channel.GEAR, "P (주차) · 시뮬레이션")
        board.ok(Channel.UX, "제한 없음 · 시뮬레이션")
        board.waiting(Channel.LOCATION, "시뮬레이션 패널에서 목적지를 고르세요")
        board.waiting(Channel.ARRIVAL, "목적지를 고르고 주차하면 받아요")
        board.waiting(Channel.ENERGY, "주행을 시작하면 읽어요")
        board.waiting(Channel.BRAKE, "속도를 줄이면 브레이크를 밟은 것으로 흉내 내요")
        board.ok(Channel.EFFICIENCY, "시뮬레이션에서는 배터리 잔량으로 계산해요")
        board.waiting(Channel.TRIP, "주행을 시작하면 읽어요")
        _diagnostics.value = board.current
    }

    private var scope: CoroutineScope? = null
    private var ticker: Job? = null

    override fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        // 실제 차량처럼 일정 간격으로 속도 값을 내보낸다
        ticker = s.launch {
            while (isActive) {
                val now = clockMs()
                val speed = _speedKmh.value ?: 0.0
                consumeEnergy(now, speed)
                _speedSamples.tryEmit(SpeedSample(now, speed))
                _steeringSamples.tryEmit(SteeringSample(now, steeringAngle))
                board.ok(Channel.SPEED, String.format(java.util.Locale.US, "%.0f km/h · 시뮬레이션", speed))
                board.ok(Channel.STEERING, String.format(java.util.Locale.US, "%.0f° · 시뮬레이션", steeringAngle))
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

    /**
     * 가상 전기차의 에너지 소비. 달린 거리만큼 기본 소비(140Wh/km)에, 속도를 올릴 때 드는
     * 에너지(차량 2톤)를 더한다. 그래서 가다 서다를 반복하면 전비가 나빠진다.
     */
    private fun consumeEnergy(nowMs: Long, speedKmh: Double) {
        val last = lastTickMs
        lastTickMs = nowMs
        val before = lastTickSpeed
        lastTickSpeed = speedKmh
        if (last == null) return
        val dtHours = (nowMs - last) / 3_600_000.0
        if (dtHours <= 0 || dtHours > 5.0 / 3600) return
        val km = (before + speedKmh) / 2.0 * dtHours
        odometerKm += km
        var wh = km * BASE_WH_PER_KM
        if (speedKmh > before) {
            val v0 = before / 3.6
            val v1 = speedKmh / 3.6
            wh += 0.5 * MASS_KG * (v1 * v1 - v0 * v0) / 3600.0 * LOSS_FACTOR
        }
        batteryWh = (batteryWh - wh).coerceAtLeast(0.0)
    }

    override suspend fun readEnergy(): EnergySnapshot =
        EnergySnapshot(evBatteryWh = batteryWh, odometerKm = odometerKm).also {
            board.ok(Channel.ENERGY, energyText(it) + " · 시뮬레이션")
            board.ok(Channel.TRIP, String.format(java.util.Locale.US, "누적 %,.1f km · 시뮬레이션", odometerKm))
        }

    /** 시뮬레이션에서는 내비 대신 시뮬레이션 목적지를 그 장소로 바꾼다. 출발 후 주차하면 그곳에 도착한다. */
    override fun requestRoute(id: String, name: String, point: GeoPoint, address: String): Boolean {
        setLocation(point, name, address)
        return true
    }

    // 아래는 시뮬레이션 패널에서 호출한다

    fun setSpeed(kmh: Double) {
        val target = kmh.coerceIn(0.0, 200.0)
        val current = _speedKmh.value ?: 0.0
        // 속도를 줄이면 브레이크 페달을 밟았다가 1.5초 뒤 뗀 것으로 흉내 낸다
        if (target < current - 0.5) pressBrake()
        _speedKmh.value = target
    }

    private fun pressBrake() {
        val s = scope ?: return
        _brakeSamples.tryEmit(BrakeSample(clockMs(), true))
        board.ok(Channel.BRAKE, "밟는 중 · 시뮬레이션")
        brakeRelease?.cancel()
        brakeRelease = s.launch {
            delay(1_500)
            _brakeSamples.tryEmit(BrakeSample(clockMs(), false))
            board.ok(Channel.BRAKE, "떼고 있음 · 시뮬레이션")
        }
    }

    fun setParked(parked: Boolean) {
        if (parked) {
            _speedKmh.value = 0.0
            steeringAngle = 0.0
            // 실제 차량에서는 내비가 주차 전에 도착을 알려 준다. 기어를 바꾸기 전에 먼저 알린다.
            destinationName?.let {
                _arrivedPlace.value = ArrivedPlace(it, System.currentTimeMillis())
                board.ok(Channel.ARRIVAL, "$it · 시뮬레이션")
            }
        }
        _driveState.value = if (parked) DriveState.PARKED else DriveState.DRIVING
        board.ok(Channel.GEAR, (if (parked) "P (주차)" else "D (주행)") + " · 시뮬레이션")
        // 실제 차량에서는 플랫폼이 알려 준다. 여기서는 주행 중이면 제한된 것으로 본다.
        _uxRestricted.value = !parked
    }

    fun setLocation(point: GeoPoint?, name: String? = null, address: String? = null) {
        _location.value = point
        destinationName = name
        _address.value = address
        if (point != null) board.ok(Channel.LOCATION, (name ?: address ?: "선택한 위치") + " · 시뮬레이션")
    }

    /** 핸들을 한쪽으로 확 꺾었다가 0.6초 뒤 되돌린다. */
    fun jerkSteering() {
        val s = scope ?: return
        s.launch {
            steeringAngle = 120.0
            delay(600)
            steeringAngle = 0.0
        }
    }

    private companion object {
        const val BASE_WH_PER_KM = 140.0
        const val MASS_KG = 2_000.0
        /** 가속에 쓴 에너지 중 실제로 잃는 비율. 회생제동으로 일부를 돌려받는 것을 감안했다. */
        const val LOSS_FACTOR = 0.8
    }
}
