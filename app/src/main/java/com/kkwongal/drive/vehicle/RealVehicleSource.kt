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
import com.kkwongal.drive.core.GeoPoint
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
 */
class RealVehicleSource(context: Context) : VehicleSource {

    private val appContext = context.applicationContext

    private val _driveState = MutableStateFlow(DriveState.UNKNOWN)
    private val _speedKmh = MutableStateFlow<Double?>(null)
    private val _speedSamples = MutableSharedFlow<SpeedSample>(extraBufferCapacity = 64)
    private val _location = MutableStateFlow<GeoPoint?>(null)
    private val _uxRestricted = MutableStateFlow(true) // 확인 전에는 제한된 것으로 본다
    private val _status = MutableStateFlow("차량 연결 중")

    override val driveState: StateFlow<DriveState> = _driveState.asStateFlow()
    override val speedKmh: StateFlow<Double?> = _speedKmh.asStateFlow()
    override val speedSamples: SharedFlow<SpeedSample> = _speedSamples.asSharedFlow()
    override val location: StateFlow<GeoPoint?> = _location.asStateFlow()
    override val uxRestricted: StateFlow<Boolean> = _uxRestricted.asStateFlow()
    override val status: StateFlow<String> = _status.asStateFlow()

    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var uxManager: CarUxRestrictionsManager? = null

    private val navi = PleosNavi(
        context = appContext,
        onLocation = { _location.value = it },
        onArrived = { Log.i(TAG, "목적지 도착") },
        onStatus = { _status.value = it },
    )

    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val metersPerSecond = (value.value as? Float) ?: return
            // 후진 시 음수가 올 수 있어 크기만 쓴다
            val kmh = kotlin.math.abs(metersPerSecond) * 3.6
            _speedKmh.value = kmh
            _speedSamples.tryEmit(SpeedSample(SystemClock.elapsedRealtime(), kmh))
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
        _driveState.value = when (gear) {
            null, VehicleGear.GEAR_UNKNOWN -> DriveState.UNKNOWN
            VehicleGear.GEAR_PARK -> DriveState.PARKED
            else -> DriveState.DRIVING
        }
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
            _uxRestricted.value = ux.currentCarUxRestrictions?.isRequiresDistractionOptimization ?: true
            ux.registerListener { restrictions ->
                _uxRestricted.value = restrictions.isRequiresDistractionOptimization
            }
        }.onFailure { Log.w(TAG, "화면 제한 상태를 읽지 못함", it) }

        _status.value = buildString {
            append(if (speedOk) "속도 연결됨" else "속도 읽기 불가(권한 확인)")
            append(" · ")
            append(if (gearOk) "기어 연결됨" else "기어 읽기 불가")
        }

        navi.start()
    }

    override fun stop() {
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
    }
}
