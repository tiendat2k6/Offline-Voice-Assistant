package com.example.trolyao

import ai.picovoice.porcupine.*
import ai.picovoice.rhino.*
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
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import ai.picovoice.porcupine.Porcupine

class AssistantService : AccessibilityService(), TextToSpeech.OnInitListener {

    private val accessKey = BuildConfig.SECRET_KEY // Key Picovoice của bạn
    private val RHINO_MODEL_FILE = "Jarvis_Commands_en_android_v4_0_0.rhn"
    private val CHANNEL_ID = "Jarvis_Channel"
    private val NOTIFICATION_ID = 123

    // Picovoice Engines
    private var porcupine: Porcupine? = null
    private var rhino: Rhino? = null

    // State Management
    private var isAwaitingAuth = false
    private var isRunning = false
    private var useRhino = false // False: Đang nghe Jarvis | True: Đang nghe lệnh (Rhino)

    // Hardware
    private var audioRecord: AudioRecord? = null
    private var tts: TextToSpeech? = null
    private var cameraManager: CameraManager? = null
    private var isFlashOn = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            initPicovoice()
        }
    }

    private fun initPicovoice() {
        try {
            // Sử dụng Porcupine.BuiltInKeyword thay vì PorcupineKeyword
            porcupine = Porcupine.Builder()
                .setAccessKey(accessKey)
                .setKeywords(arrayOf(
                    Porcupine.BuiltInKeyword.JARVIS,
                    Porcupine.BuiltInKeyword.TERMINATOR
                ))
                .setSensitivities(floatArrayOf(0.8f, 0.85f)) // Độ thính cho từng từ
                .build(applicationContext)

            // Khởi tạo Rhino như cũ
            rhino = Rhino.Builder()
                .setAccessKey(accessKey)
                .setContextPath(RHINO_MODEL_FILE)
                .setSensitivity(0.85f)
                .build(applicationContext)

            startAudioLoop()
        } catch (e: Exception) {
            Log.e("JARVIS", "Lỗi khởi tạo: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioLoop() {
        isRunning = true
        val frameLength = porcupine?.frameLength ?: 512
        val bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            bufferSize.coerceAtLeast(frameLength * 2)
        )

        Thread {
            val audioBuffer = ShortArray(frameLength)
            audioRecord?.startRecording()

            while (isRunning) {
                val read = audioRecord?.read(audioBuffer, 0, frameLength) ?: 0
                if (read == frameLength) {

                    // --- CHIÊU TRỊ ĐIẾC CHO REDMI 9T: AUDIO GAIN ---
                    for (i in audioBuffer.indices) {
                        val enhanced = (audioBuffer[i] * 1.8f).toInt() // Nhân 1.8 lần biên độ
                        audioBuffer[i] = enhanced.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }

                    if (!useRhino) {
                        // Giai đoạn 1: Đang nghe từ "Jarvis"
                        val keywordIndex = porcupine?.process(audioBuffer) ?: -1
                        if (keywordIndex == 0) {
                            handler.post { acknowledgeAndSwitch() }
                        }
                    } else {
                        // Giai đoạn 2: Đang nghe lệnh (Rhino)
                        val isFinalized = rhino?.process(audioBuffer) ?: false
                        if (isFinalized) {
                            val inference = rhino?.getInference()
                            handler.post {
                                if (inference != null) processRhinoInference(inference)
                                useRhino = false // Quay lại nghe Jarvis
                            }
                        }
                    }
                }
            }
        }.start()
    }

    private fun acknowledgeAndSwitch() {
        speak("On your command, sir.")
        useRhino = true // Chuyển sang dùng Rhino để bắt lệnh tiếp theo
    }

    private fun processRhinoInference(inference: RhinoInference) {
        if (inference.isUnderstood) {
            val intent = inference.intent
            Log.d("JARVIS", "Nghe thấy Intent: $intent")

            when (intent) {
                "lockdown" -> {
                    speak("Warning: Protocol Zero initiated. Confirm authorization?")
                    isAwaitingAuth = true
                }
                "agree" -> {
                    if (isAwaitingAuth) {
                        speak("Identity authorized. Executing Blackout.")
                        // TODO: Chỗ này ném hàm khóa máy Laptop/Phone của bạn vào
                        isAwaitingAuth = false
                    }
                }
                "disagree" -> {
                    if (isAwaitingAuth) {
                        speak("Lockdown aborted. Systems standing down.")
                        isAwaitingAuth = false
                    }
                }
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
                    // Thêm logic giảm độ sáng, bật nhạc ở đây
                }
                "override" -> {
                    isAwaitingAuth = false
                    speak("System override complete. All protocols reset.")
                }
            }
        } else {
            if (isAwaitingAuth) speak("Unauthorized input. Please confirm or abort.")
            else Log.d("JARVIS", "Rhino không hiểu lệnh.")
        }
    }

    // --- CÁC HÀM TIỆN ÍCH ---
    private fun toggleFlashlight(status: Boolean) {
        try {
            val cameraId = cameraManager?.cameraIdList?.get(0) ?: return
            cameraManager?.setTorchMode(cameraId, status)
        } catch (e: Exception) {}
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
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
            .setContentText("Listening for Jarvis...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true).build()
    }

    override fun onDestroy() {
        isRunning = false
        audioRecord?.stop(); audioRecord?.release()
        porcupine?.delete(); rhino?.delete()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}