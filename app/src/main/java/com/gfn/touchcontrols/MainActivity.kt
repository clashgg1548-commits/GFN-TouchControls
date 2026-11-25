package com.gfn.touchcontrols

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            VirtualHidController.connect(this)
        }
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        VirtualHidController.statusListener = {
            runOnUiThread { refreshStatus() }
        }

        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            VirtualHidController.connect(this)
        }

        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (40 * d).toInt(), pad, (32 * d).toInt())
            setBackgroundColor(Color.parseColor("#080B11"))
        }

        // Título con estilo moderno
        val title = TextView(this).apply {
            text = "GFN Touch Controls"
            textSize = 23f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = "Liquid Glass HUD • Hardware Mouse & Gyro para Battlefield"
            textSize = 13.5f
            setTextColor(Color.parseColor("#90A4AE"))
            setPadding(0, (6 * d).toInt(), 0, (20 * d).toInt())
        }

        // Tarjeta de estado con vidrio esmerilado
        tvStatus = TextView(this).apply {
            textSize = 13.5f
            setTextColor(Color.parseColor("#E0F7FA"))
            setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt())
            background = GradientDrawable().apply {
                colors = intArrayOf(
                    Color.argb(160, 24, 32, 50),
                    Color.argb(220, 12, 16, 26)
                )
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                cornerRadius = 18 * d
                setStroke((1.3f * d).toInt(), Color.argb(130, 200, 230, 255))
            }
        }

        val btnPermOverlay = createPillButton("1. Permiso 'Mostrar sobre otras apps'", isPrimary = false, d = d) {
            val i = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(i)
        }

        val btnPermShizuku = createPillButton("2. Conectar Shizuku (Hardware Mouse)", isPrimary = false, d = d) {
            connectShizuku()
        }

        btnStart = createPillButton("3. Iniciar Overlay Flotante", isPrimary = true, d = d) {
            toggleOverlay()
        }

        val btnOpenGfn = createPillButton("Abrir GeForce Now", isPrimary = false, d = d) {
            val launch = packageManager.getLaunchIntentForPackage("com.nvidia.geforcenow")
            if (launch != null) {
                startActivity(launch)
            } else {
                tvStatus.append("\nNo se detectó GeForce Now instalado (abrílo manual).")
            }
        }

        val hint = TextView(this).apply {
            text = "\nLiquid Glass HUD:\n" +
                "• El Joystick ahora es 100% limpio y analógico (sin WASD).\n" +
                "• La caja del Trackpad en la derecha maneja la mira suave como mantequilla.\n" +
                "• Abrí la píldora ≡ HUD en cualquier momento para ajustar la sensibilidad o invertir ejes del giroscopio."
            textSize = 12.5f
            setTextColor(Color.parseColor("#B0BEC5"))
            gravity = Gravity.START
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(tvStatus)
        root.addView(space(d))
        root.addView(btnPermOverlay)
        root.addView(btnPermShizuku)
        root.addView(space(d))
        root.addView(btnStart)
        root.addView(btnOpenGfn)
        root.addView(hint)

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    10
                )
            }
        }
    }

    private fun createPillButton(text: String, isPrimary: Boolean, d: Float, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = if (isPrimary) 15f else 13.5f
            setTextColor(Color.WHITE)
            setTypeface(null, if (isPrimary) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            background = GradientDrawable().apply {
                if (isPrimary) {
                    colors = intArrayOf(
                        Color.parseColor("#00E5FF"),
                        Color.parseColor("#0091EA")
                    )
                    gradientType = GradientDrawable.LINEAR_GRADIENT
                    orientation = GradientDrawable.Orientation.TOP_BOTTOM
                } else {
                    colors = intArrayOf(
                        Color.argb(120, 45, 55, 75),
                        Color.argb(180, 18, 22, 34)
                    )
                    gradientType = GradientDrawable.LINEAR_GRADIENT
                    orientation = GradientDrawable.Orientation.TOP_BOTTOM
                    setStroke((1.2f * d).toInt(), Color.argb(120, 200, 230, 255))
                }
                cornerRadius = 24 * d
            }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, (8 * d).toInt())
            }
        }
    }

    private fun space(d: Float) = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (10 * d).toInt()
        )
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun connectShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                refreshStatus()
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                VirtualHidController.connect(this)
            } else {
                Shizuku.requestPermission(1001)
            }
        } catch (_: Throwable) {
        }
        refreshStatus()
    }

    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        if (OverlayService.isRunning) {
            stopService(Intent(this, OverlayService::class.java))
            btnStart.postDelayed({ refreshStatus() }, 200)
        } else {
            val intent = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            btnStart.postDelayed({ refreshStatus() }, 300)
        }
    }

    private fun refreshStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val shizukuAlive = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        val shizukuPerm = shizukuAlive && try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }

        tvStatus.text = buildString {
            append("Permiso Overlay: ${if (overlayOk) "OK ✅" else "Falta ❌"}\n")
            append("Shizuku activo: ${if (shizukuAlive) "Sí ✅" else "No (abrí Shizuku) ❌"}\n")
            append("Permiso Shizuku: ${if (shizukuPerm) "OK ✅" else "Falta ❌"}\n")
            append("Driver de Entrada: ${VirtualHidController.lastStatus}")
        }
        btnStart.text = if (OverlayService.isRunning) {
            "Detener Overlay Flotante"
        } else {
            "3. Iniciar Overlay Flotante"
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        VirtualHidController.statusListener = null
        super.onDestroy()
    }
}
