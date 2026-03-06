package com.example.trolyao

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.trolyao.JarvisVoice.speak
import java.text.Normalizer
import java.util.regex.Pattern

class NotificationService : NotificationListenerService() {

    // lưu thời điểm cuối cùng đọc thông báo của từng người gửi
    // key: tên người gửi (Title), value: thời gian (ms)
    private val lastReadMap = mutableMapOf<String, Long>()

    // khoảng cách thời gian tối thiểu giữa 2 lần đọc
    private val COOLDOWN_TIME = 30000L

    // lọc: chỉ đọc nếu là Zalo, Messenger, Gmail
    private val targetApps = listOf("com.zing.zalo", "com.facebook.orca", "com.google.android.gm")

    private val relationshipMap = mapOf(
        "Mẹ" to "your mother",
        "Bố" to "your father",
        "Thien Quang" to "your student",
        "Nhóm: Gia đình mình" to "your family group"
    )

    fun removeAccents(input: String): String {
        val temp = Normalizer.normalize(input, Normalizer.Form.NFD)
        val pattern = Pattern.compile("\\p{InCombiningDiacriticalMarks}+")
        return pattern.matcher(temp).replaceAll("").replace('đ', 'd').replace('Đ', 'D')
    }

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

        // nếu không phải thông báo từ những app mình cần thì thoát
        if (packageName !in targetApps)
            return

        // truy cập vào "kho hành lý" extras của thông báo
        val extras = sbn.notification.extras

        // bóc tách tên người gửi
        val rawTitle = extras.getString(Notification.EXTRA_TITLE) ?: "Someone"

        // Tìm kiếm thông minh: nếu title chứa từ khóa trong Map (ví dụ "Mẹ (2 tin nhắn))
        val matchedKey = relationshipMap.keys.find {rawTitle.contains(it) }
        val finalTitle = if (matchedKey != null) relationshipMap[matchedKey] else removeAccents(rawTitle)


        // xử lý
        val currentTime = System.currentTimeMillis()
        val lastTime = lastReadMap[finalTitle] ?: 0L

        // kiểm tra nếu không phải spam thì đọc
        if (currentTime - lastTime > COOLDOWN_TIME) {
            speak("Sir, you have a message from $finalTitle.", isInterrupt = false)

            // xoá dữ liệu để tránh tràn
            if (lastReadMap.size > 100) lastReadMap.clear()
            // cập nhật lại thời điểm đọc cuối cùng cho người này
            lastReadMap[finalTitle!!] = currentTime
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