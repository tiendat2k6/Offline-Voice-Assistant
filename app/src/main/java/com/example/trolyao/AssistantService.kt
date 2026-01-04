package com.example.trolyao

import com.example.trolyao.BuildConfig
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
    private var keywordSpotter: KeywordSpotter? = null

    private var isSherpaSystemReady = false // Cờ hiệu bảo vệ

    private var audioRecord: AudioRecord? = null
    private var tts: TextToSpeech? = null
    private var cameraManager: CameraManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val handler = Handler(Looper.getMainLooper())
    private val flashHandler = Handler(Looper.getMainLooper())
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

            cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
            val filter = IntentFilter("ACTION_TOGGLE_JARVIS")
            ContextCompat.registerReceiver(this, notificationReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())

            // Bước 1: Chỉ kích hoạt TTS, chưa đụng đến Mic hay Model
            tts = TextToSpeech(this, this)
        } catch (e: Exception) { Log.e("Jarvis", "Error: ${e.message}") }
    }

    private fun initSherpaReady() {
        if (keywordSpotter != null) return
        Thread {
            try {
                Log.d("JARVIS_DEBUG", ">> Stage 1: Loading 20M Model...")
                val config = KeywordSpotterConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = "$SHERPA_DIR/encoder.onnx",
                            decoder = "$SHERPA_DIR/decoder.onnx",
                            joiner = "$SHERPA_DIR/joiner.onnx",
                        ),
                        tokens = "$SHERPA_DIR/tokens.txt",
                        modelType = "transducer",
                        numThreads = 1, // Snapdragon 662 gánh 1 thread là ổn định nhất
                        debug = false
                    ),
                    keywordsFile = "$SHERPA_DIR/keywords.txt"
                )

                Log.d("JARVIS_DEBUG", ">> BẮT ĐẦU nạp Encoder vào RAM...")

                keywordSpotter = KeywordSpotter(assets, config) // Dòng này ngốn RAM nhất

                Log.d("JARVIS_DEBUG", ">> ĐÃ NẠP XONG Encoder!") // Nếu hiện dòng này là an toàn

                isSherpaSystemReady = true
                Log.d("JARVIS_DEBUG", ">> Stage 2: 20M Model Ready!")

                // Bước 3: Model đã nằm yên trong RAM, giờ mới bật Mic của Porcupine
                handler.post {
                    speak("Systems online. Jarvis is ready, sir.")
                    initPorcupine()
                }
            } catch (e: Exception) {
                Log.e("JARVIS_DEBUG", "KWS Load Error: ${e.message}")
            }
        }.start()
    }



    private fun initPorcupine() {
        try {
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywords(arrayOf(Porcupine.BuiltInKeyword.JARVIS, Porcupine.BuiltInKeyword.TERMINATOR))
                .setSensitivities(floatArrayOf(0.75f, 0.75f))
                .build(applicationContext) { keywordIndex ->
                    if (isPaused) return@build
                    if (keywordIndex == 0) acknowledgeAndListen()
                    else if (keywordIndex == 1) performLock()
                }
            startPorcupine()
        } catch (e: Exception) { Log.e("Jarvis", "Porcupine Error: ${e.message}") }
    }

    private fun startPorcupine() { if (!isSherpaListening && !isPaused) porcupineManager?.start() }
    private fun stopPorcupine() {
        try {
            porcupineManager?.stop()
            // Xóa sạch thực thể để ép nó đóng hoàn toàn AudioRecord bên trong
            porcupineManager?.delete()
            porcupineManager = null
            Log.e("JARVIS_DEBUG", "Đã XÓA Porcupine để giải phóng Mic!")
        } catch (e: Exception) {
            Log.e("JARVIS_DEBUG", "Không thể diệt Porcupine: ${e.message}")
        }
    }


    private fun acknowledgeAndListen() {
        stopPorcupine()
        tts?.setSpeechRate(1.2f)
        speak("On your command.", "READY")
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == "READY") {
                    handler.postDelayed({ startSherpaListening() }, 2000)
                }
            }
            override fun onError(utteranceId: String?) { handler.post { startSherpaListening() } }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startSherpaListening() {
        if (keywordSpotter == null || !isSherpaSystemReady || isPaused) {
            if (!isPaused) startPorcupine()
            return
        }

        isSherpaListening = true

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

            // ÉP BUFFER LỚN HƠN:
            // Nếu minBufferSize nhỏ hơn 8000, ta lấy 8000. Nếu nó lớn hơn, ta lấy nó.
            // 8000 bytes tương đương với khoảng 250ms âm thanh - mức an toàn "vàng" cho máy yếu.
            val optimizedBufferSize = if (minBufferSize < 8000) 8000 else minBufferSize

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                optimizedBufferSize
            )

            // KIỂM TRA TRẠNG THÁI: Nếu không khởi tạo được thì dừng luôn để tránh crash
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("JARVIS_DEBUG", "AudioRecord không thể khởi tạo!")
                return
            }

            audioRecord?.startRecording()
            Log.d("JARVIS_DEBUG", "Mic started với buffer: $optimizedBufferSize")

        } catch (e: Exception) {
            Log.e("JARVIS_DEBUG", "Mic Error: ${e.message}")
            return
        }

        Thread {
            val stream = keywordSpotter!!.createStream()
            val buffer = ShortArray(1024)
            try {
                while (isSherpaListening) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    Log.d("JARVIS_DEBUG", "Read count: $read, First 5 samples: ${buffer.take(5)}")

                    if (read > 0) {
                        // debug:
                        // TÍNH TOÁN NĂNG LƯỢNG (RMS)
                        var sum = 0.0
                        for (i in 0 until read) {
                            sum += buffer[i] * buffer[i]
                        }
                        val rms = Math.sqrt(sum / read)

                        // Log năng lượng này ra. Nếu nó < 10 khi ông đang nói -> Mic bị "câm"
                        if (rms < 10) {
                            Log.e("JARVIS_DEBUG", "CẢNH BÁO: Mic im lặng hoàn toàn (RMS: $rms). Kiểm tra quyền hoặc Porcupine!")
                        } else {
                            // Log.d("JARVIS_DEBUG", "Mic OK, năng lượng: $rms")
                        }

                        val floatBuffer = FloatArray(read) { (buffer[it].toFloat() / 32768.0f) }
                        stream.acceptWaveform(floatBuffer, 16000)

                        // GIẢI MÃ VÀ KIỂM TRA TỪ KHÓA LIÊN TỤC
                        while (keywordSpotter!!.isReady(stream)) {
                            keywordSpotter!!.decode(stream)

                            // LỖI FIX TẠI ĐÂY: Lấy kết quả ngay trong lúc decode
                            val result = keywordSpotter!!.getResult(stream)
                            if (result.keyword.isNotEmpty()) {
                                Log.e("JARVIS_DEBUG", ">> SPOTTED: ${result.keyword}")

                                // LỖI FIX TẠI ĐÂY: Truyền nguyên bản (Chữ HOA) để Regex xử lý đúng
                                handler.post { processCommand(result.keyword) }

                                isSherpaListening = false // Thoát vòng lặp để xử lý lệnh
                                break
                            }
                        }
                    } else if (read < 0) break
                }
            } finally {
                stream.release()
                handler.post { stopSherpaListening() }
            }
        }.start()

        // Sau 10s không nghe thấy gì thì tự tắt
        handler.postDelayed({ if (isSherpaListening) stopSherpaListening() }, 10000)
    }


    private fun stopSherpaListening() {
        isSherpaListening = false
        try { audioRecord?.stop(); audioRecord?.release(); audioRecord = null } catch (e: Exception) {}
        handler.postDelayed({ startPorcupine() }, 300)
    }

    private fun processCommand(cmd: String) {
        // 1. Log nguyên bản để soi lỗi
        Log.e("JARVIS_DEBUG", "RAW CMD FROM AI: '$cmd'")

        // 2. Dọn dẹp cực sạch: Viết hoa -> Xóa mọi thứ không phải A-Z
        val normalizedCmd = cmd.uppercase().replace(Regex("[^A-Z]"), "")

        Log.d("JARVIS_DEBUG", "NORMALIZED: '$normalizedCmd'")

        if (normalizedCmd.isEmpty()) return

        when {
            normalizedCmd.contains("LIGHT") -> {
                isFlashOn = !isFlashOn
                toggleFlashlight(isFlashOn)
                speak(if (isFlashOn) "Flashlight on" else "Flashlight off")
            }
            normalizedCmd.contains("RELAX") -> {
                speak("Initiating relax protocol")
                startRelaxTimer()
            }
            normalizedCmd.contains("TIME") -> readCurrentTime()
            (normalizedCmd.contains("STILL") && normalizedCmd.contains("THREE")) -> startOverrideMode()
            normalizedCmd.contains("OPEN") -> openGoogleAssistant()
            normalizedCmd.contains("STOP") -> {
                systemTaskHandler.removeCallbacksAndMessages(null)
                tts?.stop()
                speak("All protocols terminated, sir.")
            }
            else -> {
                Log.d("JARVIS_DEBUG", "Không khớp lệnh nào: $normalizedCmd")
            }
        }
    }

    // --- CÁC TÍNH NĂNG CÓ SẴN (GIỮ NGUYÊN) ---
    private fun toggleFlashlight(status: Boolean) {
        try {
            val cameraId = cameraManager?.cameraIdList?.get(0) ?: return
            cameraManager?.setTorchMode(cameraId, status)
            if (status) flashHandler.postDelayed({ toggleFlashlight(false) }, 60000)
        } catch (e: Exception) {}
    }

    private fun readCurrentTime() {
        val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
        val day = SimpleDateFormat("EEEE", Locale.US).format(Date())
        speak("It is $time on $day, sir.")
    }

    private fun startOverrideMode() {
        val startTimestamp = 1766480400000L
        val weeksPassed = ((System.currentTimeMillis() - startTimestamp) / 604800000L).toInt()
        val minutes = 3 + weeksPassed
        systemTaskHandler.removeCallbacksAndMessages(null)
        speak("Manual Override initiated. Silence required for $minutes minutes.")
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
        systemTaskHandler.postDelayed({ speak("Time is up! Wake up sir.") }, 600000)
    }

    private fun openGoogleAssistant() {
        val intent = Intent(Intent.ACTION_VOICE_COMMAND).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        try { startActivity(intent) } catch (e: Exception) { speak("I cannot open Google right now.") }
    }

    private fun performLock() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        else speak("Screen locking is not supported on this version.")
    }

    private fun speak(text: String, id: String? = null) {
        val params = Bundle()
        if (id != null) params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
    }


    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.apply { language = Locale.UK; setPitch(0.9f); setSpeechRate(1.1f) }

            // KHÔNG nạp ngay. Đợi 10 giây để MIUI "lơi lỏng" cảnh giác rồi mới nạp Model 20M
            handler.postDelayed({
                initSherpaReady()
            }, 5000)
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

    private fun updateNotification() { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification()) }

    override fun onDestroy() {
        try { unregisterReceiver(notificationReceiver) } catch (e: Exception) {}
        stopPorcupine(); stopSherpaListening(); tts?.shutdown(); wakeLock?.release()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}