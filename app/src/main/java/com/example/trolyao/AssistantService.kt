package com.example.trolyao

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.Porcupine

class AssistantService : AccessibilityService() {

    private var porcupineManager: PorcupineManager? = null
    private val accessKey = BuildConfig.SECRET_KEY
    private val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    private var isPaused = false
    private var isMicOn = false

    private val ACTION_TOGGLE = "com.example.trolyao.ACTION_TOGGLE"
    private val CHANNEL_ID = "Assistant_Final_Fix_Net_Error"

    // DANH SÁCH NGƯỜI NHÀ (Để không bị Auto Back nhầm)
    private val SAFE_PACKAGES = listOf(
        GOOGLE_PACKAGE,
        "com.android.systemui",
        "com.miui.home",
        "android",
        "com.android.settings",
        "com.miui.securitycenter"
    )

    @SuppressLint("WakelockTimeout")
    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TroLyAo::WakeLock")
            wakeLock?.acquire(120 * 60 * 1000L) // Giữ CPU thức

            createNotificationChannel()
            initPorcupine()
            updateNotification()

        } catch (e: Exception) {
            Log.e("Assistant", "Lỗi khởi động: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE) {
            if (isPaused) resumeService() else pauseService()
        }
        return START_STICKY
    }

    private fun pauseService() {
        isPaused = true
        stopMic()
        handler.removeCallbacks(dismissTask)
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) {}
        updateNotification()
    }

    private fun resumeService() {
        isPaused = false
        try { if (wakeLock?.isHeld == false) wakeLock?.acquire(120 * 60 * 1000L) } catch (e: Exception) {}
        startMicSafe()
        updateNotification()
    }

    @SuppressLint("ForegroundServiceType")
    private fun updateNotification() {
        val title = if (isPaused) "Jarvis đang tạm dừng" else "Jarvis đang hoạt động"
//        val content = if (isPaused) "Đã tắt mic. Nhấn để BẬT LẠI" else "Nói 'Jarvis' hoặc 'Terminator'"
        val content = if (isPaused) "" else ""

        val btnIcon = if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        val btnText = if (isPaused) "BẬT LẠI" else "TẠM DỪNG"

        val toggleIntent = Intent(this, AssistantService::class.java).apply { action = ACTION_TOGGLE }
        val togglePendingIntent = PendingIntent.getService(
            this, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .addAction(btnIcon, btnText, togglePendingIntent)
            .setStyle(mediaStyle)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val type = if (isPaused) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(1, notification, type)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            try { startForeground(1, notification) } catch (e2: Exception) {}
        }
    }

    private fun initPorcupine() {
        try {
            val keywords = arrayOf(Porcupine.BuiltInKeyword.JARVIS, Porcupine.BuiltInKeyword.TERMINATOR)
            val sensitivities = floatArrayOf(0.8f, 0.9f)

            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywords(keywords)
                .setSensitivities(sensitivities)
                .build(applicationContext) { keywordIndex ->
                    if (!isPaused) onWakeWordDetected(keywordIndex)
                }
            startMicSafe()
        } catch (e: Exception) {
            porcupineManager = null
        }
    }

    private fun startMicSafe() {
        if (isMicOn || isPaused) return
        if (porcupineManager == null) return

        handler.postDelayed({
            try {
                if (porcupineManager != null) {
                    porcupineManager?.start()
                    isMicOn = true
                    Log.d("Assistant", "Mic BẬT")
                }
            } catch (e: Exception) {
                isMicOn = false
                // Log.w("Assistant", "Mic bận, thử lại sau 1s...")
                if (!isPaused) startMicSafe()
            }
        }, 1000)
    }

    private fun stopMic() {
        try { porcupineManager?.stop(); isMicOn = false } catch (e: Exception) {}
    }

    private fun onWakeWordDetected(index: Int) {
        stopMic() // 1. Tắt Mic ngay
        if (index == 0) {
            openGoogleAssistant() // 2. Gọi Google (Có delay bên trong)
        } else if (index == 1) {
            performLock()
            startMicSafe()
        }
    }

    private fun openGoogleAssistant() {
        Log.d("Assistant", "--> Đợi 300ms để nhả Mic cho Google...")

        // Delay 300ms (0.3 giây) để Android kịp thu hồi Mic từ Porcupine
        handler.postDelayed({
            try {
                val intent = Intent(Intent.ACTION_VOICE_COMMAND)
                intent.setPackage(GOOGLE_PACKAGE) // set cứng thì để, không thì bỏ
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("Assistant", "Lỗi mở Google: ${e.message}")
            }
        }, 300)

        // đặt hẹn mới 15 giây (Tính từ lúc gọi)
        handler.removeCallbacks(dismissTask)
        handler.postDelayed(dismissTask, 15300) // 15s + 300ms delay
    }

    private fun performLock() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (!performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)) performGlobalAction(GLOBAL_ACTION_BACK)
            } else performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (e: Exception) {}
    }

    private val dismissTask = Runnable {
        // Chốt chặn cuối cùng: Nếu đang Pause thì không Back
        if (isPaused) {
            startMicSafe()
            return@Runnable
        }

        Log.d("Assistant", "Hết giờ -> BACK")
        performGlobalAction(GLOBAL_ACTION_BACK)
        startMicSafe()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (isPaused) return

        val packageName = event?.packageName?.toString() ?: return

        // Nếu là người nhà -> Kệ cho đồng hồ chạy
        if (SAFE_PACKAGES.contains(packageName)) {
            return
        }

        // Nếu là App Lạ -> Hủy lệnh Back
        Log.d("Assistant", "Gặp App lạ ($packageName) -> Hủy lệnh Back")
        handler.removeCallbacks(dismissTask)

        if (!isMicOn && !isPaused) startMicSafe()
    }

    override fun onInterrupt() {}

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "Trợ lý ảo (Fix Net Error)",
                android.app.NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "Hiện nút bật tắt mic"
            getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) {}
        stopMic()
        porcupineManager?.delete()
        super.onDestroy()
    }
}