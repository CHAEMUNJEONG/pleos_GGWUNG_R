package com.kkwongal.drive.vehicle

import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import com.kkwongal.drive.core.GeoPoint

/**
 * Pleos NaviHelper SDK (3.0.5) 연결.
 * - 받기: 현재 위치·주소(onCurrentLocationInfo), 목적지 도착(onDestinationArrived),
 *   지금 도로의 제한속도(onCurrentRoadSpeedLimit, 1초마다), 오류(onError)
 * - 보내기: 여행 지도의 장소로 길 안내 시작(requestRoute)
 */
class PleosNavi(
    private val context: Context,
    private val onLocation: (GeoPoint, String) -> Unit,
    private val onArrived: (String, GeoPoint?) -> Unit,
    private val onSpeedLimit: (Int) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private var navi: NaviHelper? = null
    private var connected = false
    private var destinationPoint: GeoPoint? = null
    private var listener: NaviHelperEventListener? = null

    /** 로그에 마지막으로 남긴 제한속도. 같은 값이 1초마다 오므로 바뀔 때만 남긴다. */
    @Volatile private var lastLoggedLimit = Int.MIN_VALUE

    private fun listenerFor(helper: NaviHelper) = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) = active(helper) {
            onLocation(
                GeoPoint(
                    currentLocationInfo.latitude.toDouble(),
                    currentLocationInfo.longitude.toDouble(),
                ),
                currentLocationInfo.address,
            )
        }

        override fun onDestinationArrived(info: DestinationArrivedInfo) = active(helper) {
            // 목적지 이름 (예: 강릉 안목해변). 스탬프 이름으로 쓴다.
            // Arrival data has a name but no coordinates in navi-data 3.0.4.
            onArrived(info.name, destinationPoint)
        }

        override fun onDestinationInfo(info: DestinationInfo) = active(helper) {
            destinationPoint = GeoPoint(info.latitude, info.longitude)
        }

        override fun onDestinationChanged() = active(helper) {
            destinationPoint = null
            onArrived("", null)
            runCatching { helper.getDestinationInfo() }
                .onFailure { onStatus("목적지 정보 조회 실패") }
        }

        override fun onRouteCancelled() = active(helper) {
            destinationPoint = null
            onArrived("", null)
        }

        override fun onCurrentRoadSpeedLimit(speedLimit: Int) = active(helper) {
            // 도로 종류(시내·국도·고속도로)와 적정 속도 판단에 쓴다. 1초마다 오므로 값이 바뀔 때만 로그를 남긴다.
            if (speedLimit != lastLoggedLimit) {
                lastLoggedLimit = speedLimit
                Log.i(TAG, "도로 제한속도: $speedLimit km/h")
            }
            onSpeedLimit(speedLimit)
        }

        override fun onError(errorCode: NaviErrorCode) = active(helper) {
            Log.w(TAG, "내비 오류: $errorCode")
            onStatus("내비 오류: $errorCode")
        }
    }

    @Synchronized
    private fun active(helper: NaviHelper, action: () -> Unit) {
        if (navi === helper && connected) action()
    }

    /**
     * 내비에 목적지를 보내 길 안내를 시작한다. 내비에 연결되지 않았으면 false.
     * poiId 는 내비 검색 결과가 아닌 앱의 장소라서 앱 안의 식별자를 쓴다.
     */
    @Synchronized
    fun requestRoute(id: String, name: String, latitude: Double, longitude: Double, address: String): Boolean {
        val helper = navi ?: return false
        if (!connected) return false
        return runCatching {
            helper.requestRoute(
                RouteInfo(
                    latitude = latitude,
                    longitude = longitude,
                    poiName = name,
                    poiId = id,
                    address = address,
                    poiSubId = "",
                ),
            )
        }.onFailure { Log.w(TAG, "길 안내 요청 실패", it) }.isSuccess
    }

    @Synchronized
    fun start() {
        if (navi != null) return
        try {
            val helper = NaviHelper(context)
            navi = helper
            helper.initialize(
                { onConnected(helper) },
                { e: Throwable? -> onFailed(helper, e) },
            )
        } catch (t: Throwable) {
            stop()
            Log.w(TAG, "NaviHelper 시작 실패", t)
            onStatus("내비 연동 실패: ${t.message}")
        }
    }

    @Synchronized
    fun stop() {
        val helper = navi
        val oldListener = listener
        navi = null
        connected = false
        listener = null
        destinationPoint = null
        if (helper != null) {
            runCatching { oldListener?.let { helper.removeListener(it) } }
            runCatching { helper.release() }
        }
    }

    @Synchronized
    private fun onConnected(helper: NaviHelper) {
        if (navi !== helper) return
        runCatching {
            connected = true
            val sessionListener = listenerFor(helper)
            listener = sessionListener
            helper.addListener(sessionListener)
            onStatus("내비 연동됨")
            helper.getCurrentLocationInfo()
            helper.getDestinationInfo()
        }.onFailure { onFailed(helper, it) }
    }

    @Synchronized
    private fun onFailed(helper: NaviHelper, e: Throwable?) {
        if (navi !== helper) return
        stop()
        Log.w(TAG, "NaviHelper 연결 실패", e)
        onStatus("내비 연동 실패: ${e?.message}")
    }

    private companion object {
        const val TAG = "PleosNavi"
    }
}
