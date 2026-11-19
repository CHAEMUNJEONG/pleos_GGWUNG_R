package com.kkwongal.drive.core

/** 차량에서 받는 데이터 종류. effect 는 그 값을 못 받을 때 앱에서 달라지는 점. */
enum class Channel(val label: String, val effect: String) {
    SPEED("속도", "점수와 주행 거리를 계산할 수 없어요"),
    GEAR("기어 (P·D)", "속도로 출발·주차를 판단해요 (2분 넘게 멈추면 주차로 봐요)"),
    STEERING("핸들 각도", "급조향은 빼고 급출발·급정거로만 점수를 매겨요"),
    BRAKE("브레이크 페달", "페달 신호 없이 감속 정도만으로 급정거를 판단해요"),
    LOCATION("현재 위치", "여행 지도에 알을 놓을 수 없어요"),
    ARRIVAL("목적지 도착", "알 이름을 목적지 대신 주소로 채워요"),
    SPEED_LIMIT("도로 제한속도", "도로 종류를 평균 속도로 추정하고, 시속 110km 를 넘을 때만 고속 주행으로 봐요"),
    ENERGY("연료·배터리", "실제 연비 대신 속도 패턴으로 추정한 에코 점수만 써요"),
    EFFICIENCY("차량 계산 연비·전비", "짧은 주행에서는 실제 연비를 재지 못할 수 있어요"),
    TRIP("차량 트립 정보", "앱이 직접 잰 거리·시간만 써요"),
    UX("주행 중 화면 제한", "차량의 주행 중 화면 제한 상태를 알 수 없어요"),
}

enum class ChannelStatus { WAITING, OK, UNAVAILABLE }

/** 데이터 한 종류의 연결 상태. detail 은 마지막 값이나 못 받는 이유. */
data class ChannelState(
    val status: ChannelStatus,
    val detail: String,
    val updatedAtMs: Long? = null,
)

/**
 * 연결 점검판. 값이 들어올 때마다 기록하되, 같은 상태로 아주 자주 들어오는 값(속도, 핸들)은
 * [minIntervalMs] 에 한 번만 갱신해 화면이 쓸데없이 다시 그려지지 않게 한다.
 * 상태가 바뀔 때(기다림 → 연결됨, 연결됨 → 못 받음)는 바로 갱신한다.
 */
class DiagnosticsBoard(
    private val clockMs: () -> Long,
    private val minIntervalMs: Long = 1_000L,
    private val onChange: (Map<Channel, ChannelState>) -> Unit,
) {
    private val states: MutableMap<Channel, ChannelState> =
        Channel.entries.associateWith { ChannelState(ChannelStatus.WAITING, "확인 중") }.toMutableMap()

    val current: Map<Channel, ChannelState> @Synchronized get() = states.toMap()

    @Synchronized
    fun ok(channel: Channel, detail: String) = set(channel, ChannelStatus.OK, detail)

    @Synchronized
    fun waiting(channel: Channel, detail: String) = set(channel, ChannelStatus.WAITING, detail)

    @Synchronized
    fun unavailable(channel: Channel, reason: String) = set(channel, ChannelStatus.UNAVAILABLE, reason)

    private fun set(channel: Channel, status: ChannelStatus, detail: String) {
        val now = clockMs()
        val old = states.getValue(channel)
        val sameStatus = old.status == status
        if (sameStatus && old.detail == detail) return
        if (sameStatus && status == ChannelStatus.OK && old.updatedAtMs != null && now - old.updatedAtMs < minIntervalMs) return
        states[channel] = ChannelState(status, detail, now)
        onChange(states.toMap())
    }
}

/**
 * 기어 값을 받을 수 없을 때 속도로 주행 상태를 정한다.
 * 시속 [startKmh] 이상이면 출발, 시속 [stopKmh] 이하로 [parkAfterMs] 넘게 멈춰 있으면 주차.
 * 신호 대기처럼 잠깐 서는 것은 주차로 보지 않는다.
 */
class SpeedDriveDetector(
    private val startKmh: Double = 5.0,
    private val stopKmh: Double = 1.0,
    private val parkAfterMs: Long = 120_000L,
) {
    private var state = DriveMode.UNKNOWN
    private var stoppedSinceMs: Long? = null
    private var lastKmh = 0.0

    enum class DriveMode { UNKNOWN, PARKED, DRIVING }

    /** 속도 값이 들어올 때. 바뀐 주행 상태를 돌려준다. */
    @Synchronized
    fun onSpeed(timeMs: Long, kmh: Double): DriveMode {
        lastKmh = kmh
        when {
            kmh >= startKmh -> {
                state = DriveMode.DRIVING
                stoppedSinceMs = null
            }
            kmh <= stopKmh -> {
                val since = stoppedSinceMs ?: timeMs.also { stoppedSinceMs = it }
                if (state != DriveMode.PARKED && timeMs - since >= parkAfterMs) state = DriveMode.PARKED
            }
            else -> stoppedSinceMs = null // 천천히 움직이는 중: 상태는 그대로
        }
        return state
    }

    /** 속도 값이 한동안 안 올 때(멈춰 있으면 값이 안 오는 차량) 주기적으로 부른다. */
    @Synchronized
    fun onTick(timeMs: Long): DriveMode = onSpeed(timeMs, lastKmh)
}
