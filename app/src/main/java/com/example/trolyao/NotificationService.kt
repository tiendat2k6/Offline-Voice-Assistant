package com.example.trolyao

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.trolyao.JarvisVoice.speak

class NotificationService : NotificationListenerService() {

    // lưu thời điểm cuối cùng đọc thông báo của từng người gửi
    // key: tên người gửi (Title), value: thời gian (ms)
    private val lastReadMap = mutableMapOf<String, Long>()

    // khoảng cách thời gian tối thiểu giữa 2 lần đọc
    private val COOLDOWN_TIME = 30000L

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Kiểm tra nếu gói dữ liệu bị rỗng
        val notification = sbn?.notification ?: return

        // loại bỏ thông báo đang diễn ra (cuộc gọi, trình phát nhạc, ...)
        if (sbn.isOngoing) return

        // loại bỏ thông báo tóm tắt (group summary) của hệ thống
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        // -------------------------------------------------------------------------

        // lấy tên package (id của ứng dụng gửi thông báo)
        val packageName = sbn.packageName

        // truy cập vào "kho hành lý" extras của thông báo
        val extras = sbn.notification.extras

        // bóc tách tên người gửi
        val title = extras.getString(Notification.EXTRA_TITLE) // tên người gửi

        // lọc: chỉ đọc nếu là Zalo, Messenger, Gmail
        val targetApps = listOf("com.zing.zalo", "com.facebook.orca", "com.google.android.gm")

        // xử lý
        if (packageName in targetApps && !title.isNullOrEmpty()) {
            val currentTime = System.currentTimeMillis()
            val lastTime = lastReadMap[title] ?: 0L

            // kiểm tra nếu không phải spam thì đọc
            if (currentTime - lastTime > COOLDOWN_TIME) {
                speak("you have meessage from $title, sir.", isInterrupt = false)

                // xoá dữ liệu để tránh tràn
                if (lastReadMap.size > 100) lastReadMap.clear()
                // cập nhật lại thời điểm đọc cuối cùng cho người này
                lastReadMap[title] = currentTime
            }
        }
//        else if (packageName !in targetApps) {
//            val appName = try {
//                val info = packageManager.getApplicationInfo(packageName, 0)
//                packageManager.getApplicationLabel(info).toString()
//            } catch (e: Exception) {
//                "Unknown App"
//            }
//
//            speak("Sir, $appName is requesting your attention.")
//        }
    }
}