package com.example.trolyao

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.rhino.Rhino
import ai.picovoice.rhino.RhinoInference
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.*
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.*

class AssistantService : AccessibilityService(), TextToSpeech.OnInitListener {

    private val accessKey = BuildConfig.SECRET_KEY
    private val RHINO_MODEL_FILE = "Jarvis_Commands_en_android_v4_0_0.rhn"
    private val CHANNEL_ID = "Jarvis_Channel"
    private val NOTIFICATION_ID = 123

    private var porcupine: Porcupine? = null
    private var rhino: Rhino? = null

    private var isRunning = false
    private var useRhino = false

    private var audioRecord: AudioRecord? = null
    private var tts: TextToSpeech? = null
    private var cameraManager: CameraManager? = null
    private var isFlashOn = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("JARVIS_DEBUG", "Accessibility Service đã kết nối.")
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            Log.d("JARVIS_DEBUG", "TTS đã sẵn sàng.")
            tts?.language = Locale.US

            // --- CHỈNH CHO CHUYÊN NGHIỆP ---
            // Tốc độ nói: 0.85f là tốc độ hơi chậm, tạo cảm giác điềm tĩnh (Mặc định là 1.0f)
            tts?.setSpeechRate(0.85f)

            // Độ trầm: 0.9f sẽ làm giọng nói hơi trầm xuống, nghe "ngầu" và nam tính hơn
            tts?.setPitch(0.9f)

            initPicovoice()
        } else {
            Log.e("JARVIS_DEBUG", "TTS khởi tạo thất bại!")
        }
    }

    private fun initPicovoice() {
        try {
            // Khởi tạo Porcupine [cite: 2026-01-20]
            porcupine = Porcupine.Builder()
                .setAccessKey(accessKey)
                .setKeywords(arrayOf(
                    Porcupine.BuiltInKeyword.JARVIS,
                    Porcupine.BuiltInKeyword.TERMINATOR
                ))
                .setSensitivities(floatArrayOf(0.8f, 0.85f))
                .build(applicationContext)
            Log.d("JARVIS_DEBUG", "Porcupine 4.0.x khởi tạo thành công.")

            // Khởi tạo Rhino [cite: 2026-01-20]
            rhino = Rhino.Builder()
                .setAccessKey(accessKey)
                .setContextPath(RHINO_MODEL_FILE)
                .setSensitivity(0.85f)
                .build(applicationContext)

            Log.d("JARVIS_DEBUG", "--- DANH SÁCH LỆNH ÔNG PHẢI NÓI ĐÚNG ---")
            Log.d("JARVIS_DEBUG", "\n${rhino?.contextInformation}")
            Log.d("JARVIS_DEBUG", "---------------------------------------")

            Log.d("JARVIS_DEBUG", "Rhino 4.0.x khởi tạo thành công với file: $RHINO_MODEL_FILE")

            startAudioLoop()
        } catch (e: Exception) {
            Log.e("JARVIS_DEBUG", "LỖI khởi tạo Picovoice: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioLoop() {
        isRunning = true
        val frameLength = porcupine?.frameLength ?: 512
        val bufferSize = AudioRecord.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize.coerceAtLeast(frameLength * 2)
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("JARVIS_DEBUG", "AudioRecord không thể khởi tạo! Kiểm tra quyền Micro.")
            return
        }

        Thread {
            val audioBuffer = ShortArray(frameLength)
            audioRecord?.startRecording()
            Log.d("JARVIS_DEBUG", "Bắt đầu ghi âm luồng chính.")

            while (isRunning) {
                val read = audioRecord?.read(audioBuffer, 0, frameLength) ?: 0
                if (read == frameLength) {

                    // KÍCH ÂM CHO REDMI 9T (1.8 lần)
                    for (i in audioBuffer.indices) {
                        val enhanced = (audioBuffer[i] * 1.0f).toInt()
                        audioBuffer[i] = enhanced.coerceIn(
                            Short.MIN_VALUE.toInt(),
                            Short.MAX_VALUE.toInt()
                        ).toShort()
                    }

                    if (!useRhino) {
                        // Chế độ chờ Wake Word
                        val keywordIndex = porcupine?.process(audioBuffer) ?: -1
                        if (keywordIndex >= 0) {
                            Log.d("JARVIS_DEBUG", "Đã nghe thấy Wake Word: $keywordIndex")
                            handler.post {
                                when (keywordIndex) {
                                    0 -> acknowledgeAndSwitch() // Jarvis
                                    1 -> {
                                        speak("System terminated.")
                                        Log.w("JARVIS_DEBUG", "Lệnh Terminator: Dừng hệ thống.")
                                    }
                                }
                            }
                        }
                    } else {
                        // Chế độ chờ Lệnh (Rhino)
                        val isFinalized = rhino?.process(audioBuffer) ?: false
                        if (isFinalized) {
                            val inference = rhino?.getInference()
                            Log.d("JARVIS_DEBUG", "Rhino đã kết thúc xử lý. Understood: ${inference?.isUnderstood}")
                            handler.post {
                                if (inference != null) processRhinoInference(inference)
                                useRhino = false // Quay lại nghe Wake Word
                                Log.d("JARVIS_DEBUG", "Đã quay lại chế độ chờ Wake Word.")
                            }
                        }
                    }
                }
            }
        }.start()
    }

    private fun acknowledgeAndSwitch() {
        speak("On your command, sir.")
        Log.d("JARVIS_DEBUG", "Jarvis phản hồi. Đang đợi 1.2s để tránh nhiễu âm thanh bot...")

        // Đợi 1.2 giây để Bot nói xong rồi mới bật Rhino [cite: 2026-01-22]
        handler.postDelayed({
            useRhino = true
            Log.d("JARVIS_DEBUG", ">>> RHINO ĐÃ BẬT. Sẵn sàng nghe lệnh từ người dùng.")
        }, 1200)
    }

    private fun processRhinoInference(inference: RhinoInference) {
        if (inference.isUnderstood) {
            Log.i("JARVIS_DEBUG", "Đã hiểu Intent: ${inference.intent}")
            when (inference.intent) {
                "time" -> {
                    val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
                    speak("It is $time, sir.")
                }
                "light" -> {
                    isFlashOn = !isFlashOn
                    toggleFlashlight(isFlashOn)
                    speak(if (isFlashOn) "Flashlight on" else "Flashlight off")
                }
                "trigger_relax" -> {
                    speak("Relaxation mode engaged. Enjoy your time.")
                }
                "override" -> {
                    speak("System override complete. All protocols reset.")
                }
            }
        } else {
            Log.w("JARVIS_DEBUG", "Rhino không hiểu lệnh vừa rồi.")
            speak("I am sorry, I did not catch that.")
        }
    }

    private fun toggleFlashlight(status: Boolean) {
        try {
            val cameraId = cameraManager?.cameraIdList?.get(0) ?: return
            cameraManager?.setTorchMode(cameraId, status)
            Log.d("JARVIS_DEBUG", "Flashlight state: $status")
        } catch (e: Exception) {
            Log.e("JARVIS_DEBUG", "Lỗi điều khiển Flash: ${e.message}")
        }
    }

    private fun speak(text: String) {
        Log.d("JARVIS_DEBUG", "Bot nói: $text")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jarvis Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis 4.0.x")
            .setContentText("Hệ thống đang lắng nghe...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        Log.d("JARVIS_DEBUG", "Đang đóng Service và giải phóng tài nguyên.")
        isRunning = false
        audioRecord?.stop(); audioRecord?.release()
        porcupine?.delete(); rhino?.delete()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}