package com.kkwongal.drive.vehicle

import com.kkwongal.drive.core.Channel
import com.kkwongal.drive.core.ChannelState
import com.kkwongal.drive.core.EnergySnapshot
import com.kkwongal.drive.core.GeoPoint
import com.kkwongal.drive.rust.RustBridge
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
 * 차 없이 앱을 돌려 보기 위한 시뮬레이션 차량.
 * 가상 전기차 물리 모델·5배속 시계·브레이크/조향 타이머·시연 시나리오는 모두
 * Rust 네이티브 핸들(kkwongal-core::fake_vehicle::FakeVehicleEngine)에서 실행된다.
 */
class FakeVehicleSource(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val tickMs: Long = 200L,
) : VehicleSource {

    private var handle: Long = RustBridge.nativeFakeNew(clockMs(), System.currentTimeMillis())

    private val _driveState = MutableStateFlow(DriveState.PARKED)
    private val _speedKmh = MutableStateFlow<Double?>(0.0)
    private val _speedSamples = MutableSharedFlow<SpeedSample>(extraBufferCapacity = 64)
    private val _steeringSamples = MutableSharedFlow<SteeringSample>(extraBufferCapacity = 64)
    private val _brakeSamples = MutableSharedFlow<BrakeSample>(extraBufferCapacity = 16)
    private val _location = MutableStateFlow<GeoPoint?>(null)
    private val _address = MutableStateFlow<String?>(null)
    private val _addressUpdatedAtMs = MutableStateFlow<Long?>(null)
    private val _arrivedPlace = MutableStateFlow<ArrivedPlace?>(null)
    private val _speedLimitSamples = MutableSharedFlow<SpeedLimitSample>(extraBufferCapacity = 64)
    private val _uxRestricted = MutableStateFlow(false)
    private val _status = MutableStateFlow("시뮬레이션 모드")
    private val _diagnostics = MutableStateFlow<Map<Channel, ChannelState>>(emptyMap())

    override val driveState: StateFlow<DriveState> = _driveState.asStateFlow()
    override val speedKmh: StateFlow<Double?> = _speedKmh.asStateFlow()
    override val speedSamples: SharedFlow<SpeedSample> = _speedSamples.asSharedFlow()
    override val steeringSamples: SharedFlow<SteeringSample> = _steeringSamples.asSharedFlow()
    override val brakeSamples: SharedFlow<BrakeSample> = _brakeSamples.asSharedFlow()
    override val location: StateFlow<GeoPoint?> = _location.asStateFlow()
    override val address: StateFlow<String?> = _address.asStateFlow()
    override val addressUpdatedAtMs: StateFlow<Long?> = _addressUpdatedAtMs.asStateFlow()
    override val arrivedPlace: StateFlow<ArrivedPlace?> = _arrivedPlace.asStateFlow()
    override val speedLimitSamples: SharedFlow<SpeedLimitSample> = _speedLimitSamples.asSharedFlow()
    override val uxRestricted: StateFlow<Boolean> = _uxRestricted.asStateFlow()
    override val status: StateFlow<String> = _status.asStateFlow()
    override val diagnostics: StateFlow<Map<Channel, ChannelState>> = _diagnostics.asStateFlow()

    init {
        applyControl(RustBridge.parseFakeControl(RustBridge.nativeFakeCurrentState(handle)))
    }

    private var scope: CoroutineScope? = null
    private var ticker: Job? = null
    @Volatile private var demoActive = false

    @Synchronized
    private fun applyControl(wire: RustBridge.FakeControlWire) {
        wire.brakeEvents.forEach { _brakeSamples.tryEmit(it) }
        _driveState.value = wire.driveState
        _speedKmh.value = wire.speedKmh
        _uxRestricted.value = wire.uxRestricted
        _location.value = wire.location
        _address.value = wire.address
        _addressUpdatedAtMs.value = wire.addressUpdatedAtMs
        _arrivedPlace.value = wire.arrivedPlace
        _diagnostics.value = wire.diagnostics
    }

    @Synchronized
    private fun stepTick() {
        if (handle == 0L) return
        val wire = RustBridge.parseFakeTick(
            RustBridge.nativeFakeTick(handle, clockMs(), System.currentTimeMillis()),
        )
        wire.brakeEvents.forEach { _brakeSamples.tryEmit(it) }
        _speedSamples.tryEmit(wire.speedSample)
        _steeringSamples.tryEmit(wire.steeringSample)
        _speedLimitSamples.tryEmit(wire.speedLimitSample)
        _driveState.value = wire.driveState
        _speedKmh.value = wire.speedKmh
        _uxRestricted.value = wire.uxRestricted
        _location.value = wire.location
        _address.value = wire.address
        _addressUpdatedAtMs.value = wire.addressUpdatedAtMs
        _arrivedPlace.value = wire.arrivedPlace
        _diagnostics.value = wire.diagnostics
    }

    override fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        ticker = s.launch {
            while (isActive) {
                stepTick()
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

    override suspend fun readEnergy(): EnergySnapshot = synchronized(this) {
        val wire = RustBridge.parseFakeEnergy(
            RustBridge.nativeFakeReadEnergy(handle, System.currentTimeMillis()),
        )
        _diagnostics.value = wire.diagnostics
        wire.energy
    }

    override fun requestRoute(id: String, name: String, point: GeoPoint, address: String): Boolean {
        setLocation(point, name, address)
        return true
    }

    @Synchronized
    fun setSpeed(kmh: Double) {
        if (handle == 0L) return
        val wire = RustBridge.parseFakeControl(
            RustBridge.nativeFakeSetSpeed(handle, kmh, clockMs(), System.currentTimeMillis()),
        )
        applyControl(wire)
    }

    @Synchronized
    fun setParked(parked: Boolean) {
        if (handle == 0L) return
        val wire = RustBridge.parseFakeControl(
            RustBridge.nativeFakeSetParked(handle, parked, System.currentTimeMillis()),
        )
        applyControl(wire)
    }

    @Synchronized
    fun setLocation(point: GeoPoint?, name: String? = null, address: String? = null) {
        if (handle == 0L) return
        val wire = RustBridge.parseFakeControl(
            RustBridge.nativeFakeSetLocation(
                handle,
                point != null,
                point?.latitude ?: 0.0,
                point?.longitude ?: 0.0,
                name,
                address,
                System.currentTimeMillis(),
            ),
        )
        applyControl(wire)
    }

    @Synchronized
    fun jerkSteering() {
        if (handle == 0L) return
        val wire = RustBridge.parseFakeControl(
            RustBridge.nativeFakeJerkSteering(handle, clockMs()),
        )
        applyControl(wire)
    }

    suspend fun runDemo(onStep: (String) -> Unit) {
        if (scope == null || demoActive) return
        demoActive = true
        try {
            synchronized(this) {
                applyControl(
                    RustBridge.parseFakeControl(
                        RustBridge.nativeFakeSetDemoActive(handle, true, clockMs(), System.currentTimeMillis()),
                    ),
                )
            }
            val script = RustBridge.parseDemoScript(RustBridge.nativeFakeDemoScriptJson())
            for (action in script) {
                when (action.kind) {
                    "step" -> action.label?.let(onStep)
                    "setLocation" -> setLocation(action.point, action.name, action.address)
                    "setParked" -> setParked(action.parked ?: true)
                    "setSpeed" -> setSpeed(action.kmh ?: 0.0)
                    "rampTo" -> rampTo(action.target ?: 0.0, action.rate ?: 1.0)
                    "hold" -> hold(action.virtualSeconds ?: 1)
                }
            }
        } finally {
            endDemo()
        }
    }

    @Synchronized
    fun endDemo() {
        if (!demoActive) return
        demoActive = false
        if (handle != 0L) {
            applyControl(
                RustBridge.parseFakeControl(
                    RustBridge.nativeFakeSetDemoActive(handle, false, clockMs(), System.currentTimeMillis()),
                ),
            )
        }
    }

    private suspend fun rampTo(target: Double, rate: Double) {
        var speed = _speedKmh.value ?: 0.0
        while (kotlin.math.abs(target - speed) > 0.01) {
            speed = if (target > speed) minOf(target, speed + rate) else maxOf(target, speed - rate)
            setSpeed(speed)
            delay(1_000L / DEMO_SPEED)
        }
    }

    private suspend fun hold(seconds: Int) = delay(seconds * 1_000L / DEMO_SPEED)

    @Suppress("removal")
    protected fun finalize() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            RustBridge.nativeFakeFree(h)
        }
    }

    private companion object {
        const val DEMO_SPEED = 5L
    }
}
