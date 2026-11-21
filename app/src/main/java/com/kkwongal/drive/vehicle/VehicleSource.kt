package com.kkwongal.drive.vehicle

import com.kkwongal.drive.core.GeoPoint
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class DriveState { UNKNOWN, PARKED, DRIVING }

/** 속도 측정값 하나. timeMs 는 계속 증가하는 시계의 밀리초. */
data class SpeedSample(val timeMs: Long, val speedKmh: Double)

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

    /** 마지막으로 알려진 위치. 없으면 null. */
    val location: StateFlow<GeoPoint?>

    /** 주행 중이라 화면 조작을 막아야 하면 true. */
    val uxRestricted: StateFlow<Boolean>

    /** 연결 상태 안내 문구 (개발 중 확인용). */
    val status: StateFlow<String>

    fun start()
    fun stop()
}
