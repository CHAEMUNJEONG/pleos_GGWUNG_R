package com.kkwongal.drive.vehicle

import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import com.kkwongal.drive.core.GeoPoint

/**
 * Pleos NaviHelper SDK 연결. 현재 위치와 목적지 도착 알림을 받는다.
 *
 * 주의: 이 파일의 SDK 호출은 다른 공모전 참가팀의 공개 코드(NaviHelper 3.0.5 기준)를
 * 참고해 작성했고, 직접 빌드해 확인하지 못했다. 빌드 오류가 나면 Android Studio 에서
 * NaviHelper / NaviHelperEventListener 를 Ctrl+클릭해 실제 함수 모양을 보고 이 파일만 고치면 된다.
 * Pleos SDK 에 의존하는 코드는 이 파일 하나뿐이다.
 */
class PleosNavi(
    private val context: Context,
    private val onLocation: (GeoPoint) -> Unit,
    private val onArrived: () -> Unit,
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
            )
        }

        override fun onDestinationArrived(info: DestinationArrivedInfo) {
            onArrived()
        }
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
