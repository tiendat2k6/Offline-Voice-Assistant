package com.example.trolyao

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Tạo giao diện đơn giản bằng code (khỏi lo file layout lỗi)
        val layout = android.widget.LinearLayout(this)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.gravity = android.view.Gravity.CENTER

        val btnSetup = Button(this)
        btnSetup.text = "BƯỚC 1: CẤP QUYỀN & BẬT SERVICE"
        btnSetup.setOnClickListener { checkPermissions() }

        val btnStop = Button(this)
        btnStop.text = "KHẨN CẤP: TẮT SERVICE (NẾU LỖI)"
        btnStop.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Tìm 'TroLyAo' và TẮT nó đi", Toast.LENGTH_LONG).show()
        }

        layout.addView(btnSetup)
        layout.addView(btnStop)
        setContentView(layout)

        // Tự động kiểm tra quyền khi mở app
        checkPermissions()
    }

    private fun checkPermissions() {
        // 1. Quyền Ghi âm (QUAN TRỌNG NHẤT)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            return
        }

        // 2. Quyền Thông báo (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
                return
            }
        }

        // 3. Quyền Vẽ đè (Overlay) - Để hiện thông báo
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Cần quyền hiển thị trên ứng dụng khác", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, 103)
            return
        }

        // 4. Kiểm tra xem Service đã bật trong Cài đặt chưa
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "HÃY BẬT 'TroLyAo' TRONG CÀI ĐẶT!", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        } else {
            Toast.makeText(this, "Đã sẵn sàng! Hãy thử nói 'Jarvis'", Toast.LENGTH_SHORT).show()
        }
    }

    // Hàm kiểm tra Service đã bật chưa
    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (service in enabledServices) {
            if (service.resolveInfo.serviceInfo.packageName == packageName) return true
        }
        return false
    }

    // Xử lý khi người dùng bấm 'Cho phép'
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            checkPermissions() // Kiểm tra tiếp các quyền còn lại
        } else {
            Toast.makeText(this, "Không có quyền Mic thì app vô dụng!", Toast.LENGTH_LONG).show()
        }
    }
}