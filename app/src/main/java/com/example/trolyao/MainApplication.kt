package com.example.trolyao

import android.app.Application

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Khởi tạo JarvisVoice ở đây , tạm thời không cần vì đã khởi tạo ở AssistantService.kt
//        JarvisVoice.init(this)
    }
}