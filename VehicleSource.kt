package com.kkwongal.drive.vehicle

import com.kkwongal.drive.core.Channel
import com.kkwongal.drive.core.ChannelState
import com.kkwongal.drive.core.EnergySnapshot
import com.kkwongal.drive.core.GeoPoint
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class DriveState { UNKNOWN, PARKED, DRIVING }

/** 속도 측정값 하나. timeMs 는 계속 증가하는 시계의 밀리초. */
data class SpeedSample(val timeMs: Long, val speedKmh: Double)

/** 핸들 각도 측정값 하나 (도). timeMs 는 SpeedSample 과 같은 시계. */
data class SteeringSample(val timeMs: Long, val angleDeg: Double)

/** 브레이크 페달 상태가 바뀐 순간. timeMs 는 속도 값과 같은 시계. */
data class BrakeSample(val timeMs: Long, val pressed: Boolean)

/** 내비가 알려 준 목적지 도착. wallTimeMs 는 실제 시각(System.currentTimeMillis). */
data class ArrivedPlace(val name: String, val wallTimeMs: Long)

/**
 * 차량 데이터가 들어오는 곳. 실제 차량(RealVehicleSource)과
 * 시뮬레이션(FakeVehicleSource)이 같은 모양이라 나머지 코드는 둘을 구분하지 않는다.
 */
interface VehicleSource {
    /** 기어 P 면 PARKED, 그 외 기어면 DRIVING. */
    val driveState: StateFlow<DriveState>

    /** 화면 표시용 현재 속도. 아직 모르면 null. */
    val speedKmh: StateFlow<Double?>

    /** 점수 계산용 속도 측정값. 같은 속도가 이어져도 매번 전달된다. */
    val speedSamples: SharedFlow<SpeedSample>

    /** 핸들 각도 측정값. 읽을 수 없는 차량에서는 아무 값도 오지 않는다. */
    val steeringSamples: SharedFlow<SteeringSample>

    /** 브레이크 페달을 밟고 뗄 때마다 하나씩. 받을 수 없는 차량이면 아무것도 오지 않는다. */
    val brakeSamples: SharedFlow<BrakeSample>

    /** 마지막으로 알려진 위치. 없으면 null. */
    val location: StateFlow<GeoPoint?>

    /** 마지막으로 알려진 현재 위치의 주소. 없으면 null. */
    val address: StateFlow<String?>

    /** 가장 최근 목적지 도착. 스탬프 이름을 자동으로 채우는 데 쓴다. */
    val arrivedPlace: StateFlow<ArrivedPlace?>

    /** 주행 중이라 화면 조작을 막아야 하면 true. */
    val uxRestricted: StateFlow<Boolean>

    /** 연결 상태 안내 문구 (개발 중 확인용). */
    val status: StateFlow<String>

    /** 데이터 종류별 연결 상태. 연결 점검 화면에 쓴다. */
    val diagnostics: StateFlow<Map<Channel, ChannelState>>

    /** 지금의 연료·배터리 잔량과 누적 주행거리를 읽는다. 읽을 수 없으면 null. */
    suspend fun readEnergy(): EnergySnapshot?

    /** 내비에 이 장소로 길 안내를 요청한다. 요청을 보냈으면 true. */
    fun requestRoute(id: String, name: String, point: GeoPoint, address: String): Boolean

    fun start()
    fun stop()
}
