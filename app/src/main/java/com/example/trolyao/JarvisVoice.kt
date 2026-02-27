package com.example.trolyao

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

object JarvisVoice {
    private var tts: TextToSpeech? = null
    var isReady: Boolean = false
        private set

    // lưu trữ các hành động chờ thực hiện sau khi nói xong
    private val ttsCallbacks = mutableMapOf<String, () -> Unit>()
    private val handler = Handler(Looper.getMainLooper())

    // Hàm khởi tạo giọng nói:
    fun init(context: Context) {
        if (tts == null) {
            tts = TextToSpeech(context) {status ->
                if (status == TextToSpeech.SUCCESS) {
                    setupTTS()
                    isReady = true
                }
            }
        }
    }

    private fun setupTTS() {
        tts?.apply{
            language = Locale.UK
            setPitch(0.92f)
            setSpeechRate(1.0f)

            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceID: String?) {}
                override fun onError(utteranceID: String?) {ttsCallbacks.remove(utteranceID)}

                override fun onDone(utteranceId: String?) {
                    // Tìm hành động gắn với ID này
                    val action = ttsCallbacks.remove(utteranceId)
                    // thực thi hành động trên main thread
                    action?.let {handler.post {it.invoke()}}
                }
            })
        }
    }

    // Hàm thực hiện việc nói:
    // cho phép chuyền vào 1 hành động (onComplete)
    fun speak(text: String, isInterrupt: Boolean = false, onComplete: (() -> Unit)? = null) {
        if (!isReady) return

        // 1. Nếu ngắt lời, phải dọn sạch Map cũ để tránh rò rỉ bộ nhớ
        if (isInterrupt) { ttsCallbacks.clear() }

        // tạo một ID duy nhất cho mỗi lần nói để không bị lẫn callback
        val utteranceId = "JARVIS_${System.currentTimeMillis()}"

        // nếu có hành dộng đi kèm, lưu nó vào Map
        onComplete?.let { ttsCallbacks[utteranceId] = it }

        // xác định chế độ: ngắt lời hay xếp hàng
        val mode = if (isInterrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text, mode, null, utteranceId)
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e("JARVIS", "Lỗi dọn dẹp TTS: ${e.message}")
        } finally {
            tts = null
            isReady = false
            ttsCallbacks.clear()
        }
    }
}