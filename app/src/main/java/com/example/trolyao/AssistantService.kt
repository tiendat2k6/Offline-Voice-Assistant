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
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.Porcupine
import android.annotation.SuppressLint
import com.k2fsa.sherpa.onnx.*
import java.text.SimpleDateFormat
import java.util.*
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

class AssistantService : AccessibilityService(), TextToSpeech.OnInitListener {

    // --- CẤU HÌNH ---
    private val SHERPA_DIR = "sherpa-onnx-en"
    private val accessKey = BuildConfig.SECRET_KEY
    private val NOTIFICATION_ID = 123
    private val CHANNEL_ID = "Jarvis_Channel"

    // --- BIẾN HỆ THỐNG ---
    private var porcupineManager: PorcupineManager? = null
    private var onlineRecognizer: OnlineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var tts: TextToSpeech? = null
    private var cameraManager: CameraManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val handler = Handler(Looper.getMainLooper())
    private val flashHandler = Handler(Looper.getMainLooper()) // <--- THÊM DÒNG NÀY
    private val systemTaskHandler = Handler(Looper.getMainLooper())


    private var isSherpaListening = false
    private var isPaused = false
    private var isFlashOn = false

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "ACTION_TOGGLE_JARVIS") {
                isPaused = !isPaused
                updateNotification()
                if (isPaused) {
                    stopPorcupine(); stopSherpaListening(); speak("Jarvis sleeping")
                } else {
                    speak("Jarvis online"); startPorcupine()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Assistant::WakeLock")
            wakeLock?.acquire()

            tts = TextToSpeech(this, this)
            cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

            val filter = IntentFilter("ACTION_TOGGLE_JARVIS")

            ContextCompat.registerReceiver(this, notificationReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)


            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())

            initPorcupine()
            initSherpaReady()
        } catch (e: Exception) { Log.e("Jarvis", "Error: ${e.message}") }
    }

    // --- 1. KHỞI TẠO SHERPA (ASR) ---
    private fun initSherpaReady() {
        if (onlineRecognizer != null) return
        Thread {
            try {
                val config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = "$SHERPA_DIR/encoder.int8.onnx",
                            decoder = "$SHERPA_DIR/decoder.int8.onnx",
                            joiner = "$SHERPA_DIR/joiner.int8.onnx",
                        ),
                        tokens = "$SHERPA_DIR/tokens.txt",
                        modelType = "transducer",
                        numThreads = 1,
                        debug = false
                    ),

                    endpointConfig = EndpointConfig(
                        // Rule 1: Đợi 2.4s ban đầu. Nếu ông chưa kịp nói gì sau "On your command" thì nó sẽ huỷ.
                        rule1 = EndpointRule(false, 2.4f, 0.0f),

                        // Rule 2: Đợi im lặng sau khi nói. Hạ xuống 0.8s để phản xạ nhanh hơn.
                        // (Đủ để ông nói "Flash... light" mà không bị cắt, nhưng đủ nhanh để bật đèn ngay)
                        rule2 = EndpointRule(true, 0.8f, 0.0f),

                        // Rule 3: PHANH KHẨN CẤP. Phải để TRUE để ép buộc dừng sau 10 giây.
                        // Tôi để 10s để nó không bị "đá nhau" với cái Handler 6s của ông.
                        rule3 = EndpointRule(true, 0.0f, 10.0f)
                    ),

                    enableEndpoint = true
                )
                onlineRecognizer = OnlineRecognizer(assets, config)
                Log.d("JARVIS_DEBUG", ">> AI Model Ready!")

                // Phải dùng handler.post vì hàm speak cần chạy trên Luồng Chính (Main Thread)
                handler.post {
                    speak("System core initialized. I am ready, sir.")
                }

            } catch (e: Exception) { Log.e("JARVIS_DEBUG", "Sherpa Load Error: ${e.message}") }
        }.start()
    }

    // --- 2. PORCUPINE (WAKE WORD) ---
    private fun initPorcupine() {
        try {
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywords(arrayOf(Porcupine.BuiltInKeyword.JARVIS, Porcupine.BuiltInKeyword.TERMINATOR))
                .setSensitivities(floatArrayOf(0.7f, 0.7f))
                .build(applicationContext) { keywordIndex ->
                    if (isPaused) return@build
                    if (keywordIndex == 0) acknowledgeAndListen() // JARVIS
                    else if (keywordIndex == 1) performLock() // TERMINATOR
                }
            startPorcupine()
        } catch (e: Exception) { Log.e("Jarvis", "Porcupine Error: ${e.message}") }
    }

    private fun startPorcupine() { if (!isSherpaListening && !isPaused) porcupineManager?.start() }
    private fun stopPorcupine() { try { porcupineManager?.stop() } catch (e: Exception) {} }

    private fun acknowledgeAndListen() {
        stopPorcupine() // Dừng Porcupine để nhả Mic

        // Ép tốc độ nói nhanh hơn tí cho nó ngầu
        tts?.setSpeechRate(1.2f)

        // QUAN TRỌNG: Phải truyền ID là "READY" ở đây
        speak("On your command.", "READY")

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == "READY") {
                    // Đợi 800ms để loa tắt hẳn, tránh Mic thu lại tiếng vang của chính Jarvis
                    handler.postDelayed({
                        startSherpaListening() // BẮT ĐẦU NGHE LỆNH TẠI ĐÂY
                    }, 800)
                }
            }
            override fun onError(utteranceId: String?) {
                Log.e("JARVIS_DEBUG", "Lỗi phát âm thanh!")
                // Nếu lỗi cũng phải bật Mic lên, không là nó "mất hút" luôn
                handler.post { startSherpaListening() }
            }
        })
    }


    // --- 3. SHERPA LISTENING (MAIN AI) ---
    @SuppressLint("MissingPermission")
    private fun startSherpaListening() {
        if (onlineRecognizer == null || isPaused) return
        isSherpaListening = true

        try {
            val bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, if (bufferSize > 0) bufferSize else 2048)

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) return
            audioRecord?.startRecording()
        } catch (e: Exception) { Log.e("JARVIS_DEBUG", "Mic Error: ${e.message}"); return }

        Thread {
            val stream = onlineRecognizer!!.createStream()
            val buffer = ShortArray(1024)
            try {
                while (isSherpaListening) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        val floatBuffer = FloatArray(read) { (buffer[it].toFloat() / 32768.0f) * 1.0f }
                        stream.acceptWaveform(floatBuffer, 16000)

                        while (onlineRecognizer!!.isReady(stream)) { onlineRecognizer!!.decode(stream) }

                        val result = onlineRecognizer!!.getResult(stream).text.lowercase()

                        // Hiển thị tiến trình AI đang đoán (để ông debug)
                        if (result.isNotEmpty()) Log.d("JARVIS_DEBUG", ">> Đang nghe: $result")

                        if (onlineRecognizer!!.isEndpoint(stream)) {
                            val finalResult = onlineRecognizer!!.getResult(stream).text

                            if (isValidCommand(finalResult)) {
                                // NẾU LÀ LỆNH HỢP LỆ -> THỰC THI VÀ DỪNG NGHE
                                Log.d("JARVIS_DEBUG", ">> KHỚP LỆNH: $finalResult. Đang thực thi...")
                                handler.post { processCommand(finalResult) }
                                isSherpaListening = false
                                break
                            } else {
                                // NẾU KHÔNG PHẢI LỆNH (TIẾNG ỒN) -> RESET ĐỂ NGHE TIẾP
                                Log.d("JARVIS_DEBUG", ">> RÁC: '$finalResult'. Đang xóa đệm để nghe lại...")
                                onlineRecognizer!!.reset(stream) // Xóa dữ liệu cũ trong RAM
                                // Không gọi break, vòng lặp while sẽ tiếp tục
                            }
                        }
                    } else if (read < 0) {
                        Log.e("JARVIS_DEBUG", ">> LỖI MIC: Hệ thống đã ngắt quyền ghi âm!")
                        break
                    }
                }
            } finally {
                onlineRecognizer?.reset(stream)
                stream.release()
                handler.post { stopSherpaListening() }
            }
        }.start()

        handler.postDelayed({ if (isSherpaListening) stopSherpaListening() }, 6000)
    }

    private fun stopSherpaListening() {
        isSherpaListening = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {}
        handler.postDelayed({ startPorcupine() }, 300)
    }

    // --- 4. XỬ LÝ LỆNH (COMMAND CENTER) ---
    private fun processCommand(text: String) {
        val cmd = text.lowercase()
        Log.d("JARVIS_DEBUG", "Executing: $cmd")

        when {
            // FLASH (Bắt lỗi "flesh" luôn cho chắc)
            cmd.contains("flash") || cmd.contains("light") || cmd.contains("flesh") -> {
                isFlashOn = !isFlashOn
                toggleFlashlight(isFlashOn)
                speak(if (isFlashOn) "Flashlight on" else "Flashlight off")
            }

            // TIME
            cmd.contains("time") || cmd.contains("hour") -> readCurrentTime()

            // GOOGLE ASSISTANT
            cmd.contains("google") || cmd.contains("open assistant") -> openGoogleAssistant()

            // RELAX MODE
            cmd.contains("relax") -> startRelaxTimer()

            // OVERRIDE MODE
            cmd.contains("override") || cmd.contains("over ride") -> startOverrideMode()

            // STOP / CANCEL
            cmd.contains("stop") || cmd.contains("cancel") || cmd.contains("terminate") -> {
                systemTaskHandler.removeCallbacksAndMessages(null)
                tts?.stop()
                speak("All protocols terminated, sir.")
            }

            // LOCK SCREEN
            cmd.contains("lock") || cmd.contains("screen") -> performLock()
        }
    }

    // --- 5. CÁC TÍNH NĂNG CHI TIẾT ---

    private fun toggleFlashlight(status: Boolean) {
        try {
            val cameraId = cameraManager?.cameraIdList?.get(0) ?: return
            cameraManager?.setTorchMode(cameraId, status)
            if (status) { // Tự tắt sau 1 phút để bảo vệ LED
                flashHandler.postDelayed({ toggleFlashlight(false) }, 60000)
            }
        } catch (e: Exception) {}
    }

    private fun readCurrentTime() {
        val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
        val day = SimpleDateFormat("EEEE", Locale.US).format(Date())
        speak("It is $time on $day, sir.")
    }

    private fun startOverrideMode() {
        // Tính toán số phút dựa trên tuần như bản Vosk của ông
        val startTimestamp = 1766480400000L // 23/12/2025
        val weeksPassed = ((System.currentTimeMillis() - startTimestamp) / 604800000L).toInt()
        val minutes = 3 + weeksPassed

        systemTaskHandler.removeCallbacksAndMessages(null)
        speak("Manual Override initiated. Silence required for $minutes minutes.")

        // Nhắc nhở mỗi phút
        for (i in 1..minutes) {
            systemTaskHandler.postDelayed({
                if (i == minutes) speak("Override complete. Prefrontal Cortex is now in control.")
                else speak("Override status: ${minutes - i} minutes remaining. Maintain silence.")
            }, i * 60000L)
        }
    }

    private fun startRelaxTimer() {
        systemTaskHandler.removeCallbacksAndMessages(null)
        speak("Relax mode activated. 10 minutes starting now.")
        systemTaskHandler.postDelayed({
            speak("Time is up! Wake up sir.")
        }, 600000) // 10 min
    }

    private fun openGoogleAssistant() {
        val intent = Intent(Intent.ACTION_VOICE_COMMAND).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        try { startActivity(intent) } catch (e: Exception) { speak("I cannot open Google right now.") }
    }

    private fun performLock() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            speak("Screen locking is not supported on this version.")
        }
    }

    // --- 6. HỖ TRỢ VÀ VÒNG ĐỜI ---
    private fun speak(text: String, id: String? = null) {
        val params = Bundle()
        if (id != null) {
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
    }

    private fun isValidCommand(text: String): Boolean {
        val cmd = text.lowercase()
        // Trả về true nếu chứa bất kỳ từ khóa hợp lệ nào
        return cmd.contains("flash") || cmd.contains("light") || cmd.contains("flesh") ||
                cmd.contains("time") || cmd.contains("hour") ||
                cmd.contains("override") || cmd.contains("over ride") ||
                cmd.contains("relax") || cmd.contains("stop") ||
                cmd.contains("cancel") || cmd.contains("lock") || cmd.contains("screen")
    }


    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.apply {
                language = Locale.UK
                setPitch(0.9f)
                setSpeechRate(1.1f)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Jarvis", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val toggleIntent = PendingIntent.getBroadcast(this, 0, Intent("ACTION_TOGGLE_JARVIS"), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis AI")
            .setContentText(if (isPaused) "Sleeping" else "Active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(android.R.drawable.ic_media_pause, "Toggle", toggleIntent)
            .setOngoing(true).build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        try { unregisterReceiver(notificationReceiver) } catch (e: Exception) {}
        stopPorcupine(); stopSherpaListening(); tts?.shutdown(); wakeLock?.release()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun hasPermissions(vararg permissions: String): Boolean = permissions.all {
        androidx.core.content.ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
