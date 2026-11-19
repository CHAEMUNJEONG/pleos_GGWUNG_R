package com.kkwongal.drive.debug

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.kkwongal.drive.vehicle.PleosNavi
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Observes the actual app adapter. Available only in debug APKs. */
class PleosSdkProbe : Instrumentation() {
    private var waitMs = 20_000L

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        waitMs = arguments?.getString("waitMs")?.toLongOrNull()?.coerceIn(1000, 60_000) ?: waitMs
        start()
    }

    override fun onStart() {
        val statuses = Collections.synchronizedList(mutableListOf<String>())
        val connected = AtomicBoolean(false)
        val locations = AtomicInteger(0)
        val arrivals = AtomicInteger(0)
        val limits = AtomicInteger(0)
        val failed = CountDownLatch(1)
        var adapter: PleosNavi? = null
        var failure: String? = null
        try {
            runOnMainSync {
                adapter = PleosNavi(targetContext,
                    onLocation = { _, _ -> locations.incrementAndGet() },
                    onArrived = { arrivals.incrementAndGet() },
                    onSpeedLimit = { limits.incrementAndGet() },
                    onStatus = { value ->
                        statuses += value
                        if (value == "내비 연동됨") connected.set(true)
                        if (value.startsWith("내비 연동 실패")) failed.countDown()
                    })
                adapter!!.start()
            }
            failed.await(waitMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            failure = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            runOnMainSync { adapter?.stop() }
        }
        val statusSnapshot = synchronized(statuses) { statuses.toList() }
        val result = JSONObject()
            .put("sdk", "NaviHelper 3.0.5")
            .put("serviceConnected", connected.get())
            .put("locationCallbacks", locations.get())
            .put("arrivalCallbacks", arrivals.get())
            .put("speedLimitCallbacks", limits.get())
            .put("observedMs", waitMs)
            .put("statuses", JSONArray(statusSnapshot))
            .put("probeError", failure ?: JSONObject.NULL)
        finish(Activity.RESULT_OK, Bundle().apply { putString("sdkProbe", result.toString()) })
    }
}
