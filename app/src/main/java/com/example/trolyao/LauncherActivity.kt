package com.example.trolyao

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class LauncherActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Tạo Intent kích hoạt Google Assistant
        val assistantIntent = Intent(Intent.ACTION_VOICE_COMMAND)
        assistantIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

        try {
            startActivity(assistantIntent)
        } catch (e: Exception) {
            e.printStackTrace() // Phòng trường hợp máy không có Google App
        }

        // 2. Tắt ngay Activity này để trả lại màn hình cho Assistant
        finish()

        // 3. Loại bỏ hiệu ứng chuyển cảnh để trông như không có gì xảy ra
        overridePendingTransition(0, 0)
    }
}