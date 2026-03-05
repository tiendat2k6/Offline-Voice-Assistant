package com.example.trolyao

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import android.widget.LinearLayout
import android.view.Gravity
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

import android.os.PowerManager

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.gravity = Gravity.CENTER

        val btnSetup = Button(this)
        btnSetup.text = "CẤP QUYỀN ĐẦY ĐỦ"
        btnSetup.setOnClickListener { checkAllPermissions() }

        val btnStop = Button(this)
        btnStop.text = "MỞ CÀI ĐẶT TRỢ NĂNG"
        btnStop.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                val serviceName = ComponentName(packageName, AssistantService::class.java.name).flattenToString()

                // Thêm flag và dữ liệu định danh dịch vụ
                putExtra("android.provider.extra.SERVICE_COMPONENT_NAME", serviceName) // Thử dùng Key chuẩn theo tài liệu Android chính thức
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }

        layout.addView(btnSetup)
        layout.addView(btnStop)
        setContentView(layout)

        checkAllPermissions()
    }

    private fun checkAllPermissions() {

        // 2. Quyền Ghi âm
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            return
        }

        // 3. Quyền Camera (Để bật đèn Flash)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 104)
            return
        }

        // 4. Quyền Thông báo (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
                return
            }
        }

        // 5. Quyền Vẽ đè (Overlay)
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Cấp quyền hiển thị trên ứng dụng khác", Toast.LENGTH_SHORT).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            return
        }

        Toast.makeText(this, "ĐÃ ĐỦ QUYỀN! HÃY BẬT SERVICE.", Toast.LENGTH_SHORT).show()
    }
}
