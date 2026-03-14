package com.example.trolyao

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.rhino.Rhino
import ai.picovoice.rhino.RhinoInference
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar

import android.graphics.Color
import android.graphics.PixelFormat
import android.view.KeyEvent

import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import android.speech.tts.UtteranceProgressListener
import com.example.trolyao.JarvisVoice.speak


class AssistantService : AccessibilityService() {

    private val accessKey = BuildConfig.SECRET_KEY
    private val RHINO_MODEL_FILE = "Jarvis_Commands_en_android_v4_0_0.rhn"
    private val CHANNEL_ID = "Jarvis_Channel"
    private val NOTIFICATION_ID = 123

    private var porcupine: Porcupine? = null
    private var rhino: Rhino? = null
    private var audioRecord: AudioRecord? = null
    private var cameraManager: CameraManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var isRunning = false
    private var useRhino = false
    private var isPaused = false
    private var isFlashOn = false

    private val handler = Handler(Looper.getMainLooper())
    private val systemTaskHandler = Handler(Looper.getMainLooper())

    private val flashHandler = Handler(Looper.getMainLooper())

    private val PC_COMMAND_URL = "https://juanita-tragicomic-nonethereally.ngrok-free.dev/lockdown" // Domain Ngrok
    private val AUTH_TOKEN = "Jarvis-K20-FPT-2026-ngocminh-tiendat" // Mã token bí mật

    private var windowManager: WindowManager? = null
    private var blueScreenView: FrameLayout? = null
    private var isLocked = false

    // quản lý View mặt trước
    private var frontLightView: FrameLayout? = null

    // Biến trạng thái kiểm tra blackout_node.py
    private var isBlackoutRunning = false

    // Biến đếm đã nói sai bao nhiêu câu lệnh
    private var numberOfUnrecognized = 0

    // --- 1. TÍNH NĂNG ĐIỀU KHIỂN QUA THÔNG BÁO ---
    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "ACTION_TOGGLE_JARVIS") {
                isPaused = !isPaused
                updateNotification()
                if (isPaused) {
                    speak("Entering hibernate mode.")
                    Log.d("JARVIS_DEBUG", "Hệ thống tạm dừng (Sleep)")
                } else {
                    val report = "<speak>" +
                            "Neural link established. " +
                            "<prosody rate='1.2'>How can I help you, Sir?</prosody> " +
                        "</speak>"

                    speak(report)
                    Log.d("JARVIS_DEBUG", "Hệ thống hoạt động trở lại")
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        JarvisVoice.init(applicationContext) { success ->
            if (success) {
                startupGreeting() // chào khi tts đã sẵn sàng
            }
        }

        // 1. PHẢI GỌI HÀM NÀY ĐẦU TIÊN ĐỂ MỞ CỔNG THÔNG BÁO
        createNotificationChannel()


        // 2. BIẾN JARVIS THÀNH FOREGROUND SERVICE ĐỂ KHÔNG BỊ DIỆT
        // Điều này sẽ hiện cái thông báo "Jarvis is listening..." lên ngay lập tức
        startForeground(NOTIFICATION_ID, buildNotification())

        initPicovoice() // Jarvis sẽ bắt đầu nghe ngay khi model load xong

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

        Log.d("JARVIS_DEBUG", "Đang khởi tạo TTS và Picovoice song song...")
    }

    // Hàm chào hỏi lúc mới bật Service
    private fun startupGreeting() {
        val report = "<speak>" +
                "<prosody pitch='-10%'>All <emphasis level='strong'>systems</emphasis> active.</prosody> " +
                "<break time='400ms'/>" +
                "<prosody rate='1.25'> <emphasis level='moderate'>Welcome</emphasis> <emphasis level='reduced'>back</emphasis>, </prosody>" + // Dấu phẩy tạo độ nghỉ tự nhiên cho "back"
                "<prosody pitch='-15%'>sir.</prosody>" + // Hạ thấp tone ở cuối để nghe "Jarvis" hơn
                "</speak>"

        speak(report)
    }


    private fun initPicovoice() {
        try {
            porcupine = Porcupine.Builder()
                .setAccessKey(accessKey)
                .setKeywords(arrayOf(Porcupine.BuiltInKeyword.JARVIS, Porcupine.BuiltInKeyword.TERMINATOR))
                .setSensitivities(floatArrayOf(0.7f, 0.7f))
                .build(applicationContext)

            rhino = Rhino.Builder()
                .setAccessKey(accessKey)
                .setContextPath(RHINO_MODEL_FILE)
                .setSensitivity(0.7f)
                .build(applicationContext)

            Log.d("JARVIS_DEBUG", "--- DANH SÁCH LỆNH PHẢI NÓI ĐÚNG ---")
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

        // 1. Kích hoạt AudioFX NGAY SAU KHI khởi tạo AudioRecord
        val sessionId = audioRecord?.audioSessionId ?: 0
//        if (sessionId != 0) {
//             Tự động kiểm soát mức tăng (AGC): Quan trọng để nghe tiếng THÌ THẦM/Ở XA
//            if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
//                android.media.audiofx.AutomaticGainControl.create(sessionId).apply {
//                    enabled = true
//                }
//            }
            // Khử nhiễu (NS): Lọc bớt tiếng "rì rầm" của bạn cùng phòng
//            if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
//                android.media.audiofx.NoiseSuppressor.create(sessionId).apply {
//                    enabled = true
//                }
//            }
//        }

        Thread {
            val audioBuffer = ShortArray(frameLength)
            audioRecord?.startRecording()

            while (isRunning) {
                if (isPaused) {
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
//                        Log.d("JARVIS", "rhino đã được bật trong vòng lặp")
                        val isFinalized = rhino?.process(audioBuffer) ?: false
                        if (isFinalized) {
//                            Log.d("JARVIS", "Đã lấy được âm thanh cho Rhino")
                            val inference = rhino?.getInference()
                            handler.post {
                                if (inference != null) {
//                                    Log.d("JARVIS", "Âm thanh cho Rhino khác rỗng")
                                    processRhinoInference(inference)
                                }
                                useRhino = false

                                // QUAN TRỌNG: Phải xóa cái Timer 8s ở trên đi nếu đã nhận được lệnh
                                handler.removeCallbacksAndMessages(rhinoTimeoutRunnable)
                            }
                        }
                    }
                }
            }
        }.start()
    }

    // Bộ đếm ngược 8 giây để thoát Rhino
    private val rhinoTimeoutRunnable = Runnable {
        if (useRhino) {
            useRhino = false
            Log.d("JARVIS", "Rhino Timeout - Quay lại chế độ chờ")
            speak("No signal detected, Sir.") // báo là nó đã ngừng nghe
        }
    }

    private fun acknowledgeAndSwitch() {
        val report = "<speak><prosody rate='1.28'>On your command.</prosody></speak>"
        speak(report) {
            useRhino = true // Chỉ bật Rhino khi Jarvis đã im lặng
            Log.d("JARVIS", "Đã chuyển sang Rhino trong 8s")

            // --- BẮT ĐẦU ĐẾM NGƯỢC 8 GIÂY ---
            handler.removeCallbacks(rhinoTimeoutRunnable) // Xóa các bộ đếm cũ (nếu có)
            handler.postDelayed(rhinoTimeoutRunnable, 8000)

            Log.d("JARVIS", "Đã bật Rhino - Hạn chót: 8 giây")
        }
    }

    private fun processRhinoInference(inference: RhinoInference) {
        if (inference.isUnderstood) {
            Log.i("JARVIS_DEBUG", "Nhận diện Intent: ${inference.intent}")

            // reset lại biến đếm sai
            numberOfUnrecognized = 0

            when (inference.intent) {
                "time" -> readCurrentTimePro()

                "light" -> {
                    isFlashOn = !isFlashOn
                    toggleFrontLight(isFlashOn)

                    val response = if (isFlashOn) {
                        "Engaging optical matrix, Sir." // Đang kích hoạt ma trận quang học
                    } else {
                        "Primary array offline."       // Mảng sơ cấp đã ngoại tuyến
                    }

                    speak(response)
                }

                "trigger_relax" -> startRelaxTimer()

                "override" -> startOverrideMode()

                "lockdown" -> triggerPcBlackout()

                "trigger_assistant" -> {
                    speak("Invoking core.")
                    handler.postDelayed({
                        openGoogleAssistant()
                    }, 1000)
                }
            }
        } else {
            // Rhino không khớp được âm thanh với bất kỳ Expression nào trong YAML
            Log.w("JARVIS_DEBUG", "Rhino không hiểu lệnh. Expression không khớp.")

            // tăng số lần nói sai.
            numberOfUnrecognized++
            if (numberOfUnrecognized >= 2) {
                isPaused = true
                updateNotification()

                numberOfUnrecognized = 0

                val report = "<speak>" +
                        "<emphasis level='strong'>Unauthorized access detected.</emphasis> " +
                        "<break time='600ms'/> " +
                        "<prosody pitch='-15%' rate='90%'>You are not my master.</prosody> " +
                        "<break time='1000ms'/> " +
                        "<prosody rate='92%' pitch='-10%'>" +
                        "Intruder metadata has been permanently " +
                        "<emphasis level='moderate'>logged</emphasis> and synchronized." +
                        "</prosody>" +
                        "</speak>"

                speak(report)
            }
            else
                speak("Input unrecognized, sir.")
        }
    }

    // --- Black Out trên redmi 9t: VẼ ĐÈ MÀN HÌNH ---
    fun triggerPhoneBlackout(enable: Boolean) {
        isLocked = enable
        if (enable) {
            if (blueScreenView != null) return

            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            blueScreenView = FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK) // Nền đen làm nổi bật ánh sáng xanh
                isFocusable = true
                isFocusableInTouchMode = true

                // FIX LỖI 266: Thêm performClick để Android không báo lỗi vàng nữa
                setOnTouchListener { v, _ ->
                    v.performClick()
                    true // Nuốt sạch cú chạm/vuốt
                }
            }

            // TextView: Chữ xanh Neon + Phát sáng xanh Neon
            val textView = TextView(this).apply {
                text = "PNM"
                textSize = 100f
                setTextColor(Color.parseColor("#39FF14"))

                // 1. Dùng font Sans-Serif mặc định (không phải condensed) để chữ rộng hơn
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)

                // 2. GIÃN CHỮ RỘNG RA (Positive Letter Spacing): Tạo cảm giác cao cấp và hiện đại
                letterSpacing = 0.25f

                gravity = Gravity.CENTER
                setShadowLayer(35f, 0f, 0f, Color.parseColor("#39FF14"))
            }

            blueScreenView?.addView(textView, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or // Đè Lockscreen
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD, // or // Khóa phím
//                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            )

            // Tràn qua tai thỏ Redmi 9T
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            windowManager?.addView(blueScreenView, params)
        } else {
            blueScreenView?.let { windowManager?.removeView(it); blueScreenView = null }

            performLock()  // KHÓA MÁY: Ép Android quay về Lockscreen
        }
    }

    // chặn home, back, recent. Nhưng vẫn cho phép sử dụng âm lượng
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (isLocked) {
            // Nếu là phím tăng/giảm âm lượng thì cho phép hệ thống xử lý
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                return false // Trả về false để Android tự chỉnh volume
            }

            // Còn lại (Back, Recent, v.v.) thì nuốt sạch
            return true
        }
        return super.onKeyEvent(event)
    }

    private fun triggerPcBlackout() {
        if (isBlackoutRunning == true) {
            speak("Blackout sequence is currently active. All external access remains restricted, Sir.")
            return
        }

        isBlackoutRunning = true

        // Thông báo trạng thái qua TTS
        // speak("Protocol Blackout initiated... Target neutralized, sir.")

        val report = "<speak>" +
                "Dopamine receptors stabilizing. <break time=\"800ms\"/> Neuro-reset is now in progress, Sir" +
                "</speak>"

        speak(report)

        // Kích hoạt màn hình xanh trên điện thoại ngay lập tức

        triggerPhoneBlackout(true)

        // --- HẸN GIỜ 10 PHÚT (600.000 ms) ---
        handler.postDelayed({
            speak("Your mind is now optimized, sir.")

            isBlackoutRunning = false

            // Giải phóng điện thoại sau 10 phút
            triggerPhoneBlackout(false)

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

    // --- 4. TÍNH NĂNG OVERRIDE (Luôn học trong 5 phút) ---
    private fun startOverrideMode() {
        systemTaskHandler.removeCallbacksAndMessages(null)

        // Câu lệnh bắt đầu: Thể hiện sự nghiêm túc
        val report = "<speak>" +
                "Manual override engaged. <break time='400ms'/>" +
                "<prosody rate='0.9' pitch='-10%'> <emphasis level='moderate'>accelerate</emphasis> execution to <emphasis level='moderate'>maximum</emphasis>,</prosody> " +
                "<break time='200ms'/>Sir." +
                "</speak>"

        speak(report)

        // Hẹn giờ 5 phút (300,000 ms)
        systemTaskHandler.postDelayed({
            Log.d("Assistant", "Hết 5 phút Override!")

            // Thông báo sau 5 phút
            speak("Momentum detected. Shall we maintain this flow, Sir?")
//            speak("The boredom barrier has been breached. Resistance levels have stabilized.") {
//                // đợi 1 giây sau khi nói xong câu trên
//                handler.postDelayed({
//                    speak("Momentum detected. Shall we maintain this flow, Sir?")
//                }, 800)
//            }
        }, 300000)
    }

    // --- 5. TÍNH NĂNG RELAX (HẸN GIỜ 10 PHÚT) ---
    private fun startRelaxTimer() {
        systemTaskHandler.removeCallbacksAndMessages(null)
        speak("Relaxation protocol initiated... Enjoy your downtime, Sir.")

        // Hẹn giờ 10 phút (600,000 ms)
        systemTaskHandler.postDelayed({
            Log.d("Assistant", "Hết 10 phút Relax!")

            val report = "<speak>" + "Synaptic noise eliminated." +
                    "<break time='700ms'/>Your cognitive engine is now ready, Sir." + "</speak>"
            speak(report)

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

    private fun toggleFrontLight(status: Boolean) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        if (status) {
            if (frontLightView != null) return // Đã bật rồi thì thôi

            // Thay Color.WHITE bằng màu Ice Blue hiện đại
            frontLightView = FrameLayout(this).apply {
                setBackgroundColor(Color.parseColor("#F0F8FF"))
                alpha = 0f // Bắt đầu từ trong suốt
                animate().alpha(1f).setDuration(2000).start() // Hiện dần lên trong 2s
            }

            // 2. Thiết lập LayoutParams để đè toàn màn hình và đẩy độ sáng
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or // Giữ màn hình luôn sáng
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or   // <--- QUAN TRỌNG: Tự bật màn hình
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED, // <--- QUAN TRỌNG: Hiện đè lockscreen
                PixelFormat.TRANSLUCENT
            ).apply {
                // Đẩy độ sáng màn hình lên mức cao nhất (1.0f)
                screenBrightness = 1.0f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            try {
                wm.addView(frontLightView, params)
                isFlashOn = true
                Log.d("JARVIS_DEBUG", "Front Light Activated at Max Brightness")

                // 3. Logic Timeout 60s
                flashHandler.removeCallbacksAndMessages(null)
                flashHandler.postDelayed({
                    if (isFlashOn) {
                        JarvisVoice.speak("Safety timeout reached. Disengaging lighting systems.") {
                            toggleFrontLight(false)
                        }
                    }
                }, 60000L)
            } catch (e: Exception) {
                Log.e("JARVIS_DEBUG", "Lỗi bật đèn trước: ${e.message}")
            }

        } else {
            // TẮT ĐÈN: Giảm độ sáng từ từ rồi mới gỡ View
            frontLightView?.let { view ->
                view.animate()
                    .alpha(0f) // Giảm độ trong suốt về 0
                    .setDuration(2000) // Thời gian giảm
                    .withEndAction {
                        performLock() // khoá màn hình (tắt đèn ngay lập tức)
                        // Sau khi chạy xong hiệu ứng thì mới xóa View
                        try {
                            if (view.isAttachedToWindow) {
                                wm.removeView(view)
                            }
                        } catch (e: Exception) {
                            Log.e("JARVIS_DEBUG", "Lỗi khi gỡ View: ${e.message}")
                        }
                        frontLightView = null
                    }
                    .start()
            }

            isFlashOn = false
            Log.d("JARVIS_DEBUG", "Front Light Deactivating (Fading out...)")
            flashHandler.removeCallbacksAndMessages(null)
        }
    }

    // chào lần cuối trước khi bị android giết
    override fun onUnbind(intent: Intent?): Boolean {
        val goodbyeReport = "<speak>" +
                "<prosody pitch='-10%' rate='0.95'><emphasis level='strong'>Jarvis</emphasis> offline.</prosody>" +
                "<break time='500ms'/>" +
                "<prosody pitch='-20%' rate='1.1'><emphasis level='strong'>Good</emphasis> bye, master.</prosody>" +
                "</speak>"

        speak(goodbyeReport) {
            JarvisVoice.shutdown() // QUAN TRỌNG: Giết sạch "bóng ma" TTS
        }
        return false // Rất quan trọng: Báo cho Android không dùng lại instance này
    }

    override fun onDestroy() {
        isRunning = false
        unregisterReceiver(notificationReceiver)
        wakeLock?.release()
        audioRecord?.stop(); audioRecord?.release()
        porcupine?.delete(); rhino?.delete()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}