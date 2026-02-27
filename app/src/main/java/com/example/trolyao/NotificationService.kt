package com.example.trolyao

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Kiểm tra nếu gói dữ liệu bị rỗng
        if (sbn == null) return

        // lấy tên package (id của ứng dụng gửi thông báo)
        val packageName = sbn.packageName

        // truy cập vào "kho hành lý" extras của thông báo
        val extras = sbn.notification.extras

        // bóc tách tên người gửi
        val title = extras.getString(Notification.EXTRA_TITLE) // tên người gửi

        // lọc: chỉ đọc nếu là Zalo, Messenger.
        val targetApps = listOf("com.zing.zalo", "com.facebook.orca")

        // xử lý
        if (packageName in targetApps && !title.isNullOrEmpty()) {
            JarvisVoice.speak("you have meessage from $title, sir.", isInterrupt = false)
        }
    }
}