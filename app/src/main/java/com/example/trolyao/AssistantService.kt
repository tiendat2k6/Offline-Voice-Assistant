package com.example.trolyao

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.Porcupine
import android.content.pm.ServiceInfo

class AssistantService : AccessibilityService() {

    private var porcupineManager: PorcupineManager? = null
    // Nhớ đảm bảo BuildConfig đã import đúng package
    private val accessKey = BuildConfig.SECRET_KEY

    private val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"

    private val handler = Handler(Looper.getMainLooper())

    // Biến cờ để biết mic đang bật hay tắt
    private var isMicOn = false

    override fun onServiceConnected() {
        super.onServiceConnected()

        try {
            // 1. Notification (Giữ Service sống)
            createNotificationChannel()
            val notification = NotificationCompat.Builder(this, "AssistantChannel")
                .setContentTitle("Trợ lý ảo đang trực chiến")
                .setContentText("Jarvis đang nghe...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(1, notification)
            }

            // 2. Khởi tạo Porcupine
            initPorcupine()

        } catch (e: Exception) {
            Log.e("Assistant", "Lỗi Service: ${e.message}")
        }
    }

    private fun initPorcupine() {
        try {
            val keywords = arrayOf(Porcupine.BuiltInKeyword.JARVIS, Porcupine.BuiltInKeyword.TERMINATOR)
            val sensitivities = floatArrayOf(0.7f, 0.7f)

            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywords(keywords)
                .setSensitivities(sensitivities)
                .build(applicationContext) { keywordIndex ->
                    onWakeWordDetected(keywordIndex)
                }

            // Gọi hàm bật mic an toàn
            startMicSafe()

        } catch (e: Exception) {
            Log.e("Assistant", "Lỗi init Porcupine: ${e.message}")
        }
    }

    // --- HÀM BẬT MIC "BẤT TỬ" ---
    private fun startMicSafe() {
        if (isMicOn) return // Đang bật rồi thì thôi

        handler.postDelayed({
            try {
                porcupineManager?.start()
                isMicOn = true
                Log.d("Assistant", "--> Đã bật lại Mic thành công!")
            } catch (e: Exception) {
                Log.w("Assistant", "Mic đang bận (Google đang dùng?), thử lại sau 1s...")
                isMicOn = false
                // ĐỆ QUY: Nếu lỗi, đợi 1 giây rồi tự gọi lại chính nó
                startMicSafe()
            }
        }, 1000) // Delay 1s trước khi thử
    }

    // --- HÀM TẮT MIC ---
    private fun stopMic() {
        try {
            porcupineManager?.stop()
            isMicOn = false
            Log.d("Assistant", "--> Đã tạm dừng Mic")
        } catch (e: Exception) {}
    }

    private fun onWakeWordDetected(index: Int) {
        // 1. Dừng mic ngay lập tức để nhường cho Google/System
        stopMic()

        if (index == 0) {
            Log.i("Assistant", "JARVIS -> Mở Google")
            openGoogleAssistant()
        }
        else if (index == 1) {
            Log.i("Assistant", "TERMINATOR -> Tắt màn hình")
            performLock()
            // Tắt màn hình xong thì bật lại mic ngay
            startMicSafe()
        }
    }

    private fun openGoogleAssistant() {
        val intent = Intent(Intent.ACTION_VOICE_COMMAND)
        intent.setPackage(GOOGLE_PACKAGE)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
        try {
            startActivity(intent)
        } catch (e: Exception) {}

        // Hủy các lệnh hẹn giờ cũ để tránh chồng chéo
        handler.removeCallbacks(dismissTask)
        // Hẹn giờ Auto-back sau 4 giây
        handler.postDelayed(dismissTask, 4000)
    }

    private fun performLock() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val result = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                if (!result) performGlobalAction(GLOBAL_ACTION_BACK)
            } else {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        } catch (e: Exception) {}
    }

    // --- LOGIC TỰ ĐỘNG THOÁT GOOGLE ---
    private val dismissTask = Runnable {
        Log.d("Assistant", "Hết giờ chờ -> Bấm Back")

        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        // Chỉ Back nếu đang KHÔNG khóa màn hình
        if (!keyguardManager.isKeyguardLocked) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }

        // QUAN TRỌNG: Google xong việc rồi -> Bật lại Mic
        startMicSafe()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return

        if (packageName == GOOGLE_PACKAGE) {
            // Google đang hoạt động -> Gia hạn thời gian sống
            handler.removeCallbacks(dismissTask)
            handler.postDelayed(dismissTask, 4000)
        } else {
            // Đã sang app khác
            handler.removeCallbacks(dismissTask)

            // Nếu mic đang tắt (do lúc nãy gọi Jarvis), thì giờ phải bật lại ngay
            if (!isMicOn) {
                startMicSafe()
            }
        }
    }

    override fun onInterrupt() {}

    private fun createNotificationChannel() {
        val channel = android.app.NotificationChannel(
            "AssistantChannel", "Trạng thái Trợ lý", android.app.NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        stopMic()
        porcupineManager?.delete()
        super.onDestroy()
    }
}