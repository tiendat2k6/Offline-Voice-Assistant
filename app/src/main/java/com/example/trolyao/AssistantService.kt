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
    // Dán lại Key của bạn vào đây (nếu BuildConfig bị lỗi)
    private val accessKey = BuildConfig.SECRET_KEY
    private val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    private var isPaused = false
    private var isMicOn = false

    private val ACTION_TOGGLE = "com.example.trolyao.ACTION_TOGGLE"
    // Đổi tên kênh một chút cho đỡ trùng
    private val CHANNEL_ID = "Assistant_Terminator_V1"

    @SuppressLint("WakelockTimeout")
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("Assistant", "=== SERVICE CONNECTED ===")
        try {
            // 1. Giữ CPU thức
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TroLyAo::WakeLock")
            wakeLock?.acquire(120 * 60 * 1000L) // Giữ 2 tiếng

            // 2. Tạo thông báo & Init
            createNotificationChannel()
            initPorcupine()
            updateNotification()

        } catch (e: Exception) {
            Log.e("Assistant", "CRASH tại onServiceConnected: ${e.message}")
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
        val title = if (isPaused) "Jarvis đang ngủ" else "Jarvis đang trực chiến"
        val content = if (isPaused) "Đã tắt mic. Nhấn để BẬT LẠI" else "Nói 'Jarvis' hoặc 'Terminator'"

        val btnIcon = if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        val btnText = if (isPaused) "BẬT LẠI" else "TẠM DỪNG"

        val toggleIntent = Intent(this, AssistantService::class.java).apply { action = ACTION_TOGGLE }
        val togglePendingIntent = PendingIntent.getService(
            this, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Dùng MediaStyle để hiện nút trên Xiaomi (nhớ check xem đã thêm thư viện androidx.media chưa nhé)
        // Nếu chưa thêm thư viện thì xóa dòng .setStyle(...) đi cũng được, nhưng nút sẽ bị ẩn
        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
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
            // --- ĐỔI LẠI TỪ KHÓA Ở ĐÂY ---
            val keywords = arrayOf(
                Porcupine.BuiltInKeyword.JARVIS,      // 0: Mở Google
                Porcupine.BuiltInKeyword.TERMINATOR   // 1: Tắt màn hình
            )

            val sensitivities = floatArrayOf(0.7f, 0.8f) // Terminator nhạy 0.8f

            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywords(keywords)
                .setSensitivities(sensitivities)
                .build(applicationContext) { keywordIndex ->
                    if (!isPaused) onWakeWordDetected(keywordIndex)
                }
            startMicSafe()
        } catch (e: Exception) {
            Log.e("Assistant", "Lỗi Init Porcupine: ${e.message}")
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
                Log.w("Assistant", "Mic bận, thử lại sau 1s...")
                if (!isPaused) startMicSafe()
            }
        }, 1000)
    }

    private fun stopMic() {
        try { porcupineManager?.stop(); isMicOn = false; Log.d("Assistant", "Mic TẮT") } catch (e: Exception) {}
    }

    private fun onWakeWordDetected(index: Int) {
        stopMic()
        if (index == 0) {
            Log.i("Assistant", "--> JARVIS")
            openGoogleAssistant()
        } else if (index == 1) {
            Log.i("Assistant", "--> TERMINATOR (LOCK)")
            performLock()
            startMicSafe()
        }
    }

    private fun openGoogleAssistant() {
        val intent = Intent(Intent.ACTION_VOICE_COMMAND)
        intent.setPackage(GOOGLE_PACKAGE)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
        try { startActivity(intent) } catch (e: Exception) {}

        handler.removeCallbacks(dismissTask)
        handler.postDelayed(dismissTask, 4000)
    }

    private fun performLock() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (!performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)) performGlobalAction(GLOBAL_ACTION_BACK)
            } else performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (e: Exception) {}
    }

    private val dismissTask = Runnable {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (!keyguardManager.isKeyguardLocked) performGlobalAction(GLOBAL_ACTION_BACK)
        startMicSafe()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (packageName == GOOGLE_PACKAGE) {
            handler.removeCallbacks(dismissTask)
            handler.postDelayed(dismissTask, 4000)
        } else {
            handler.removeCallbacks(dismissTask)
            if (!isMicOn && !isPaused) startMicSafe()
        }
    }

    override fun onInterrupt() {}

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "Trợ lý ảo (Terminator)",
                android.app.NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "Hiện nút bật tắt mic"
            channel.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
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