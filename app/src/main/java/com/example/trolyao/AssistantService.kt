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
    private val VOSK_MODEL_PATH = "/sdcard/model-en"
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
    private val handler = Handler(Looper.getMainLooper())      // Cho Mic
    private val flashHandler = Handler(Looper.getMainLooper()) // Cho Đèn Flash
    private val relaxHandler = Handler(Looper.getMainLooper()) // Cho chế độ Relax

    private var wakeLock: PowerManager.WakeLock? = null

    // Trạng thái
    private var isVoskListening = false
    private var isPaused = false

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
            wakeLock?.acquire(120 * 60 * 1000L)

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
                        startVoskCommandListening()
                    }
                    else if (keywordIndex == 1) { // TERMINATOR
                        Log.d("Assistant", ">>> Heard TERMINATOR! <<<")
//                        speak("Hasta la vista, baby")
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

    // --- VOSK ---
    private fun initVoskReady() {
        Thread {
            try {
                voskModel = Model(VOSK_MODEL_PATH)
                Log.d("Assistant", "✅ Vosk Model Loaded!")

                // --- DANH SÁCH TỪ KHÓA (GRAMMAR) ---
                // Chỉ cho phép nghe những từ này. Nói từ khác nó sẽ ép về từ gần nhất trong list.
                val grammar = "[" +
                        "\"jarvis\", \"terminator\", " + // Wake words
                        "\"google\", \"open\", " +
                        "\"flash\", \"light\", \"on\", \"off\", \"enable\", \"disable\", " +
                        "\"time\", \"hour\", " +
                        "\"relax\", " +
                        "\"cancel\", \"stop\", " +
                        "\"lock\", \"screen\", \"bye\"" +
                        "]"

                // Tạo Recognizer với Grammar
                globalRecognizer = Recognizer(voskModel, 16000.0f, grammar)
                Log.d("Assistant", "✅ Recognizer Ready (With Grammar)!")

            } catch (e: Exception) {
                Log.e("Assistant", "Vosk Load Error: ${e.message}")
            }
        }.start()
    }


    private fun startVoskCommandListening() {
        if (voskModel == null || globalRecognizer == null || isPaused) return
        stopPorcupine()
        handler.postDelayed({
            try {
                Log.d("Assistant", "Listening...")
                globalRecognizer?.reset()
                voskService = SpeechService(globalRecognizer, 16000.0f)
                voskService?.startListening(this)
                isVoskListening = true
                handler.postDelayed({ if (isVoskListening) stopVoskAndReset() }, 5000)
            } catch (e: Exception) { stopVoskAndReset() }
        }, 200)
    }

    private fun stopVoskAndReset() {
        try {
            voskService?.stop()
            voskService = null
            isVoskListening = false
            startPorcupine()
        } catch (e: Exception) {}
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
            Log.d("Assistant", "🟢 Heard: '$text'")
            when {
                // GOOGLE
                text.contains("google") -> { speak("Opening assistant"); openGoogleAssistant(); stopVoskAndReset() }

                // FLASH OFF
                (text.contains("flash") || text.contains("light")) && (text.contains("off") || text.contains("disable") || text.contains("stop")) -> {
                    toggleFlashlight(false); speak("Flash off"); stopVoskAndReset()
                }

                // FLASH ON
                text.contains("flash") -> { toggleFlashlight(true); speak("Flash on"); stopVoskAndReset() }

                // TERMINATOR
                text.contains("terminator") || text.contains("lock screen") -> { performLock(); stopVoskAndReset() }

                // --- TÍNH NĂNG MỚI: RELAX (HẸN GIỜ 10 PHÚT) ---
                text.contains("relax") -> {
                    startRelaxTimer()
                    stopVoskAndReset()
                }

                // GIỜ
                text.contains("time") || text.contains("hour") -> { readCurrentTime(); stopVoskAndReset() }

                // HỦY (Dừng mọi thứ: Flash, Relax timer...)
                text.contains("cancel") || text.contains("stop") -> {
                    relaxHandler.removeCallbacksAndMessages(null) // Hủy hẹn giờ Relax
                    flashHandler.removeCallbacksAndMessages(null) // Hủy hẹn giờ Flash
                    speak("Cancelled")
                    stopVoskAndReset()
                }
            }
        }
    }

    // --- HÀM HỖ TRỢ ---

    // Logic Relax: Đếm 10 phút
    private fun startRelaxTimer() {
        // Xóa hẹn giờ cũ nếu có
        relaxHandler.removeCallbacksAndMessages(null)

        speak("Relax mode activated. I will wake you up in 10 minutes.")

        // 10 phút = 10 * 60 * 1000 = 600000 ms
        relaxHandler.postDelayed({
            // Hành động khi hết giờ
            Log.d("Assistant", "⏰ Hết 10 phút Relax!")
            // Nói 3 lần cho tỉnh ngủ
            speak("Time is up! Relax mode over.")
            Thread.sleep(5000)
            speak("Time is up!")
            Thread.sleep(3000)
            speak("Wake up!")
        }, 600000)
    }

    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) tts?.setLanguage(Locale.US) }
    private fun speak(text: String) { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null) }

    private fun toggleFlashlight(status: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val cameraId = cameraManager?.cameraIdList?.get(0)
                if (cameraId != null) {
                    cameraManager?.setTorchMode(cameraId, status)
                    flashHandler.removeCallbacksAndMessages(null)
                    if (status) {
                        flashHandler.postDelayed({
                            try { cameraManager?.setTorchMode(cameraId, false) } catch (e: Exception) {}
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
