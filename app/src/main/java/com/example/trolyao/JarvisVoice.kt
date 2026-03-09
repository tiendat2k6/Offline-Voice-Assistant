package com.example.trolyao

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

object JarvisVoice {
    private var tts: TextToSpeech? = null
    var isReady: Boolean = false
        private set

    // lưu trữ các hành động chờ thực hiện sau khi nói xong
    private val ttsCallbacks = mutableMapOf<String, () -> Unit>()
    private val handler = Handler(Looper.getMainLooper())

    private var engVoice: Voice? = null
    private var vieVoice: Voice? = null

    // Hàm khởi tạo giọng nói:
    fun init(context: Context, onResult: (Boolean) -> Unit) {
        if (tts == null) {
            tts = TextToSpeech(context) {status ->
                if (status == TextToSpeech.SUCCESS) {
                    setupTTS()
                    isReady = true
                    onResult(true) // báo về là đã sẵn sàng

                    // test ngay khi vừa load xong để nghe thử
//                    handler.postDelayed({ testNotification() }, 3000)
                }
                else {
                    onResult(false) // báo thất bại
                }
            }
        }
        else {
            // nếu tts đã tồn tại rồi thì báo success luôn
            onResult(true)
        }
    }

    private fun setupTTS() {
        tts?.apply{
            // lấy danh sách tất cả các giọng máy hộ trợ
//            val availableVoices = voices
//
//            availableVoices.forEach { voice ->
//                // In: tên giọng, ngôn ngữ, độ chi tiết (quality), có đang cần mạng không
//                Log.i("JARVIS", "Voice: ${voice.name} | Locale: ${voice.locale} | Quality: ${voice.quality} | IsNetwork: ${voice.isNetworkConnectionRequired}")
//            }

            // Cache lại để dùng ngay lập tức, không tốn thời gian tìm kiếm
            engVoice = voices.find { it.name == "en-gb-x-gba-local" }
            vieVoice = voices.find { it.name == "vi-vn-x-vic-local" }

            // nếu không tìm thấy voice cụ thể, set Locale làm backup
            if (engVoice == null) language = Locale.UK
            if (vieVoice == null) language = Locale.forLanguageTag("vi-VN")

            voice = engVoice ?: voice

//            setPitch(0.92f)
//            setSpeechRate(1.0f)

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

        // 1. Nếu ngắt lời, phải dọn sạch Map cũ để tránh rò rỉ bộ nhớ và câu cũ chết rồi vẫn gọi onDone
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

    fun speakHybrid(englishPart: String, vietnamesePart: String, onComplete: (() -> Unit)? = null) {
        tts?.apply {
            // đọc phần đầu bằng tiếng anh
            speak(englishPart, isInterrupt = false)

            // set giọng việt và xếp hàng đọc tiếp ngay lập tức kèm callback khi kết thúc toàn bộ
            setSpeechRate(1.05f) // tăng tốc tiếng việt.
            voice = vieVoice
            speak(vietnamesePart, isInterrupt = false, onComplete = onComplete)

            // set lại giọng anh cho những câu nói khác.
            setSpeechRate(1.0f) // reset lại tốc độ
            voice = engVoice
        }
    }

    private fun testNotification() {
        val vieTitle = "Nhật Tân"
        speakHybrid("Sir. You have a new message from ", "$vieTitle")

        // nghỉ 1 giây giữa 2 giọng
        tts?.playSilentUtterance(1000L, TextToSpeech.QUEUE_ADD, "PAUSE_ID")

        val finalTitle = "your mother"
        speak("You have a new message from $finalTitle, Sir")
    }
}