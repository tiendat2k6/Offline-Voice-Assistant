package com.example.trolyao

import android.app.Application

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Khởi tạo JarvisVoice ở đây
        JarvisVoice.init(this)
    }
}