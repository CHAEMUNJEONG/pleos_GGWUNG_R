package com.kkwongal.drive.vehicle

import com.kkwongal.drive.core.Channel
import com.kkwongal.drive.core.ChannelState
import com.kkwongal.drive.core.DiagnosticsBoard
import com.kkwongal.drive.core.EnergySnapshot
import com.kkwongal.drive.core.GeoPoint
import com.kkwongal.drive.core.RoadType
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
    private val _speedLimitSamples = MutableSharedFlow<SpeedLimitSample>(extraBufferCapacity = 64)
    /** 시뮬레이션 도로의 제한속도. 속도에 맞춰 시내(50)·국도(80)·고속도로(100)로 바뀐다. */
    @Volatile private var simLimit = 50
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
    override val speedLimitSamples: SharedFlow<SpeedLimitSample> = _speedLimitSamples.asSharedFlow()
    override val uxRestricted: StateFlow<Boolean> = _uxRestricted.asStateFlow()
    override val status: StateFlow<String> = _status.asStateFlow()
    override val diagnostics: StateFlow<Map<Channel, ChannelState>> = _diagnostics.asStateFlow()

    init {
        board.ok(Channel.GEAR, "P (주차) · 시뮬레이션")
        board.ok(Channel.UX, "제한 없음 · 시뮬레이션")
        board.waiting(Channel.LOCATION, "시뮬레이션 패널에서 목적지를 고르세요")
        board.waiting(Channel.ARRIVAL, "목적지를 고르고 주차하면 받아요")
        board.waiting(Channel.ENERGY, "주행을 시작하면 읽어요")
        board.waiting(Channel.BRAKE, "−30 버튼은 브레이크, 막대로 줄이면 발을 뗀 감속으로 흉내 내요")
        board.waiting(Channel.SPEED_LIMIT, "속도에 맞춰 시내·국도·고속도로 제한속도를 흉내 내요")
        board.ok(Channel.EFFICIENCY, "시뮬레이션에서는 배터리 잔량으로 계산해요")
        board.waiting(Channel.TRIP, "주행을 시작하면 읽어요")
        _diagnostics.value = board.current
    }

    private var scope: CoroutineScope? = null
    private var ticker: Job? = null

    // 시연 모드는 시간을 빠르게 흘려 보낸다. 모든 측정값의 시각은 now() 로 찍으므로
    // 점수 계산은 실제로 그 시간 동안 달린 것과 같다.
    private var timeScale = 1L
    private var anchorReal = clockMs()
    private var anchorVirtual = anchorReal
    @Volatile private var demoActive = false

    /** 측정값에 찍는 시각. 시연 중에는 [DEMO_SPEED]배 빠르게 흐른다. */
    @Synchronized
    private fun now(): Long = anchorVirtual + (clockMs() - anchorReal) * timeScale

    @Synchronized
    private fun setTimeScale(scale: Long) {
        val virtual = now()
        anchorReal = clockMs()
        anchorVirtual = virtual
        timeScale = scale
    }

    override fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        // 실제 차량처럼 일정 간격으로 속도 값을 내보낸다
        ticker = s.launch {
            while (isActive) {
                val t = now()
                val speed = _speedKmh.value ?: 0.0
                consumeEnergy(t, speed)
                _speedSamples.tryEmit(SpeedSample(t, speed))
                _steeringSamples.tryEmit(SteeringSample(t, steeringAngle))
                emitSpeedLimit(t, speed)
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

    /** 멈춰 있을 때는 그 도로에 그대로 있는 것으로 보고, 달리는 속도대에 맞춰 도로를 바꾼다. */
    private fun emitSpeedLimit(now: Long, speed: Double) {
        if (speed >= 1.0) {
            simLimit = when {
                speed <= 50.0 -> 50
                speed <= 90.0 -> 80
                else -> 100
            }
        }
        _speedLimitSamples.tryEmit(SpeedLimitSample(now, simLimit))
        board.ok(Channel.SPEED_LIMIT, "$simLimit km/h · ${RoadType.ofLimit(simLimit).label} · 시뮬레이션")
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
        // 한 번에 크게 줄이면(−30 버튼) 브레이크를 밟았다가 1.5초 뒤 뗀 것으로 흉내 낸다.
        // 막대로 조금씩 줄이면 발을 떼고 미리 감속한 것으로 본다.
        if (target < current - BRAKE_STEP_KMH) {
            pressBrake()
        } else if (target < current - 0.5) {
            _brakeSamples.tryEmit(BrakeSample(now(), false))
        }
        _speedKmh.value = target
    }

    private fun pressBrake() {
        val s = scope ?: return
        _brakeSamples.tryEmit(BrakeSample(now(), true))
        board.ok(Channel.BRAKE, "밟는 중 · 시뮬레이션")
        brakeRelease?.cancel()
        brakeRelease = s.launch {
            delay(1_500)
            _brakeSamples.tryEmit(BrakeSample(now(), false))
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

    /**
     * 시연 시나리오. 발표 때마다 같은 흐름으로 달린다 (5배속, 실제 약 1분 30초).
     * 시내 출발 → 급출발 1회 → 국도 정속 → 고속도로 정속 → 과속 → 미리 감속 → 급정거 1회 → 오래 정차(공회전) → 도착.
     * 리포트의 "다음엔" 조언이 적정 속도(과속)로 나오도록 짜 두었다.
     * [onStep] 으로 지금 단계 설명을 알려 준다. 취소하면 그 자리에서 주차한다.
     */
    suspend fun runDemo(onStep: (String) -> Unit) {
        if (scope == null || demoActive) return
        demoActive = true
        try {
            setTimeScale(DEMO_SPEED)
            val place = DEMO_PLACE
            onStep("강릉 안목해변으로 출발")
            setLocation(place.first, place.second, place.third)
            hold(2)
            setParked(false)
            hold(3)
            onStep("시내 · 부드럽게 출발")
            rampTo(50.0, 2.0)
            hold(20)
            onStep("급출발 한 번 (감점 음성)")
            setSpeed(80.0)
            hold(3)
            onStep("국도 · 정속 주행")
            hold(40)
            onStep("고속도로 · 정속 주행")
            rampTo(100.0, 1.5)
            hold(80)
            // 코칭을 보여 주려고 일부러 아쉬운 습관 하나를 넣는다
            onStep("고속도로 · 제한속도 넘겨 과속")
            rampTo(125.0, 2.0)
            hold(25)
            onStep("앞이 막혀 발을 떼고 미리 감속")
            rampTo(40.0, 2.5)
            onStep("급정거 한 번 (감점 음성)")
            setSpeed(10.0)
            hold(2)
            rampTo(0.0, 3.0)
            onStep("정체로 오래 정차 (공회전)")
            hold(170)
            onStep("도착 · 주차")
            setParked(true)
            hold(2)
        } finally {
            endDemo()
        }
    }

    /** 시연을 끝낸다. 달리던 중이면 그 자리에서 주차한다. */
    fun endDemo() {
        if (!demoActive) return
        demoActive = false
        setTimeScale(1)
        if (_driveState.value == DriveState.DRIVING) setParked(true)
    }

    /** 시연용: 1(가상)초마다 [rate] km/h 씩 [target] 까지 바꾼다. 줄일 때는 발을 뗀 감속이다. */
    private suspend fun rampTo(target: Double, rate: Double) {
        var speed = _speedKmh.value ?: 0.0
        while (kotlin.math.abs(target - speed) > 0.01) {
            speed = if (target > speed) minOf(target, speed + rate) else maxOf(target, speed - rate)
            setSpeed(speed)
            delay(1_000L / DEMO_SPEED)
        }
    }

    /** 시연용: (가상) [seconds]초 동안 그대로 둔다. */
    private suspend fun hold(seconds: Int) = delay(seconds * 1_000L / DEMO_SPEED)

    private companion object {
        /** 시연 모드에서 시간이 흐르는 배수. */
        const val DEMO_SPEED = 5L
        val DEMO_PLACE = Triple(GeoPoint(37.7719, 128.9473), "강릉 안목해변", "강원특별자치도 강릉시 창해로14번길")
        const val BASE_WH_PER_KM = 140.0
        /** 한 번에 이만큼보다 크게 줄이면 브레이크를 밟은 것으로 본다. */
        const val BRAKE_STEP_KMH = 10.0
        const val MASS_KG = 2_000.0
        /** 가속에 쓴 에너지 중 실제로 잃는 비율. 회생제동으로 일부를 돌려받는 것을 감안했다. */
        const val LOSS_FACTOR = 0.8
    }
}
