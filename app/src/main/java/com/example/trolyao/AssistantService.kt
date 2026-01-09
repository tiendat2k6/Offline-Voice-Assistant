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

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder


class AssistantService : AccessibilityService(), TextToSpeech.OnInitListener {

    private val SHERPA_DIR = "sherpa-onnx-en"
    private val accessKey = BuildConfig.SECRET_KEY
    private val CHANNEL_ID = "Jarvis_Channel"
    private val NOTIFICATION_ID = 123

    // AI Components
    private var porcupineManager: PorcupineManager? = null
    private var keywordSpotter: KeywordSpotter? = null
    private var isSherpaSystemReady = false

    // Hardware & State
    private var audioRecord: AudioRecord? = null
    private var tts: TextToSpeech? = null
    private var cameraManager: CameraManager? = null
    private var isSherpaListening = false
    private var isPaused = false
    private var isFlashOn = false

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // Bước 1: Khởi tạo TTS trước
        tts = TextToSpeech(this, this)

        val filter = IntentFilter("ACTION_TOGGLE_JARVIS")
        ContextCompat.registerReceiver(this, notificationReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    // --- GIAI ĐOẠN 1: NẠP NÃO (CHỈ CHẠY 1 LẦN) ---
    private fun initSherpaASR() {
        if (keywordSpotter != null) return
        Thread {
            try {
                Log.d("JARVIS_DEBUG", ">> Đang nạp Model KWS (Keyword Spotting)...")

                // 1. Dùng KeywordSpotterConfig
                val config = KeywordSpotterConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = "$SHERPA_DIR/encoder.onnx",
                            decoder = "$SHERPA_DIR/decoder.onnx",
                            joiner = "$SHERPA_DIR/joiner.onnx",
                        ),
                        tokens = "$SHERPA_DIR/tokens.txt",
                        modelType = "zipformer",
                        numThreads = 1,
                        debug = false
                    ),
                    keywordsFile = "$SHERPA_DIR/keywords.txt",
                    keywordsScore = 0.1f
                )

                // 2. PHẢI khởi tạo bằng KeywordSpotter
                keywordSpotter = KeywordSpotter(assets, config)

                isSherpaSystemReady = true
                Log.e("JARVIS_DEBUG", ">> Hệ thống Keyword Spotter đã sẵn sàng!")
                handler.post {
                    speak("Systems online.")
                    initPorcupine()
                }
            } catch (e: Exception) {
                Log.e("JARVIS_DEBUG", "Lỗi nạp Model KWS: ${e.message}")
                e.printStackTrace()
            }
        }.start()
    }

    private fun initPorcupine() {
        try {
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywords(arrayOf(Porcupine.BuiltInKeyword.JARVIS))
                .setSensitivities(floatArrayOf(0.7f))
                .build(applicationContext) { keywordIndex ->
                    if (keywordIndex == 0) acknowledgeAndListen()
                }
            porcupineManager?.start()
        } catch (e: Exception) { Log.e("JARVIS_DEBUG", "Porcupine Error: ${e.message}") }
    }

    private fun acknowledgeAndListen() {
        // Tắt Porcupine để giải phóng Mic cho Sherpa
        porcupineManager?.stop()
        porcupineManager?.delete()
        porcupineManager = null

        speak("On your command.", "READY")
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(p0: String?) {}
            override fun onDone(id: String?) {
                if (id == "READY") handler.post { startSherpaLoop() }
            }
            override fun onError(p0: String?) { handler.post { startSherpaLoop() } }
        })
    }

    // --- GIAI ĐOẠN 2: NGHE LỆNH (VÒNG LẶP CHIẾN THUẬT) ---
    @SuppressLint("MissingPermission")
    private fun startSherpaLoop() {
        val spotter = keywordSpotter ?: return
        isSherpaListening = true
        val stream = spotter.createStream()

        // --- BƯỚC 1: CHUẨN BỊ FILE GHI ÂM ---
        val debugFile = File(getExternalFilesDir(null), "jarvis_audio_debug.pcm")
        var fos: FileOutputStream? = null

        try {
            fos = FileOutputStream(debugFile)
            val bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize.coerceAtLeast(8000))
            audioRecord?.startRecording()
            Log.d("JARVIS_DEBUG", "Đang ghi âm debug vào: ${debugFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("JARVIS_DEBUG", "Không thể khởi tạo ghi âm: ${e.message}")
            return
        }

        Thread {
            val buffer = ShortArray(1600)
            // ByteBuffer dùng để chuyển Short sang Byte để ghi vào file
            val byteBuffer = ByteBuffer.allocate(3200).order(ByteOrder.LITTLE_ENDIAN)

            try {
                while (isSherpaListening) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        // --- BƯỚC 2: GHI DỮ LIỆU VÀO FILE ---
                        byteBuffer.clear()
                        for (i in 0 until read) {
                            byteBuffer.putShort(buffer[i])
                        }
                        fos.write(byteBuffer.array(), 0, read * 2)

                        // Kiểm tra cường độ âm thanh
                        var maxVol = 0
                        for (i in 0 until read) {
                            val absVal = Math.abs(buffer[i].toInt())
                            if (absVal > maxVol) maxVol = absVal
                        }
                        if (maxVol > 1000) Log.d("JARVIS_DEBUG", "Raw Mic: $maxVol")

                        // Xử lý AI
                        val floatBuffer = FloatArray(read) {
                            ((buffer[it] / 32768.0f) * 1.0f).coerceIn(-1.0f, 1.0f)
                        }
                        stream.acceptWaveform(floatBuffer, 16000)

                        while (spotter.isReady(stream)) {
                            spotter.decode(stream)
                        }

                        val result = spotter.getResult(stream)
                        val raw: String = result.keyword ?: ""

                        // 1. SOI TOKEN LẺ (Đây là thứ AI thực sự nghe thấy)
                        if (result.tokens.isNotEmpty()) {
                            val heard = result.tokens.joinToString("-")
                            Log.d("JARVIS_SCORES", "AI ĐANG NGHE THẤY: [$heard]")
                        }

                        if (raw.isNotEmpty()) {
                            val asciiCode = raw[0].code
                            Log.e("JARVIS_BINGO", "GOC: '$raw' | ASCII: $asciiCode")

                            var cleanCmd = raw.replace("▁", "").replace("_", "").replace(" ", "")
                            val finalCmd = cleanCmd.trim().uppercase()

                            if (finalCmd.isNotEmpty()) {
                                Log.e("JARVIS_BINGO", ">> LENH THUC SU: $finalCmd")
                                handler.post { processCommand(finalCmd) }
                                isSherpaListening = false
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("JARVIS_DEBUG", "Lỗi trong luồng xử lý: ${e.message}")
            } finally {
                // --- BƯỚC 3: ĐÓNG FILE ---
                try { fos.close() } catch (e: Exception) {}
                stream.release()
                handler.post { stopSherpaAndResumePorcupine() }
            }
        }.start()

        handler.postDelayed({ if (isSherpaListening) isSherpaListening = false }, 20000)
    }

    private fun stopSherpaAndResumePorcupine() {
        isSherpaListening = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {}
        initPorcupine() // Quay lại nghe từ "Jarvis"
    }

    private fun processCommand(cmd: String) {
        when {
            cmd.contains("LIGHT") -> {
                isFlashOn = !isFlashOn
                toggleFlashlight(isFlashOn)
                speak(if (isFlashOn) "Flashlight on" else "Flashlight off")
            }
            cmd.contains("TIME") -> {
                val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
                speak("It is $time, sir.")
            }
            cmd.contains("STOP") -> speak("Terminating processes.")
        }
    }

    // --- UTILS ---
    private fun toggleFlashlight(status: Boolean) {
        try {
            val cameraId = cameraManager?.cameraIdList?.get(0) ?: return
            cameraManager?.setTorchMode(cameraId, status)
        } catch (e: Exception) {}
    }

    private fun speak(text: String, id: String? = null) {
        val params = Bundle()
        if (id != null) params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.UK
            handler.postDelayed({ initSherpaASR() }, 1000)
        }
    }

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "ACTION_TOGGLE_JARVIS") {
                isPaused = !isPaused
                if (isPaused) { isSherpaListening = false; speak("Jarvis sleeping") }
                else { speak("Jarvis awake"); initPorcupine() }
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis Active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true).build()
    }

    override fun onDestroy() {
        isSherpaListening = false
        porcupineManager?.stop(); porcupineManager?.delete()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}