package com.example.trolyao

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.Porcupine
import android.util.Log
import android.content.Context
import android.content.pm.ServiceInfo // Cần thêm import này cho Android 14

class AssistantService : Service() {
    private var porcupineManager: PorcupineManager? = null
    private val accessKey = BuildConfig.SECRET_KEY

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val notification = NotificationCompat.Builder(this, "AssistantChannel")
            .setContentTitle("Trợ lý ảo đang lắng nghe...")
            .setContentText("Nói 'Jarvis' để kích hoạt") // Thêm dòng này cho rõ
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        // Cập nhật cho Android 14 (Nếu target SDK 34)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }

        initPorcupine()
    }

    private fun initPorcupine() {
        try {
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                .setSensitivity(0.7f)
                .build(applicationContext) { _ ->
                    // Sửa cảnh báo: đổi keywordIndex thành _
                    onWakeWordDetected()
                }
            porcupineManager?.start()
            Log.d("Assistant", "Porcupine started")
        } catch (e: Exception) {
            Log.e("Assistant", "Lỗi khởi tạo Porcupine: ${e.message}")
        }
    }

//    private fun onWakeWordDetected() {
//        Log.i("Assistant", "Đã nghe thấy lệnh JARVIS!")
//
//        // LƯU Ý: Phải có quyền SYSTEM_ALERT_WINDOW thì lệnh này mới chạy được từ background
//        val launchIntent = Intent(this, LauncherActivity::class.java)
//        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
//        startActivity(launchIntent)
//    }

    private fun onWakeWordDetected() {
        Log.i("Assistant", "Đã nghe thấy lệnh JARVIS!")

        // --- ÉP ÂM LƯỢNG XUỐNG ---
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

        // Lấy mức âm lượng tối đa của máy
        val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)

        // Tính mức âm lượng mong muốn (ví dụ: 50%)
        val targetVol = (maxVol * 0.5).toInt()

        // Ép luồng Nhạc (Music) xuống 50%
        audioManager.setStreamVolume(
            android.media.AudioManager.STREAM_MUSIC,
            targetVol,
            0 // 0 nghĩa là không hiện cái thanh volume lên màn hình cho đỡ vướng
        )

        // Ép luôn luồng Trợ năng (Accessibility) xuống cho chắc cốp
        audioManager.setStreamVolume(
            android.media.AudioManager.STREAM_ACCESSIBILITY,
            targetVol,
            0
        )
        //-----------------------------------------------------------------

        // 1. Tạo lệnh gọi thẳng Google Assistant
        val intent = Intent(Intent.ACTION_VOICE_COMMAND)

        // 2. Chỉ định rõ gọi Google App (để không bị hỏi Google hay Copilot)
        intent.setPackage("com.google.android.googlequicksearchbox")

        // 3. Cờ bắt buộc khi gọi từ Service
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION

        try {
            // 4. Bắn lệnh đi ngay lập tức
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("Assistant", "Lỗi: Không thể mở Google Assistant. ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Nếu service bị hệ thống giết, tự động khởi động lại
        return START_STICKY
    }

    override fun onDestroy() {
        porcupineManager?.stop()
        porcupineManager?.delete()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        // Đã xóa check SDK >= O vì project của bạn minSdk cao hơn rồi
        val channel = NotificationChannel(
            "AssistantChannel",
            "Trạng thái Trợ lý ảo", // Tên hiển thị cho user thấy trong settings
            NotificationManager.IMPORTANCE_LOW // Low để không rung/kêu mỗi khi bật service
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}