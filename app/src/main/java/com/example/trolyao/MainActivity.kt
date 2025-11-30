package com.example.trolyao

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    // 1. Đăng ký bộ lắng nghe kết quả khi xin quyền Overlay (Vẽ đè)
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Khi người dùng quay lại từ cài đặt, kiểm tra lại xem đã cấp chưa
        if (Settings.canDrawOverlays(this)) {
            checkRuntimePermissions()
        } else {
            Toast.makeText(this, "Cần quyền 'Hiển thị trên ứng dụng khác' để hoạt động!", Toast.LENGTH_LONG).show()
        }
    }

    // 2. Đăng ký bộ lắng nghe kết quả khi xin quyền Mic & Thông báo
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Kiểm tra xem tất cả quyền yêu cầu đã được cấp chưa
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startAssistantService()
        } else {
            Toast.makeText(this, "Cần cấp đủ quyền Micro để nghe lệnh!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bắt đầu quy trình kiểm tra quyền ngay khi mở app
        checkOverlayPermission()
    }

    // Bước 1: Kiểm tra quyền vẽ đè
    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Vui lòng cấp quyền 'Hiển thị trên ứng dụng khác'", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            // Dùng launcher mới thay cho startActivityForResult
            overlayPermissionLauncher.launch(intent)
        } else {
            // Nếu có rồi thì qua bước 2
            checkRuntimePermissions()
        }
    }

    // Bước 2: Kiểm tra quyền Mic và Thông báo
    private fun checkRuntimePermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // Luôn cần quyền Mic
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        // Quyền Thông báo (Chỉ cần cho Android 13/SDK 33 trở lên)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            // Xin các quyền còn thiếu
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            // Đã đủ hết quyền -> Chạy Service
            startAssistantService()
        }
    }

    private fun startAssistantService() {
        val intent = Intent(this, AssistantService::class.java)
        // Vì minSdk của bạn >= 29 nên không cần check if version O nữa
        startForegroundService(intent)
        Toast.makeText(this, "Trợ lý ảo đã bắt đầu!", Toast.LENGTH_SHORT).show()
    }
}