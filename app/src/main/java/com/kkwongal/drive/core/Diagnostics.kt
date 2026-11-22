package com.kkwongal.drive.core

import com.kkwongal.drive.rust.RustBridge
import java.io.Closeable

enum class Channel(val label: String, val effect: String) {
    SPEED("속도", "점수와 주행 거리를 계산할 수 없어요"),
    SPEED_LIMIT("도로 제한속도", "도로 종류를 평균 속도로 추정하고, 시속 110km 를 넘을 때만 고속 주행으로 봐요"),
    GEAR("기어 (P·D)", "속도로 출발·주차를 판단해요 (2분 넘게 멈추면 주차로 봐요)"),
    STEERING("핸들 각도", "급조향은 빼고 급출발·급정거로만 점수를 매겨요"),
    BRAKE("브레이크 페달", "페달 신호 없이 감속 정도만으로 급정거를 판단해요"),
    LOCATION("현재 위치", "여행 지도에 알을 놓을 수 없어요"),
    ARRIVAL("목적지 도착", "알 이름을 목적지 대신 주소로 채워요"),
    ENERGY("연료·배터리", "실제 연비 대신 속도 패턴으로 추정한 에코 점수만 써요"),
    EFFICIENCY("차량 계산 연비·전비", "짧은 주행에서는 실제 연비를 재지 못할 수 있어요"),
    TRIP("차량 트립 정보", "앱이 직접 잰 거리·시간만 써요"),
    UX("주행 중 화면 제한", "차량의 주행 중 화면 제한 상태를 알 수 없어요"),
}

enum class ChannelStatus { WAITING, OK, UNAVAILABLE }

data class ChannelState(
    val status: ChannelStatus,
    val detail: String,
    val updatedAtMs: Long? = null,
)

/**
 * 연결 점검판. 상태 관리는 Rust 네이티브 핸들(kkwongal-core::diagnostics::DiagnosticsBoard)에서 수행한다.
 */
class DiagnosticsBoard(
    private val clockMs: () -> Long,
    minIntervalMs: Long = 1_000L,
    private val onChange: (Map<Channel, ChannelState>) -> Unit,
) : Closeable {
    private var handle: Long = RustBridge.nativeDiagNew(minIntervalMs)

    val current: Map<Channel, ChannelState>
        @Synchronized get() = RustBridge.parseDiagnostics(RustBridge.nativeDiagCurrent(handle))

    @Synchronized
    fun ok(channel: Channel, detail: String) = set(channel, ChannelStatus.OK, detail)

    @Synchronized
    fun waiting(channel: Channel, detail: String) = set(channel, ChannelStatus.WAITING, detail)

    @Synchronized
    fun unavailable(channel: Channel, reason: String) = set(channel, ChannelStatus.UNAVAILABLE, reason)

    private fun set(channel: Channel, status: ChannelStatus, detail: String) {
        val updatedJson = RustBridge.nativeDiagSet(handle, channel.name, status.name, detail, clockMs())
        if (updatedJson != null) {
            onChange(RustBridge.parseDiagnostics(updatedJson))
        }
    }

    override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            RustBridge.nativeDiagFree(h)
        }
    }

    @Suppress("removal")
    protected fun finalize() {
        close()
    }
}

/**
 * 기어 값을 받을 수 없을 때 속도로 주행 상태를 정한다 (Rust 네이티브 핸들).
 */
class SpeedDriveDetector(
    startKmh: Double = 5.0,
    stopKmh: Double = 1.0,
    parkAfterMs: Long = 120_000L,
) : Closeable {
    private var handle: Long = RustBridge.nativeSpeedDetectorNew(startKmh, stopKmh, parkAfterMs)

    enum class DriveMode { UNKNOWN, PARKED, DRIVING }

    @Synchronized
    fun onSpeed(timeMs: Long, kmh: Double): DriveMode =
        RustBridge.parseDriveMode(RustBridge.nativeSpeedDetectorOnSpeed(handle, timeMs, kmh))

    @Synchronized
    fun onTick(timeMs: Long): DriveMode =
        RustBridge.parseDriveMode(RustBridge.nativeSpeedDetectorOnTick(handle, timeMs))

    override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            RustBridge.nativeSpeedDetectorFree(h)
        }
    }

    @Suppress("removal")
    protected fun finalize() {
        close()
    }
}
