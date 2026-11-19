package com.kkwongal.drive.voice

import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.speech.tts.TextToSpeech as AndroidTts
import android.util.Log
import java.util.Locale

/**
 * 감점 음성 안내. 운전자가 화면을 보지 않아도 어떤 급조작으로 몇 점이 깎였는지 들을 수 있다.
 *
 * - 먼저 Pleos Gleo AI TextToSpeech SDK (3.3.1) 를 기기 안 엔진(ON_DEVICE)으로 쓴다. 인터넷·앱 키가 필요 없다.
 * - Pleos 음성을 쓸 수 없으면(초기화 실패, 재생 오류) 안드로이드 기본 음성 합성으로 바꾼다.
 *
 * [onEngine] 으로 지금 쓰는 엔진 이름을 알려 준다 (설정 화면 안내용).
 */
class VoiceGuide(
    context: Context,
    private val onEngine: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private var pleos: PleosTts? = null
    @Volatile private var pleosReady = false
    private var android: AndroidTts? = null
    @Volatile private var androidReady = false
    /** Pleos 음성을 쓰지 못한 이유. 설정 화면에 함께 보여 준다. */
    @Volatile private var pleosProblem: String? = null

    private val pleosEvents = object : EventListener {
        override fun onStart() = Unit
        override fun onReady() = Unit
        override fun onDone() = Unit
        override fun onStop() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onError(error: ErrorType) {
            Log.w(TAG, "Pleos 음성 오류: $error. 안드로이드 기본 음성으로 바꿉니다")
            pleosProblem = "Pleos 음성 오류 $error"
            pleosReady = false
            startAndroid()
        }
    }

    fun start() {
        if (pleos != null || android != null) return
        try {
            val tts = PleosTts(appContext, Mode.ON_DEVICE)
            pleos = tts
            // SDK 안의 서비스 연결은 initialize() 가 만든다. 리스너 등록과 설정은 초기화가 끝난 뒤에 한다.
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    runCatching { tts.addEventListener(pleosEvents) }
                        .onFailure { Log.w(TAG, "Pleos 음성 리스너 등록 실패", it) }
                    runCatching { tts.setLocale(Locale.KOREA) }
                    pleosReady = true
                    onEngine("Pleos Gleo 음성")
                }

                override fun onFailure(error: ErrorType) {
                    Log.w(TAG, "Pleos 음성 초기화 실패: $error")
                    pleosProblem = "Pleos 음성 초기화 실패 $error"
                    startAndroid()
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "Pleos 음성을 시작하지 못함", t)
            pleosProblem = "Pleos 음성 시작 실패 ${t.javaClass.simpleName}"
            startAndroid()
        }
    }

    @Synchronized
    private fun startAndroid() {
        if (android != null) return
        android = AndroidTts(appContext) { status ->
            androidReady = status == AndroidTts.SUCCESS
            val why = pleosProblem?.let { " ($it)" } ?: ""
            if (androidReady) {
                android?.language = Locale.KOREAN
                onEngine("안드로이드 기본 음성$why")
            } else {
                Log.w(TAG, "안드로이드 기본 음성도 쓸 수 없음: status=$status")
                onEngine("음성을 쓸 수 없어요$why · 기본 음성 $status")
            }
        }
    }

    /** 문장 하나를 읽는다. 앞 문장이 끝나지 않았으면 그 뒤에 이어서 읽는다. */
    fun speak(text: String) {
        val p = pleos
        if (pleosReady && p != null) {
            val ok = runCatching { p.speak(text) }.onFailure { Log.w(TAG, "Pleos 음성 재생 실패", it) }.isSuccess
            if (ok) return
            pleosReady = false
            startAndroid()
        }
        val a = android
        if (androidReady && a != null) {
            a.speak(text, AndroidTts.QUEUE_ADD, null, "kkwongal-${System.nanoTime()}")
        }
    }

    fun stopSpeaking() {
        runCatching { pleos?.stop() }
        runCatching { android?.stop() }
    }

    fun release() {
        runCatching {
            pleos?.removeEventListener(pleosEvents)
            pleos?.release()
        }
        runCatching { android?.shutdown() }
        pleos = null
        android = null
        pleosReady = false
        androidReady = false
    }

    companion object {
        private const val TAG = "VoiceGuide"

        /** 감점 안내 문장. 예: "급정거, 5점 감점" */
        fun penaltyMessage(label: String, penalty: Int): String = "$label, ${penalty}점 감점"
    }
}
