package com.example.trolyao

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.TextView
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import android.widget.LinearLayout
import android.view.Gravity
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

import android.os.PowerManager
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // đổi màu nền thanh pin, wifi,... thành đen
        window.statusBarColor = Color.parseColor("#000000")

        // 1. Root layout - nền đen sâu
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#000000"))
            setPadding(60, 60, 60, 60)
        }

        // Header - Logo/tên hệ thống
        val txtTitle = TextView(this).apply {
            text = "JARVIS SYSTEM CORE"
            setTextColor(Color.parseColor("#00FFFF"))
            textSize = 24f
            typeface = Typeface.create("sans-serif-thin", Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.2f
        }

        val txtStatus = TextView(this).apply {
            text = "STATUS: WAITING FOR PERMISSIONS..."
            setTextColor(Color.parseColor("#455A64")) // màu xám lạnh
            textSize = 12f
            setPadding(0, 10, 0, 80)
            typeface = Typeface.MONOSPACE
        }

        // Nút bấm
        val btnSetup = createCyberButton("INITIALIZE PERMISSIONS", "#00FFFF")
        val btnStop = createCyberButton("ACCESSIBILITY INTERFACE", "#00FFFF")

        // khoảng trắng giữa các nút
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, // rộng hết màn hình
            LinearLayout.LayoutParams.WRAP_CONTENT // chiều cao sẽ được thay đổi theo nội dung
        ). apply { setMargins(0, 50, 0, 50) }


        // Logic xử lý
        btnSetup.setOnClickListener { checkAllPermissions() }

        btnStop.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                val serviceName = ComponentName(packageName, AssistantService::class.java.name).flattenToString()
                putExtra("android.provider.extra.SERVICE_COMPONENT_NAME", serviceName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }


        // Add views
        mainLayout.addView(txtTitle)
        mainLayout.addView(txtStatus)
        mainLayout.addView(btnSetup, params)
        mainLayout.addView(btnStop, params)

        setContentView(mainLayout)

        // Code ẩn thanh trạng thái an toàn:
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, mainLayout).let { controller ->
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Ẩn Action Bar cho giao diện tràn viền
        supportActionBar?.hide()

        checkAllPermissions()
    }


    // Hàm tạo nút bấm phong cách công nghệ cao
    private fun createCyberButton(label: String, colorHex: String): Button {
        return Button(this).apply {
            text = label
//            textSize = 20
            setTextColor(Color.parseColor(colorHex))
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            isAllCaps = true
            letterSpacing = 0.1f

            // tạo background dạng viền (outline)
            val gd = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(4, Color.parseColor(colorHex)) // viền mỏng
                cornerRadius = 10f // bo góc nhẹ
            }
            background = gd

            // cài đặt padding
            setPadding(40, 40, 40, 40)
        }
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
