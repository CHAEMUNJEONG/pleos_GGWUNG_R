package com.kkwongal.drive.vehicle

import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.DestinationArrivedInfo
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
    private val onArrived: (String) -> Unit,
    private val onSpeedLimit: (Int) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private var navi: NaviHelper? = null
    private var connected = false

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            onLocation(
                GeoPoint(
                    currentLocationInfo.latitude.toDouble(),
                    currentLocationInfo.longitude.toDouble(),
                ),
                currentLocationInfo.address,
            )
        }

        override fun onDestinationArrived(info: DestinationArrivedInfo) {
            // 목적지 이름 (예: 강릉 안목해변). 스탬프 이름으로 쓴다.
            onArrived(info.name)
        }

        override fun onCurrentRoadSpeedLimit(speedLimit: Int) {
            // 도로 종류(시내·국도·고속도로)와 적정 속도 판단에 쓴다
            onSpeedLimit(speedLimit)
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "내비 오류: $errorCode")
            onStatus("내비 오류: $errorCode")
        }
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
            navi = null
            Log.w(TAG, "NaviHelper 시작 실패", t)
            onStatus("내비 연동 실패: ${t.message}")
        }
    }

    @Synchronized
    fun stop() {
        val helper = navi ?: return
        runCatching {
            if (connected) helper.removeListener(listener)
            helper.release()
        }
        navi = null
        connected = false
    }

    @Synchronized
    private fun onConnected(helper: NaviHelper) {
        if (navi !== helper) return
        helper.addListener(listener)
        connected = true
        onStatus("내비 연동됨")
    }

    @Synchronized
    private fun onFailed(helper: NaviHelper, e: Throwable?) {
        if (navi !== helper) return
        runCatching { helper.release() }
        navi = null
        Log.w(TAG, "NaviHelper 연결 실패", e)
        onStatus("내비 연동 실패: ${e?.message}")
    }

    private companion object {
        const val TAG = "PleosNavi"
    }
}
