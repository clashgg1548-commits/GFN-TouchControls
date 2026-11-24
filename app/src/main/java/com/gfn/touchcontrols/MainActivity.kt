package com.gfn.touchcontrols

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
            VirtualHidController.bind(this)
        }
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        VirtualHidController.statusListener = {
            runOnUiThread { refreshStatus() }
        }

        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (36 * d).toInt(), pad, pad)
            setBackgroundColor(Color.parseColor("#0F131A"))
        }

        val title = TextView(this).apply {
            text = "GFN Touch Controls + Gyro"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = "Controles estilo shooter móvil + giroscopio RAW para GeForce Now (Battlefield)"
            textSize = 13.5f
            setTextColor(Color.parseColor("#90A4AE"))
            setPadding(0, (6 * d).toInt(), 0, (18 * d).toInt())
        }

        tvStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#80D8FF"))
            setPadding((14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt())
            setBackgroundColor(Color.parseColor("#181E29"))
        }

        val btnPermOverlay = Button(this).apply {
            text = "1. Dar permiso 'Mostrar sobre otras apps'"
            setOnClickListener {
                val i = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(i)
            }
        }

        val btnPermShizuku = Button(this).apply {
            text = "2. Conectar y autorizar Shizuku (/dev/uhid)"
            setOnClickListener { connectShizuku() }
        }

        btnStart = Button(this).apply {
            text = "3. Iniciar Overlay Flotante"
            textSize = 16f
            setOnClickListener { toggleOverlay() }
        }

        val btnOpenGfn = Button(this).apply {
            text = "Abrir GeForce Now"
            setOnClickListener {
                val launch = packageManager.getLaunchIntentForPackage("com.nvidia.geforcenow")
                if (launch != null) {
                    startActivity(launch)
                } else {
                    tvStatus.append("\nNo se detectó GeForce Now instalado (igual podés abrirlo manual).")
                }
            }
        }

        val hint = TextView(this).apply {
            text = "\nCómo usar:\n" +
                "• Tocá 'Iniciar Overlay Flotante' y abrí GeForce Now.\n" +
                "• A la izquierda vas a ver una pestañita discreta (≡ HUD): tocala o deslizala hacia adentro para mover botones, ocultar los que no uses y ajustar el giroscopio RAW.\n" +
                "• Si necesitás tocar menús del juego, usá 'Modo Cursor' dentro de esa pestaña."
            textSize = 13f
            setTextColor(Color.LTGRAY)
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

    private fun space(d: Float) = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (12 * d).toInt()
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
                VirtualHidController.bind(this)
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
            append("Teclado/Mouse Virtual: ${VirtualHidController.lastStatus}")
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
