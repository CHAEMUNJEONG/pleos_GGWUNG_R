package com.kkwongal.drive.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import android.content.Context
import android.util.Log
import com.kkwongal.drive.core.Channel
import com.kkwongal.drive.core.EnergySnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Pleos Vehicle SDK (2.3.2) 연결. 이 앱이 쓰는 Pleos 차량 API 는 모두 이 파일에 있다.
 *
 * - 지원 여부 확인: check...Capabilities 로 이 차량이 각 값을 주는지 먼저 확인한다 (차종마다 다르다).
 * - 바퀴 조향각: registerSteeringWheelAngle 리스너로 받는다 (급조향 감지). 리스너가 안 되면 주기적으로 읽는다.
 * - 브레이크 페달: registerBrakePedalStatus 리스너로 받는다 (급정거를 "브레이크를 밟으며 감속"으로 판정).
 * - 에너지: 연료 잔량(%), 전기차 배터리 잔량(Wh), 누적 주행거리(km), 차량이 계산한 이번 주행 전비·연비.
 * - 트립: 차량이 잰 이번 주행 거리(m)·시간(분). 연결 점검 화면에 보여 준다.
 *
 * 읽을 수 없는 값은 null 로 두고 나머지 기능은 그대로 동작한다.
 */
class PleosVehicle(
    context: Context,
    private val scope: CoroutineScope,
    private val isDriving: () -> Boolean,
    /** 핸들 회전 각도(도)로 환산한 값. */
    private val onAngle: (Double) -> Unit,
    private val onBrake: (Boolean) -> Unit,
    /** 데이터 종류별 연결 상태: (종류, 받을 수 있는지(null = 아직 모름), 설명). */
    private val onChannel: (Channel, Boolean?, String) -> Unit,
) {
    private val vehicle = Vehicle(context.applicationContext)
    private var job: Job? = null
    @Volatile private var initialized = false
    @Volatile private var steerFailures = 0
    @Volatile private var steerReceived = false
    @Volatile private var steerListenerActive = false
    @Volatile private var brakeListenerActive = false

    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) {
            if (angle != null) onSteering(angle.toDouble())
        }

        override fun onFailed(e: Exception) {
            steerFailed(e)
        }
    }

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) {
            if (status == null) return
            onBrake(status)
            onChannel(Channel.BRAKE, true, if (status) "밟는 중" else "떼고 있음")
        }

        override fun onFailed(e: Exception) {
            Log.w(TAG, "브레이크 페달 상태 읽기 실패", e)
            onChannel(Channel.BRAKE, false, "브레이크 페달 상태를 받을 수 없어요")
        }
    }

    fun start() {
        if (job != null) return
        initialized = runCatching { vehicle.initialize() }
            .onFailure { Log.w(TAG, "Vehicle SDK 초기화 실패", it) }
            .isSuccess
        if (!initialized) {
            listOf(Channel.STEERING, Channel.BRAKE, Channel.EFFICIENCY, Channel.TRIP).forEach {
                onChannel(it, false, "Pleos Vehicle SDK 를 시작하지 못했어요")
            }
            return
        }
        job = scope.launch {
            delay(INIT_WAIT_MS) // initialize() 가 끝나도 서비스 연결은 조금 늦게 끝난다
            checkCapabilities()
            registerListeners()
            readTripInfo()
            // 리스너를 쓸 수 없을 때만 주행 중에 조향각을 직접 읽는다
            while (isActive && !steerListenerActive && steerFailures < MAX_FAILURES) {
                if (isDriving()) readSteeringOnce()
                delay(STEER_POLL_MS)
            }
            if (steerFailures >= MAX_FAILURES) onChannel(Channel.STEERING, false, "조향각을 받을 수 없어요")
        }
    }

    /** 이 차량이 각 API 를 지원하는지 확인한다. 지원하지 않는 항목은 점검 화면에 바로 표시한다. */
    private suspend fun checkCapabilities() {
        val steering = capability { ok, fail ->
            vehicle.getSteeringWheel().checkSteeringWheelAngleCapabilities({ config, _ -> ok(config) }, fail)
        }
        val brake = capability { ok, fail -> vehicle.getBrake().checkBrakePedalStatusCapabilities(ok, fail) }
        val evEfficiency = capability { ok, fail -> vehicle.getEvBattery().checkCurrentEvEfficiencyCapabilities(ok, fail) }
        val fuelEconomy = capability { ok, fail -> vehicle.getFuel().checkCurrentFuelEconomyCapabilities(ok, fail) }
        val tripDistance = capability { ok, fail -> vehicle.getOdometer().checkTripCurrentDrivingDistanceCapabilities(ok, fail) }

        report(Channel.STEERING, steering, "지원됨 · 주행을 시작하면 받아요")
        report(Channel.BRAKE, brake, "지원됨 · 페달을 밟으면 받아요")
        val efficiencyReadable = listOf(evEfficiency, fuelEconomy).any { it == true }
        val efficiencyKnown = listOf(evEfficiency, fuelEconomy).any { it != null }
        report(
            Channel.EFFICIENCY,
            if (efficiencyReadable) true else if (efficiencyKnown) false else null,
            buildList {
                if (evEfficiency == true) add("전비")
                if (fuelEconomy == true) add("연비")
            }.joinToString("·").ifBlank { "확인 중" } + " 지원됨 · 주행이 끝나면 읽어요",
        )
        report(Channel.TRIP, tripDistance, "지원됨")
    }

    private fun report(channel: Channel, readable: Boolean?, okText: String) {
        when (readable) {
            true -> onChannel(channel, null, okText)
            false -> onChannel(channel, false, "이 차량은 지원하지 않아요 (Pleos 지원 여부 확인 결과)")
            null -> Unit // 확인 자체가 실패: 실제로 값을 읽어 보고 판단한다
        }
    }

    private fun registerListeners() {
        steerListenerActive = runCatching { vehicle.getSteeringWheel().registerSteeringWheelAngle(steeringListener) }
            .onFailure { Log.w(TAG, "조향각 리스너 등록 실패: 주기적으로 읽습니다", it) }
            .isSuccess
        brakeListenerActive = runCatching { vehicle.getBrake().registerBrakePedalStatus(brakeListener) }
            .onFailure {
                Log.w(TAG, "브레이크 리스너 등록 실패", it)
                onChannel(Channel.BRAKE, false, "브레이크 페달 상태를 받을 수 없어요")
            }
            .isSuccess
    }

    /** 차량이 잰 이번 주행 거리·시간. 연결 점검 화면에서 다시 읽을 수 있다. */
    suspend fun readTripInfo() {
        if (!initialized) return
        val meters = await<Float> { ok, fail -> vehicle.getOdometer().getTripCurrentDrivingDistance(ok, fail) }
        val minutes = await<Int> { ok, fail -> vehicle.getOdometer().getTripCurrentDrivingTime(ok, fail) }
        if (meters == null && minutes == null) return
        onChannel(
            Channel.TRIP,
            true,
            buildList {
                meters?.let { add(String.format(Locale.US, "이번 주행 %.1f km", it / 1000.0)) }
                minutes?.let { add("${it}분") }
            }.joinToString(" · "),
        )
    }

    private fun readSteeringOnce() {
        runCatching {
            vehicle.getSteeringWheel().getCurrentSteeringWheelAngle(
                { value -> if (value != null) onSteering(value.toDouble()) },
                { error -> steerFailed(error) },
            )
        }.onFailure { steerFailed(it) }
    }

    /**
     * Pleos 가 주는 값은 핸들 회전각이 아니라 앞바퀴 조향각이다 (SDK 설명).
     * 급조향 기준은 핸들 회전 속도로 정해 두었으므로, 일반 승용차의 조향비로 환산해 넘긴다.
     */
    private fun onSteering(frontWheelDeg: Double) {
        steerFailures = 0
        if (!steerReceived) steerReceived = true
        onAngle(frontWheelDeg * STEERING_RATIO)
        onChannel(Channel.STEERING, true, String.format(Locale.US, "앞바퀴 %.1f° (핸들 약 %.0f°)", frontWheelDeg, frontWheelDeg * STEERING_RATIO))
    }

    private fun steerFailed(error: Any?) {
        steerFailures++
        if (steerFailures == 1) Log.w(TAG, "조향각 읽기 실패: $error")
    }

    /** 연료·배터리 잔량, 누적 주행거리, 차량이 계산한 이번 주행 전비·연비. 탱크 용량은 차량 표준 속성에서 받는다. */
    suspend fun readEnergy(fuelCapacityL: Double?): EnergySnapshot? {
        if (!initialized) return null
        return coroutineScope {
            val fuel = async { await<Int> { ok, fail -> vehicle.getFuel().getFuelPercent(ok, fail) } }
            val level = async { await<Float> { ok, fail -> vehicle.getEvBattery().getEvBatteryLevel(ok, fail) } }
            val odometer = async { await<Float> { ok, fail -> vehicle.getOdometer().getCurrentDistanceDriven(ok, fail) } }
            val evEfficiency = async { await<Float> { ok, fail -> vehicle.getEvBattery().getCurrentEvEfficiency(ok, fail) } }
            // 연비는 L/100km 로 오므로 km/L 로 바꾼다
            val fuelEconomy = async { await<Float> { ok, fail -> vehicle.getFuel().getCurrentFuelEconomy(ok, fail) } }
            val snapshot = EnergySnapshot(
                evBatteryWh = level.await()?.toDouble()?.takeIf { it > 0 },
                fuelPercent = fuel.await()?.toDouble(),
                fuelCapacityL = fuelCapacityL,
                odometerKm = odometer.await()?.toDouble(),
                vehicleEvKmPerKwh = evEfficiency.await()?.toDouble()?.takeIf { it > 0 },
                vehicleFuelKmPerL = fuelEconomy.await()?.toDouble()?.takeIf { it > 0 }?.let { 100.0 / it },
            )
            val ev = snapshot.vehicleEvKmPerKwh
            val ice = snapshot.vehicleFuelKmPerL
            when {
                ev != null -> onChannel(Channel.EFFICIENCY, true, String.format(Locale.US, "이번 주행 전비 %.1f km/kWh", ev))
                ice != null -> onChannel(Channel.EFFICIENCY, true, String.format(Locale.US, "이번 주행 연비 %.1f km/L", ice))
            }
            readTripInfo()
            val anything = snapshot.evBatteryWh ?: snapshot.fuelPercent ?: snapshot.vehicleEvKmPerKwh ?: snapshot.vehicleFuelKmPerL
            if (anything == null) null else snapshot
        }
    }

    /** check...Capabilities 결과: 읽을 수 있으면 true, 지원하지 않으면 false, 확인 실패면 null. */
    private suspend fun capability(call: ((CapabilityConfig) -> Unit, (Exception) -> Unit) -> Unit): Boolean? =
        await<CapabilityConfig> { ok, fail -> call({ ok(it) }, fail) }?.let {
            it.accessType == AccessType.READ || it.accessType == AccessType.READ_WRITE
        }

    /** Vehicle SDK 의 콜백 함수를 기다린다. 실패하거나 [READ_TIMEOUT_MS] 안에 답이 없으면 null. */
    private suspend fun <T> await(call: ((T?) -> Unit, (Exception) -> Unit) -> Unit): T? =
        withTimeoutOrNull(READ_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    call(
                        { value -> if (cont.isActive) cont.resume(value) },
                        { error ->
                            Log.w(TAG, "값 읽기 실패: $error")
                            if (cont.isActive) cont.resume(null)
                        },
                    )
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }

    fun stop() {
        job?.cancel()
        job = null
        if (steerListenerActive) runCatching { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener) }
        if (brakeListenerActive) runCatching { vehicle.getBrake().unregisterBrakePedalStatus(brakeListener) }
        steerListenerActive = false
        brakeListenerActive = false
        runCatching { vehicle.release() }
    }

    private companion object {
        const val TAG = "PleosVehicle"
        const val STEER_POLL_MS = 200L
        const val INIT_WAIT_MS = 1_000L
        const val READ_TIMEOUT_MS = 2_000L
        const val MAX_FAILURES = 10

        /** 핸들 회전각 : 앞바퀴 조향각. 일반 승용차는 대략 14~16 : 1. */
        const val STEERING_RATIO = 15.0
    }
}
