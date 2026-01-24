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
import androidx.core.content.ContextCompat

class AssistantService : AccessibilityService(), TextToSpeech.OnInitListener {

    private val accessKey = BuildConfig.SECRET_KEY
    private val RHINO_MODEL_FILE = "Jarvis_Commands_en_android_v4_0_0.rhn"
    private val CHANNEL_ID = "Jarvis_Channel"
    private val NOTIFICATION_ID = 123

    private var porcupine: Porcupine? = null
    private var rhino: Rhino? = null
    private var audioRecord: AudioRecord? = null
    private var tts: TextToSpeech? = null
    private var cameraManager: CameraManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var isRunning = false
    private var useRhino = false
    private var isPaused = false
    private var isFlashOn = false

    private val handler = Handler(Looper.getMainLooper())
    private val systemTaskHandler = Handler(Looper.getMainLooper())

    private val flashHandler = Handler(Looper.getMainLooper())

    private val PC_COMMAND_URL = "https://juanita-tragicomic-nonethereally.ngrok-free.dev/lockdown" // Domain Ngrok của bạn
    private val AUTH_TOKEN = "Jarvis-K20-FPT-2026-ngocminh-tiendat" // Mã token bí mật

    // --- 1. TÍNH NĂNG ĐIỀU KHIỂN QUA THÔNG BÁO ---
    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "ACTION_TOGGLE_JARVIS") {
                isPaused = !isPaused
                updateNotification()
                if (isPaused) {
                    speak("Jarvis power save mode initiated.")
                    Log.d("JARVIS_DEBUG", "Hệ thống tạm dừng (Sleep)")
                } else {
                    speak("Jarvis online.")
                    Log.d("JARVIS_DEBUG", "Hệ thống hoạt động trở lại")
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        // 1. PHẢI GỌI HÀM NÀY ĐẦU TIÊN ĐỂ MỞ CỔNG THÔNG BÁO
        createNotificationChannel()

        // 2. BIẾN JARVIS THÀNH FOREGROUND SERVICE ĐỂ KHÔNG BỊ DIỆT
        // Điều này sẽ hiện cái thông báo "Jarvis is listening..." lên ngay lập tức
        startForeground(NOTIFICATION_ID, buildNotification())

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis::WakeLock")
        wakeLock?.acquire()

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        val filter = IntentFilter("ACTION_TOGGLE_JARVIS")

        ContextCompat.registerReceiver(
            this,
            notificationReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        Log.d("JARVIS_DEBUG", "Hệ thống đã trình diện và hiện thông báo thành công.")
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.UK // Giọng Anh cho nó chuyên nghiệp
            tts?.setPitch(0.92f)
            tts?.setSpeechRate(1.0f)
            initPicovoice()
        }
    }

    private fun initPicovoice() {
        try {
            porcupine = Porcupine.Builder()
                .setAccessKey(accessKey)
                .setKeywords(arrayOf(Porcupine.BuiltInKeyword.JARVIS, Porcupine.BuiltInKeyword.TERMINATOR))
                .setSensitivities(floatArrayOf(0.8f, 0.8f))
                .build(applicationContext)

            rhino = Rhino.Builder()
                .setAccessKey(accessKey)
                .setContextPath(RHINO_MODEL_FILE)
                .setSensitivity(0.7f)
                .build(applicationContext)

            Log.d("JARVIS_DEBUG", "--- DANH SÁCH LỆNH ÔNG PHẢI NÓI ĐÚNG ---")
            Log.d("JARVIS_DEBUG", "\n${rhino?.contextInformation}")
            Log.d("JARVIS_DEBUG", "---------------------------------------")

            startAudioLoop()
            Log.d("JARVIS_DEBUG", "Hệ thống Picovoice Ready.")
        } catch (e: Exception) {
            Log.e("JARVIS_DEBUG", "Lỗi khởi tạo Picovoice: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioLoop() {
        isRunning = true
        val frameLength = porcupine?.frameLength ?: 512
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 2048
        )

        Thread {
            val audioBuffer = ShortArray(frameLength)
            audioRecord?.startRecording()

            while (isRunning) {
                if (isPaused) { // Nếu đang Pause thì skip việc xử lý
                    Thread.sleep(100)
                    continue
                }

                val read = audioRecord?.read(audioBuffer, 0, frameLength) ?: 0
                if (read == frameLength) {
                    if (!useRhino) {
                        val keywordIndex = porcupine?.process(audioBuffer) ?: -1
                        if (keywordIndex == 0) handler.post { acknowledgeAndSwitch() }
                        else if (keywordIndex == 1) handler.post { performLock() }
                    } else {
                        val isFinalized = rhino?.process(audioBuffer) ?: false
                        if (isFinalized) {
                            val inference = rhino?.getInference()
                            handler.post {
                                if (inference != null) processRhinoInference(inference)
                                useRhino = false
                            }
                        }
                    }
                }
            }
        }.start()
    }

    private fun acknowledgeAndSwitch() {
        // --- 3. TĂNG TỐC ĐỘ NÓI KHI CHÀO (GIỐNG CODE CŨ) ---
        tts?.setSpeechRate(1.3f)
        speak("On your command.")
        handler.postDelayed({
            tts?.setSpeechRate(1.0f) // Trả lại tốc độ chuẩn
            useRhino = true
        }, 1000)
    }

    private fun processRhinoInference(inference: RhinoInference) {
        if (inference.isUnderstood) {
            Log.i("JARVIS_DEBUG", "Nhận diện Intent: ${inference.intent}")

            when (inference.intent) {
                "time" -> readCurrentTimePro()

                "light" -> {
                    isFlashOn = !isFlashOn
                    toggleFlashlight(isFlashOn)
                    speak(if (isFlashOn) "Flashlight activated." else "Flashlight deactivated.")
                }

                "trigger_relax" -> startRelaxTimer()

                "override" -> startOverrideMode()

                "lockdown" -> triggerPcBlackout()

                // SỬA TẠI ĐÂY: Phải là "trigger_assistant" mới khớp với YAML của ông
                "trigger_assistant" -> {
                    speak("Invoking core.")
                    handler.postDelayed({
                        openGoogleAssistant()
                    }, 1000)
                }

                // BỔ SUNG: Xử lý khi ông nói "confirm", "execute" hoặc "proceed"
                "agree" -> {
                    speak("Acknowledged. Executing the sequence.")
                    // Thêm logic thực thi lệnh quan trọng ở đây
                }

                // BỔ SUNG: Xử lý khi ông nói "no", "cancel" hoặc "denied"
                "disagree" -> {
                    speak("Action cancelled, sir.")
                }
            }
        } else {
            // Rhino không khớp được âm thanh với bất kỳ Expression nào trong YAML
            Log.w("JARVIS_DEBUG", "Rhino không hiểu lệnh. Expression không khớp.")
            speak("Input unrecognized. Awaiting re-entry, sir.")
        }
    }

    private fun triggerPcBlackout() {
        // Thông báo trạng thái qua TTS
        // speak("Protocol Blackout initiated... Target neutralized, sir.")

        speak("Dopamine receptors stabilizing... Neuro-reset in progress. ... You are training your neural weights right now... Sir.")

        // --- HẸN GIỜ 10 PHÚT (600.000 ms) ---
        handler.postDelayed({
            // Câu thoại sau khi đã "reset" xong dopamine
            speak("Neuro-stabilization complete. Your mind is now optimized, sir.")
        }, 600000L)

        // Chạy trong luồng phụ để tránh treo app trên Redmi 9T
        Thread {
            try {
                val url = java.net.URL(PC_COMMAND_URL)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"

                // Gắn token bảo mật để tránh bot quấy phá
                connection.setRequestProperty("x-token", AUTH_TOKEN)

                // DÒNG QUAN TRỌNG: Bỏ qua trang cảnh báo của Ngrok để lệnh chạy thẳng
                connection.setRequestProperty("ngrok-skip-browser-warning", "true")

                connection.connectTimeout = 5000

                val code = connection.responseCode
                if (code == 200) {
                    Log.d("JARVIS_DEBUG", "Lệnh đã được thực thi tại Vinhomes Grand Park.")
                } else {
                    handler.post { speak("Access denied. Link error $code.") }
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e("JARVIS_DEBUG", "Lỗi: ${e.message}")
                handler.post { speak("Target unreachable. Check your network, sir.") }
            }
        }.start()
    }

    // --- 4. TÍNH NĂNG OVERRIDE (ĐẾM TUẦN PHẠT) ---
    private fun startOverrideMode() {
        val startTimestamp = 1766480400000L // Mốc 23/12/2025
        val weeksPassed = ((System.currentTimeMillis() - startTimestamp) / 604800000L).toInt()
        val minutes = 3 + weeksPassed

        systemTaskHandler.removeCallbacksAndMessages(null)
        speak("Manual Override initiated. Silence required for $minutes minutes, sir.")

        systemTaskHandler.postDelayed({
            speak("Override complete. Prefrontal Cortex is now in control.")
        }, minutes * 60000L)
    }

    // --- 5. TÍNH NĂNG RELAX (HẸN GIỜ 10 PHÚT) ---
    private fun startRelaxTimer() {
        systemTaskHandler.removeCallbacksAndMessages(null)
        speak("Relax mode activated. I will wake you up in 10 minutes.")

        // Hẹn giờ 10 phút
        systemTaskHandler.postDelayed({
            Log.d("Assistant", "Hết 10 phút Relax!")

            // Câu 1
            speak("Time is up! Relax mode over.")

            // Đợi 4s sau mới nói câu 2 (Không chặn luồng)
            systemTaskHandler.postDelayed({
                speak("Time is up!")

                // Đợi thêm 3s nữa nói câu 3
                systemTaskHandler.postDelayed({
                    speak("Wake up!")
                }, 3000)

            }, 4000)

        }, 600000)
    }

    // --- 6. ĐỌC GIỜ KIỂU "SANG CHẢNH" ---
    private fun readCurrentTimePro() {
        val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
        val day = SimpleDateFormat("EEEE", Locale.US).format(Date())
        speak("Current time is $time, on $day, Sir.")
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

    // --- QUẢN LÝ NOTIFICATION ---
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Jarvis", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent("ACTION_TOGGLE_JARVIS").apply {
            // Thêm dòng này để chỉ đích danh app mình nhận, tăng tính bảo mật
            setPackage(packageName)
        }

        val toggleIntent = PendingIntent.getBroadcast(
            this,
            0,
            intent,
            // FLAG_IMMUTABLE là bắt buộc trên Android hiện đại
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (isPaused) "Jarvis is sleeping" else "Jarvis is listening..."

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis Assistant")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(android.R.drawable.ic_media_pause, if (isPaused) "WAKE UP" else "SLEEP", toggleIntent)
            .setOngoing(true).build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun toggleFlashlight(status: Boolean) {
        try {
            val cameraId = cameraManager?.cameraIdList?.get(0) ?: return

            // Thực thi lệnh bật/tắt vật lý
            cameraManager?.setTorchMode(cameraId, status)
            isFlashOn = status
            Log.d("JARVIS_DEBUG", "Flashlight status: $status")

            // Xóa các lệnh hẹn giờ cũ để reset bộ đếm
            flashHandler.removeCallbacksAndMessages(null)

            if (status) {
                // Nếu bật đèn, thiết lập tự động tắt sau 60 giây
                flashHandler.postDelayed({
                    if (isFlashOn) {
                        toggleFlashlight(false) // Tự gọi lại hàm để tắt đèn vật lý

                        // CHỈ DÙNG 1 CÂU DUY NHẤT THEO YÊU CẦU
                        speak("Safety timeout reached. Powering down the flashlight.")
                        Log.d("JARVIS_DEBUG", "Flash tự động tắt sau 60 giây do an toàn.")
                    }
                }, 60000L)
            }
        } catch (e: Exception) {
            Log.e("JARVIS_DEBUG", "Lỗi Flash: ${e.message}")
        }
    }

    private fun speak(text: String) {
        // Sử dụng Bundle để đánh dấu độ ưu tiên của câu nói
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        // QUEUE_FLUSH giúp hủy các câu nói cũ đang chờ, nói câu mới ngay lập tức
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "JarvisUtterance")
    }

    override fun onDestroy() {
        isRunning = false
        unregisterReceiver(notificationReceiver)
        wakeLock?.release()
        audioRecord?.stop(); audioRecord?.release()
        porcupine?.delete(); rhino?.delete()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}