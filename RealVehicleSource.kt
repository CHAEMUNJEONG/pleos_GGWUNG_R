package com.kkwongal.drive.vehicle

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.kkwongal.drive.core.Channel
import com.kkwongal.drive.core.ChannelState
import com.kkwongal.drive.core.ChannelStatus
import com.kkwongal.drive.core.DiagnosticsBoard
import com.kkwongal.drive.core.EnergySnapshot
import com.kkwongal.drive.core.GeoPoint
import com.kkwongal.drive.core.SpeedDriveDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 실제 차량(또는 Pleos Connect 에뮬레이터)에서 데이터를 읽는다.
 *
 * - 속도: 안드로이드 차량용 표준 PERF_VEHICLE_SPEED (단위 m/s → km/h 로 변환)
 * - 기어: 안드로이드 차량용 표준 GEAR_SELECTION (P 단이면 주차)
 * - 화면 제한: CarUxRestrictionsManager
 * - 위치·도착: Pleos NaviHelper (PleosNavi.kt)
 * - 핸들 각도·연료·배터리: Pleos Vehicle SDK (PleosVehicle.kt)
 *
 * 값마다 연결 상태를 [diagnostics] 에 기록한다. 기어 값을 받을 수 없으면 속도로 주행 상태를 정한다.
 */
class RealVehicleSource(context: Context) : VehicleSource {

    private val appContext = context.applicationContext

    private val _driveState = MutableStateFlow(DriveState.UNKNOWN)
    private val _speedKmh = MutableStateFlow<Double?>(null)
    private val _speedSamples = MutableSharedFlow<SpeedSample>(extraBufferCapacity = 64)
    private val _steeringSamples = MutableSharedFlow<SteeringSample>(extraBufferCapacity = 64)
    private val _brakeSamples = MutableSharedFlow<BrakeSample>(extraBufferCapacity = 16)
    private val _location = MutableStateFlow<GeoPoint?>(null)
    private val _address = MutableStateFlow<String?>(null)
    private val _arrivedPlace = MutableStateFlow<ArrivedPlace?>(null)
    private val _uxRestricted = MutableStateFlow(true) // 확인 전에는 제한된 것으로 본다
    private val _status = MutableStateFlow("차량 연결 중")
    private val _diagnostics = MutableStateFlow<Map<Channel, ChannelState>>(emptyMap())
    private val board = DiagnosticsBoard(clockMs = { System.currentTimeMillis() }) { _diagnostics.value = it }

    /** 기어 값을 한 번이라도 받았는지. 못 받으면 [gearFallback] 으로 주행 상태를 정한다. */
    @Volatile private var gearReceived = false
    @Volatile private var useSpeedForDriveState = false
    private val gearFallback = SpeedDriveDetector()

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
        _diagnostics.value = board.current
    }

    private var car: Car? = null
    private var scope: CoroutineScope? = null
    private var pleos: PleosVehicle? = null
    private var propertyManager: CarPropertyManager? = null
    private var uxManager: CarUxRestrictionsManager? = null

    private val navi = PleosNavi(
        context = appContext,
        onLocation = { point, address ->
            _location.value = point
            if (address.isNotBlank()) _address.value = address
            board.ok(Channel.LOCATION, address.ifBlank { String.format(java.util.Locale.US, "%.4f, %.4f", point.latitude, point.longitude) })
        },
        onArrived = { name ->
            Log.i(TAG, "목적지 도착: $name")
            if (name.isNotBlank()) _arrivedPlace.value = ArrivedPlace(name, System.currentTimeMillis())
            board.ok(Channel.ARRIVAL, name.ifBlank { "이름 없는 목적지" })
        },
        onStatus = { message ->
            _status.value = message
            if (message.contains("실패")) {
                board.unavailable(Channel.LOCATION, message)
                board.unavailable(Channel.ARRIVAL, message)
            } else {
                // 이미 값을 받은 항목은 그대로 둔다
                if (board.current[Channel.LOCATION]?.status != ChannelStatus.OK) {
                    board.waiting(Channel.LOCATION, "내비 연결됨 · 위치 기다리는 중")
                }
                if (board.current[Channel.ARRIVAL]?.status != ChannelStatus.OK) {
                    board.waiting(Channel.ARRIVAL, "내비 연결됨 · 목적지에 도착하면 받아요")
                }
            }
        },
    )

    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val metersPerSecond = (value.value as? Float) ?: return
            // 후진 시 음수가 올 수 있어 크기만 쓴다
            val kmh = kotlin.math.abs(metersPerSecond) * 3.6
            val now = SystemClock.elapsedRealtime()
            _speedKmh.value = kmh
            _speedSamples.tryEmit(SpeedSample(now, kmh))
            board.ok(Channel.SPEED, String.format(java.util.Locale.US, "%.0f km/h", kmh))
            if (useSpeedForDriveState) applyFallback(gearFallback.onSpeed(now, kmh))
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.w(TAG, "속도 값 오류: $propertyId/$areaId")
        }
    }

    private val gearCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            applyGear(value.value as? Int)
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.w(TAG, "기어 값 오류: $propertyId/$areaId")
        }
    }

    private fun applyGear(gear: Int?) {
        if (gear == null || gear == VehicleGear.GEAR_UNKNOWN) return
        gearReceived = true
        if (useSpeedForDriveState) {
            // 늦게라도 기어 값이 오면 기어 기준으로 돌아간다
            useSpeedForDriveState = false
            Log.i(TAG, "기어 값 수신: 기어 기준으로 주행 상태를 정합니다")
        }
        _driveState.value = if (gear == VehicleGear.GEAR_PARK) DriveState.PARKED else DriveState.DRIVING
        board.ok(Channel.GEAR, gearLabel(gear))
    }

    private fun gearLabel(gear: Int): String = when (gear) {
        VehicleGear.GEAR_PARK -> "P (주차)"
        VehicleGear.GEAR_REVERSE -> "R (후진)"
        VehicleGear.GEAR_NEUTRAL -> "N (중립)"
        VehicleGear.GEAR_DRIVE -> "D (주행)"
        else -> "주행 기어 ($gear)"
    }

    private fun applyFallback(mode: SpeedDriveDetector.DriveMode) {
        val state = when (mode) {
            SpeedDriveDetector.DriveMode.UNKNOWN -> return
            SpeedDriveDetector.DriveMode.PARKED -> DriveState.PARKED
            SpeedDriveDetector.DriveMode.DRIVING -> DriveState.DRIVING
        }
        if (_driveState.value != state) _driveState.value = state
    }

    /**
     * 차량용 API 가 없는 기기(일반 휴대폰)에서는 여기서 오류가 난다.
     * 호출하는 쪽에서 잡아 시뮬레이션 모드로 바꾼다.
     */
    override fun start() {
        if (car != null) return
        val c = Car.createCar(appContext) ?: error("차량 서비스에 연결할 수 없습니다")
        car = c
        val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        propertyManager = pm

        val speedOk = runCatching {
            pm.registerCallback(
                speedCallback,
                VehiclePropertyIds.PERF_VEHICLE_SPEED,
                CarPropertyManager.SENSOR_RATE_NORMAL,
            )
        }.getOrDefault(false)

        val gearOk = runCatching {
            pm.registerCallback(
                gearCallback,
                VehiclePropertyIds.GEAR_SELECTION,
                CarPropertyManager.SENSOR_RATE_ONCHANGE,
            )
        }.getOrDefault(false)

        runCatching {
            val ux = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            uxManager = ux
            val restricted = ux.currentCarUxRestrictions?.isRequiresDistractionOptimization ?: true
            _uxRestricted.value = restricted
            board.ok(Channel.UX, if (restricted) "제한 중" else "제한 없음")
            ux.registerListener { restrictions ->
                _uxRestricted.value = restrictions.isRequiresDistractionOptimization
                board.ok(Channel.UX, if (restrictions.isRequiresDistractionOptimization) "제한 중" else "제한 없음")
            }
        }.onFailure {
            Log.w(TAG, "화면 제한 상태를 읽지 못함", it)
            board.unavailable(Channel.UX, "화면 제한 상태를 읽을 수 없어요")
        }

        _status.value = buildString {
            append(if (speedOk) "속도 연결됨" else "속도 읽기 불가(권한 확인)")
            append(" · ")
            append(if (gearOk) "기어 연결됨" else "기어 읽기 불가")
        }
        if (!speedOk) board.unavailable(Channel.SPEED, "속도 값을 받을 수 없어요 (권한 확인)")
        if (!gearOk) board.unavailable(Channel.GEAR, "기어 값을 받을 수 없어 속도로 판단해요")

        navi.start()

        // 핸들 각도: Pleos Vehicle SDK. 읽을 수 없어도 나머지 기능은 그대로 동작한다.
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        pleos = PleosVehicle(
            context = appContext,
            scope = s,
            isDriving = { _driveState.value == DriveState.DRIVING },
            onAngle = { angle -> _steeringSamples.tryEmit(SteeringSample(SystemClock.elapsedRealtime(), angle)) },
            onBrake = { pressed -> _brakeSamples.tryEmit(BrakeSample(SystemClock.elapsedRealtime(), pressed)) },
            onChannel = { channel, readable, detail ->
                when (readable) {
                    true -> board.ok(channel, detail)
                    false -> board.unavailable(channel, detail)
                    null -> if (board.current[channel]?.status != ChannelStatus.OK) board.waiting(channel, detail)
                }
            },
        )
        board.waiting(Channel.STEERING, "Pleos 지원 여부 확인 중")
        board.waiting(Channel.BRAKE, "Pleos 지원 여부 확인 중")
        board.waiting(Channel.EFFICIENCY, "Pleos 지원 여부 확인 중")
        board.waiting(Channel.TRIP, "Pleos 지원 여부 확인 중")
        board.waiting(Channel.ENERGY, "주행을 시작하면 읽어요")
        pleos?.start()

        // 기어 값이 일정 시간 안에 안 오면 속도로 주행 상태를 정한다.
        // 멈춰 있을 때 속도 값이 안 오는 차량도 있어 주기적으로 확인한다.
        s.launch {
            delay(GEAR_WAIT_MS)
            if (!gearReceived) {
                useSpeedForDriveState = true
                board.unavailable(Channel.GEAR, "기어 값을 받지 못해 속도로 출발·주차를 판단해요")
                Log.w(TAG, "기어 값 없음: 속도로 주행 상태를 정합니다")
            }
            while (isActive) {
                if (useSpeedForDriveState) applyFallback(gearFallback.onTick(SystemClock.elapsedRealtime()))
                delay(FALLBACK_TICK_MS)
            }
        }
    }

    /** 연료 탱크 용량 (L). 안드로이드 차량용 표준 속성 INFO_FUEL_CAPACITY (mL). 전기차는 0. */
    private fun fuelCapacityL(): Double? = runCatching {
        propertyManager?.getFloatProperty(VehiclePropertyIds.INFO_FUEL_CAPACITY, 0)?.toDouble()?.div(1000.0)
    }.getOrNull()?.takeIf { it > 0 }

    override fun requestRoute(id: String, name: String, point: GeoPoint, address: String): Boolean =
        navi.requestRoute(id, name, point.latitude, point.longitude, address)

    override suspend fun readEnergy(): EnergySnapshot? {
        val snapshot = pleos?.readEnergy(fuelCapacityL())
        if (snapshot == null) {
            board.unavailable(Channel.ENERGY, "연료·배터리 잔량을 받을 수 없어요")
        } else {
            board.ok(Channel.ENERGY, energyText(snapshot))
        }
        return snapshot
    }

    override fun stop() {
        pleos?.stop()
        pleos = null
        scope?.cancel()
        scope = null
        navi.stop()
        runCatching {
            propertyManager?.unregisterCallback(speedCallback)
            propertyManager?.unregisterCallback(gearCallback)
            uxManager?.unregisterListener()
            car?.disconnect()
        }
        propertyManager = null
        uxManager = null
        car = null
    }

    private companion object {
        const val TAG = "RealVehicleSource"

        /** 시작 후 이 시간 안에 기어 값이 안 오면 속도로 주행 상태를 정한다. */
        const val GEAR_WAIT_MS = 10_000L
        const val FALLBACK_TICK_MS = 5_000L
    }
}
