package com.example.trolyao

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.Porcupine
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AssistantService : AccessibilityService(), RecognitionListener, TextToSpeech.OnInitListener {

    // --- CẤU HÌNH ---
    private val accessKey = BuildConfig.SECRET_KEY
    // Đừng dùng String cứng, dùng lazy và getExternalFilesDir
    private val VOSK_MODEL_PATH by lazy {
        getExternalFilesDir(null)?.absolutePath + "/model-en"
    }

    private val NOTIFICATION_ID = 123
    private val CHANNEL_ID = "Jarvis_Channel"

    // --- BIẾN HỆ THỐNG ---
    private var porcupineManager: PorcupineManager? = null
    private var voskModel: Model? = null
    private var globalRecognizer: Recognizer? = null
    private var voskService: SpeechService? = null

    private var tts: TextToSpeech? = null
    private var cameraManager: CameraManager? = null

    // CÁC HANDLER QUẢN LÝ THỜI GIAN
    private val handler = Handler(Looper.getMainLooper())
    private val flashHandler = Handler(Looper.getMainLooper())
    private val relaxHandler = Handler(Looper.getMainLooper())

    private var wakeLock: PowerManager.WakeLock? = null

    // TRẠNG THÁI
    private var isVoskListening = false
    private var isPaused = false
    private var isFlashOn = false


    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "ACTION_TOGGLE_JARVIS") {
                isPaused = !isPaused
                updateNotification()
                if (isPaused) {
                    stopPorcupine()
                    stopVoskAndReset()
                    speak("Jarvis sleeping")
                } else {
                    speak("Jarvis online")
                    startPorcupine()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TroLyAo::WakeLock")
            wakeLock?.acquire()

            tts = TextToSpeech(this, this)
            cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

            val filter = IntentFilter("ACTION_TOGGLE_JARVIS")
            registerReceiver(notificationReceiver, filter)

            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())

            initPorcupine()
            initVoskReady()

        } catch (e: Exception) {
            Log.e("Assistant", "Start Error: ${e.message}")
        }
    }

    // --- PHẦN THÔNG BÁO ---
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Jarvis Assistant", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val toggleIntent = Intent("ACTION_TOGGLE_JARVIS")
        val togglePendingIntent = PendingIntent.getBroadcast(
            this, 0, toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val statusText = if (isPaused) "Đang ngủ" else "Đang nghe lệnh..."
        val actionText = if (isPaused) "BẬT JARVIS" else "TẮT JARVIS"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Trợ lý ảo Jarvis")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(android.R.drawable.ic_media_pause, actionText, togglePendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    // --- PORCUPINE ---
    private fun initPorcupine() {
        try {
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywords(arrayOf(Porcupine.BuiltInKeyword.JARVIS, Porcupine.BuiltInKeyword.TERMINATOR))
                .setSensitivities(floatArrayOf(0.7f, 0.7f))
                .build(applicationContext) { keywordIndex ->
                    if (isPaused) return@build

                    if (keywordIndex == 0) { // JARVIS
                        Log.d("Assistant", ">>> Heard JARVIS! <<<")
                        // Thay vì bật mic ngay, ta gọi hàm xử lý chào hỏi
                        acknowledgeAndListen()
                    }
                    else if (keywordIndex == 1) { // TERMINATOR
                        Log.d("Assistant", ">>> Heard TERMINATOR! <<<")
                        performLock()
                    }
                }
            startPorcupine()
        } catch (e: Exception) {
            Log.e("Assistant", "Porcupine Error: ${e.message}")
        }
    }

    private fun startPorcupine() {
        if (!isVoskListening && !isPaused) porcupineManager?.start()
    }

    private fun stopPorcupine() {
        try { porcupineManager?.stop() } catch (e: Exception) {}
    }

    // Logic chào hỏi + delay
    private fun acknowledgeAndListen() {
        stopPorcupine()

        // HẠ CAO ĐỘ: Giọng trầm hơn sẽ nghe "ngầu" và "lực" hơn rất nhiều
        tts?.setPitch(0.92f)

        // GIẢM TỐC ĐỘ: Cho câu này thôi, để từ "Command" được phát âm trọn vẹn
        tts?.setSpeechRate(1.3f)

        // Mẹo viết: Thêm dấu chấm sau mỗi từ để ép TTS ngắt nghỉ nhẹ, tạo độ dứt khoát
        // Hoặc viết là "On your Com-mand."
        speak("On Your Command.")

        // Vì tốc độ chậm hơn, ta cần tăng delay một chút để Jarvis nói xong mới bật Vosk
        handler.postDelayed({
            tts?.setPitch(1.0f)      // Trả lại cao độ chuẩn
            tts?.setSpeechRate(1.0f)   // Trả lại tốc độ chuẩn
            startVoskCommandListening()
        }, 1400) // Tăng lên 1.4s cho khớp với tốc độ 1.3f
    }



    // --- VOSK ---
    private fun initVoskReady() {
        Thread {
            try {
                // Đặt ưu tiên cao cho luồng nhận diện
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)

                voskModel = Model(VOSK_MODEL_PATH)
                Log.d("Assistant", "Vosk Model Loaded!")

                val grammar = "[" +
                        "\"jarvis\", \"terminator\", \"override\", \"over ride\", \"over\", \"ride\", \"right\", " +
                        "\"google\", \"open\", \"flash\", \"light\", \"time\", \"hour\", \"relax\", " +
                        "\"cancel\", \"stop\", \"lock\", \"screen\", \"bye\"" +
                        "]"


                globalRecognizer = Recognizer(voskModel, 16000.0f, grammar)
                Log.d("Assistant", "Recognizer Ready!")
            } catch (e: Exception) {
                Log.e("Assistant", "Vosk Load Error: ${e.message}")
            }
        }.start()
    }

    private fun startVoskCommandListening() {
        if (voskModel == null || globalRecognizer == null || isPaused) {
            Log.e("Assistant", "Vosk not ready or Paused!")
            stopVoskAndReset() // Trả quyền cho Porcupine nếu Vosk chưa sẵn sàng
            return
        }

        try {
            Log.d("Assistant", "🎤 Vosk Start Listening...")
            globalRecognizer?.reset()
            voskService = SpeechService(globalRecognizer, 16000.0f)
            voskService?.startListening(this)
            isVoskListening = true

            // Timeout an toàn: Nếu 6 giây không nghe thấy gì thì phải Reset
            handler.removeCallbacksAndMessages(null) // Xóa các timeout cũ
            handler.postDelayed({
                if (isVoskListening) {
                    Log.d("Assistant", "Vosk Timeout - Resetting...")
                    stopVoskAndReset()
                }
            }, 6000)
        } catch (e: Exception) {
            Log.e("Assistant", "Vosk Start Error: ${e.message}")
            stopVoskAndReset()
        }
    }


    private fun stopVoskAndReset() {
        try {
            Log.d("Assistant", "Stop Vosk and Restarting Porcupine...")
            voskService?.stop()
            voskService?.cancel() // Thêm cancel để giải phóng hẳn luồng
            voskService = null
            isVoskListening = false

            // QUAN TRỌNG: Đợi 200ms để phần cứng kịp nhả Mic ra rồi mới bật Porcupine
            handler.postDelayed({
                if (!isPaused) {
                    try {
                        porcupineManager?.start()
                        Log.d("Assistant", "Porcupine Re-started")
                    } catch (e: Exception) {
                        Log.e("Assistant", "Lỗi khởi động lại Porcupine: ${e.message}")
                        // Nếu lỗi quá nặng, khởi tạo lại luôn Porcupine
                        initPorcupine()
                    }
                }
            }, 200)
        } catch (e: Exception) {
            Log.e("Assistant", "Fatal Error in Reset: ${e.message}")
        }
    }


    // --- XỬ LÝ LỆNH ---
    override fun onResult(hypothesis: String?) { processResult(hypothesis) }
    override fun onFinalResult(hypothesis: String?) { processResult(hypothesis); stopVoskAndReset() }

    private fun processResult(json: String?) {
        val rawJson = json ?: ""
        val text = if (rawJson.contains("\"text\" : \"")) {
            rawJson.substringAfter("\"text\" : \"").substringBefore("\"")
        } else { "" }

        if (text.isNotEmpty()) {
            Log.d("Assistant", "Heard: '$text'")
            when {

                // OVERRIDE
                text.contains("override") || text.contains("over ride") ||
                        (text.contains("over") && text.contains("ride")) ||
                        text.contains("over right")
                    -> {
                    startOverrideMode()
                    stopVoskAndReset()
                    return
                }

                // GOOGLE
                text.contains("google") -> {
                    speak("Opening assistant"); openGoogleAssistant(); stopVoskAndReset()
                }

                // TOGGLE FLASH
                text.contains("flash") || text.contains("light") -> {
                    val newState = !isFlashOn
                    toggleFlashlight(newState)
                    if (newState) speak("Flash on") else speak("Flash off")
                    stopVoskAndReset()
                }

                // TERMINATOR
                text.contains("terminator") || text.contains("lock screen") -> {
                    performLock(); stopVoskAndReset()
                }

                // RELAX
                text.contains("relax") -> {
                    startRelaxTimer(); stopVoskAndReset()
                }

                // TIME
                text.contains("time") || text.contains("hour") -> {
                    readCurrentTime(); stopVoskAndReset()
                }

                // CANCEL
                text.contains("cancel") || text.contains("stop") -> {
                    relaxHandler.removeCallbacksAndMessages(null)
                    flashHandler.removeCallbacksAndMessages(null)
                    speak("Cancelled")
                    stopVoskAndReset()
                }

            }
        }
    }

    // --- HÀM HỖ TRỢ ---

    private fun startOverrideMode() {
        // Hardcode ngày bắt đầu: 23/12/2025 16:00:00 (timestamp)
        val startTimestamp = 1734944400000L
        val currentTime = System.currentTimeMillis()

        // Tính số tuần đã qua (1 tuần = 604,800,000 ms)
        val weeksPassed = ((currentTime - startTimestamp) / 604800000L).toInt()

        // Số phút phạt (không giới hạn)
        val minutes = 3 + weeksPassed

        relaxHandler.removeCallbacksAndMessages(null)

        speak("Manual Override initiated. Accessing Prefrontal Cortex. Silence required for $minutes minutes.")

        Log.d("Assistant", "SYSTEM OVERRIDE: $minutes min countdown started.")

        // Hẹn giờ kết thúc
        relaxHandler.postDelayed({
            speak("Override complete. System stabilized. Prefrontal Cortex is now in control.")
        }, minutes * 60000L)

        // Nhắc nhở giữ im lặng mỗi phút
        for (i in 1 until minutes) {
            relaxHandler.postDelayed({
                val remaining = minutes - i
                // Nhắc ngắn gọn như một thông báo hệ thống
                speak("Override status: $remaining minutes remaining. Maintain silence.")
            }, i * 60000L)
        }

    }


    private fun startRelaxTimer() {
        relaxHandler.removeCallbacksAndMessages(null)
        speak("Relax mode activated. I will wake you up in 10 minutes.")

        // Hẹn giờ 10 phút
        relaxHandler.postDelayed({
            Log.d("Assistant", "Hết 10 phút Relax!")

            // Câu 1
            speak("Time is up! Relax mode over.")

            // Đợi 5s sau mới nói câu 2 (Không chặn luồng)
            relaxHandler.postDelayed({
                speak("Time is up!")

                // Đợi thêm 3s nữa nói câu 3
                relaxHandler.postDelayed({
                    speak("Wake up!")
                }, 3000)

            }, 5000)

        }, 600000)
    }


    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) tts?.setLanguage(Locale.UK) }

    // Hàm speak mặc định dùng STREAM_MUSIC (loa thường)
    private fun speak(text: String) { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null) }

    private fun toggleFlashlight(status: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val cameraId = cameraManager?.cameraIdList?.get(0)
                if (cameraId != null) {
                    cameraManager?.setTorchMode(cameraId, status)
                    isFlashOn = status
                    flashHandler.removeCallbacksAndMessages(null)
                    if (status) {
                        flashHandler.postDelayed({
                            try {
                                cameraManager?.setTorchMode(cameraId, false)
                                isFlashOn = false
                            } catch (e: Exception) {}
                        }, 60000)
                    }
                }
            }
        } catch (e: Exception) {}
    }

    private fun readCurrentTime() {
        val sdf = SimpleDateFormat("h:mm a", Locale.US)
        speak("It is ${sdf.format(Date())}")
    }

    private fun openGoogleAssistant() {
        try {
            val intent = Intent(Intent.ACTION_VOICE_COMMAND)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (e: Exception) {}
    }

    private fun performLock() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        else performGlobalAction(GLOBAL_ACTION_BACK)
    }

    override fun onPartialResult(hypothesis: String?) {}
    override fun onError(exception: Exception?) { stopVoskAndReset() }
    override fun onTimeout() { stopVoskAndReset() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        try { unregisterReceiver(notificationReceiver) } catch(e: Exception) {}
        relaxHandler.removeCallbacksAndMessages(null)
        stopPorcupine()
        voskService?.shutdown()
        globalRecognizer?.close()
        tts?.stop()
        tts?.shutdown()
        wakeLock?.release()
        super.onDestroy()
    }
}
