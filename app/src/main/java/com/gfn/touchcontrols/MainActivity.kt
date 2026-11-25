package com.gfn.touchcontrols

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    // Vistas de estado para actualizar tildes
    private lateinit var checkOverlay: TextView
    private lateinit var checkShizukuAlive: TextView
    private lateinit var checkShizukuPerm: TextView
    private lateinit var checkMouse: TextView
    private lateinit var tvGfnSub: TextView
    private lateinit var btnStartHudText: TextView
    private lateinit var btnStartHudSub: TextView

    // Animador de respiración metálica
    private var breathingAnimator: ValueAnimator? = null
    private val glowingBorders = mutableListOf<GradientDrawable>()

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            safeConnect()
        }
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            VirtualHidController.statusListener = { runOnUiThread { refreshStatus() } }
        } catch (_: Throwable) {
        }

        val d = resources.displayMetrics.density
        val padH = (18 * d).toInt()

        // Contenedor principal con fondo negro carbón metálico
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, (28 * d).toInt(), padH, (20 * d).toInt())
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#120A0C"), Color.parseColor("#0A0A0C"), Color.parseColor("#070709"))
            )
        }

        // --- 1. CABECERA: LOGO + TÍTULO + AJUSTES ---
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (20 * d).toInt())
        }

        val logoIcon = TextView(this).apply {
            text = "🎮"
            textSize = 28f
            setPadding(0, 0, (10 * d).toInt(), 0)
        }

        val titleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleMain = TextView(this).apply {
            text = "GFN"
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }

        val titleSub = TextView(this).apply {
            text = "Touch Controls"
            textSize = 15f
            setTextColor(Color.parseColor("#FF2A4D")) // Rojo metálico brillante
            setTypeface(null, Typeface.BOLD)
        }
        titleContainer.addView(titleMain)
        titleContainer.addView(titleSub)

        val btnSettings = TextView(this).apply {
            text = "⚙"
            textSize = 22f
            setTextColor(Color.parseColor("#C0C0C8"))
            gravity = Gravity.CENTER
            setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
            background = createCardBg(12 * d, Color.parseColor("#1F161A"), Color.parseColor("#4D1822"))
            setOnClickListener {
                if (OverlayService.isRunning) {
                    toggleOverlay()
                    postDelayed({ toggleOverlay() }, 300)
                }
            }
        }

        header.addView(logoIcon)
        header.addView(titleContainer)
        header.addView(btnSettings)
        root.addView(header)

        // --- 2. TARJETA HERO: DIVIDIDA (GEFORCE NOW + LISTA DE TILDES) ---
        val heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), (18 * d).toInt(), (16 * d).toInt(), (18 * d).toInt())
            val bg = createCardBg(20 * d, Color.parseColor("#180F13"), Color.parseColor("#801524"), true)
            background = bg
        }

        // Columna izquierda: GeForce Now
        val gfnCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val gfnLogo = TextView(this).apply {
            text = "👁"
            textSize = 34f
            setTextColor(Color.parseColor("#76B900")) // Verde oficial NVIDIA
            gravity = Gravity.CENTER
        }
        val gfnText = TextView(this).apply {
            text = "GeForce NOW"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, (4 * d).toInt(), 0, 0)
        }
        tvGfnSub = TextView(this).apply {
            text = "Esperando..."
            textSize = 11.5f
            setTextColor(Color.parseColor("#8D929D"))
            gravity = Gravity.CENTER
        }
        gfnCol.addView(gfnLogo)
        gfnCol.addView(gfnText)
        gfnCol.addView(tvGfnSub)

        // Divisor vertical sutil
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams((1 * d).toInt(), (80 * d).toInt())
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
        }

        // Columna derecha: Checklist con tildes
        val checklistCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.25f)
        }

        checkOverlay = createCheckItem(d, "Permiso Overlay")
        checkShizukuAlive = createCheckItem(d, "Shizuku Activo")
        checkShizukuPerm = createCheckItem(d, "Permiso Shizuku")
        checkMouse = createCheckItem(d, "Estado Ratón")

        checklistCol.addView(checkOverlay)
        checklistCol.addView(checkShizukuAlive)
        checklistCol.addView(checkShizukuPerm)
        checklistCol.addView(checkMouse)

        heroCard.addView(gfnCol)
        heroCard.addView(divider)
        heroCard.addView(checklistCol)
        root.addView(heroCard)

        root.addView(space(18 * d))

        // --- 3. BOTONES DE ACCIÓN EN CÁPSULA CON FLECHA ---
        val btnPerm = createActionCard(
            d = d,
            icon = "⊞",
            title = "Permiso para otras apps",
            subtitle = "Mostrar sobre otras aplicaciones"
        ) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (_: Throwable) {
            }
        }

        val btnShizuku = createActionCard(
            d = d,
            icon = "🔗",
            title = "Conectar Shizuku",
            subtitle = "Hardware Mouse (uinput)"
        ) {
            connectShizuku()
        }

        val btnHud = createActionCard(
            d = d,
            icon = "🖥",
            title = "Iniciar HUD Flotante",
            subtitle = "Activar interfaz táctil en pantalla",
            onBindText = { titleView, subView ->
                btnStartHudText = titleView
                btnStartHudSub = subView
            }
        ) {
            toggleOverlay()
        }

        // Botón principal metálico: "Abrir GeForce NOW"
        val btnLaunchGfn = createHighlightedCard(
            d = d,
            icon = "▶",
            title = "Abrir GeForce NOW",
            subtitle = "Iniciar la app oficial"
        ) {
            val launch = packageManager.getLaunchIntentForPackage("com.nvidia.geforcenow")
            if (launch != null) {
                startActivity(launch)
            } else {
                tvGfnSub.text = "GFN no instalado"
            }
        }

        root.addView(btnPerm)
        root.addView(space(12 * d))
        root.addView(btnShizuku)
        root.addView(space(12 * d))
        root.addView(btnHud)
        root.addView(space(12 * d))
        root.addView(btnLaunchGfn)

        // --- 4. FOOTER CYBERPUNK MECHA ---
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, (26 * d).toInt(), 0, (6 * d).toInt())
        }

        val footerFrame = TextView(this).apply {
            text = "GFN TOUCH CONTROLS"
            textSize = 10.5f
            setTextColor(Color.parseColor("#9D606C"))
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.22f
            gravity = Gravity.CENTER
            setPadding((16 * d).toInt(), (6 * d).toInt(), (16 * d).toInt(), (6 * d).toInt())
            val bg = createCardBg(14 * d, Color.parseColor("#160B0E"), Color.parseColor("#5A1420"), true)
            background = bg
        }

        val footerSub = TextView(this).apply {
            text = "— CONTROL TOTAL EN TUS MANOS —"
            textSize = 9.5f
            setTextColor(Color.parseColor("#6A454E"))
            letterSpacing = 0.16f
            gravity = Gravity.CENTER
            setPadding(0, (6 * d).toInt(), 0, 0)
        }

        footer.addView(footerFrame)
        footer.addView(footerSub)
        root.addView(footer)

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        // Iniciar animación de respiración metálica
        startBreathingAnimation()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
            }
        }
    }

    // Efecto de respiración lento (Breathing LED) estilo mouse/laptop gamer
    private fun startBreathingAnimation() {
        breathingAnimator = ValueAnimator.ofFloat(0.35f, 1.0f).apply {
            duration = 2800L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                val redAlpha = (fraction * 255).toInt().coerceIn(60, 255)
                val strokeColor = Color.argb(redAlpha, 255, 36, 68)
                for (bg in glowingBorders) {
                    bg.setStroke((1.5f * resources.displayMetrics.density).toInt(), strokeColor)
                }
            }
            start()
        }
    }

    private fun createCardBg(radius: Float, fillColor: Int, strokeColor: Int, trackGlow: Boolean = false): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = radius
            setStroke((1.4f * resources.displayMetrics.density).toInt(), strokeColor)
            if (trackGlow) glowingBorders.add(this)
        }
    }

    private fun createCheckItem(d: Float, title: String): TextView {
        return TextView(this).apply {
            text = "⚪  $title"
            textSize = 12.5f
            setTextColor(Color.parseColor("#B0B4C0"))
            setPadding(0, (2.5f * d).toInt(), 0, (2.5f * d).toInt())
        }
    }

    private fun createActionCard(
        d: Float,
        icon: String,
        title: String,
        subtitle: String,
        onBindText: ((TextView, TextView) -> Unit)? = null,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt())
            val bg = createCardBg(16 * d, Color.parseColor("#150E12"), Color.parseColor("#3D161F"), true)
            background = bg

            val tvIcon = TextView(context).apply {
                text = icon
                textSize = 20f
                setTextColor(Color.parseColor("#FF4060"))
                gravity = Gravity.CENTER
                setPadding(0, 0, (14 * d).toInt(), 0)
            }

            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvTitle = TextView(context).apply {
                text = title
                textSize = 14f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
            }

            val tvSub = TextView(context).apply {
                text = subtitle
                textSize = 11.5f
                setTextColor(Color.parseColor("#7D8290"))
            }

            textCol.addView(tvTitle)
            textCol.addView(tvSub)
            onBindText?.invoke(tvTitle, tvSub)

            val tvArrow = TextView(context).apply {
                text = "›"
                textSize = 22f
                setTextColor(Color.parseColor("#FF2A4D"))
            }

            addView(tvIcon)
            addView(textCol)
            addView(tvArrow)

            setOnClickListener { onClick() }
        }
    }

    private fun createHighlightedCard(
        d: Float,
        icon: String,
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), (15 * d).toInt(), (16 * d).toInt(), (15 * d).toInt())
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#C60B28"), Color.parseColor("#800516"), Color.parseColor("#4A020C"))
            ).apply {
                cornerRadius = 16 * d
                setStroke((1.5f * d).toInt(), Color.parseColor("#FF5C78"))
            }

            val tvIcon = TextView(context).apply {
                text = icon
                textSize = 18f
                setTextColor(Color.WHITE)
                setPadding(0, 0, (14 * d).toInt(), 0)
            }

            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvTitle = TextView(context).apply {
                text = title
                textSize = 14.5f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
            }

            val tvSub = TextView(context).apply {
                text = subtitle
                textSize = 11.5f
                setTextColor(Color.parseColor("#FFCCD4"))
            }

            textCol.addView(tvTitle)
            textCol.addView(tvSub)

            val tvArrow = TextView(context).apply {
                text = "›"
                textSize = 22f
                setTextColor(Color.WHITE)
            }

            addView(tvIcon)
            addView(textCol)
            addView(tvArrow)

            setOnClickListener { onClick() }
        }
    }

    private fun space(h: Float) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h.toInt())
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun safeConnect() {
        Thread {
            try {
                VirtualHidController.connect(this)
            } catch (_: Throwable) {
            }
            runOnUiThread { refreshStatus() }
        }.start()
    }

    private fun connectShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                refreshStatus()
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                safeConnect()
            } else {
                Shizuku.requestPermission(1001)
            }
        } catch (_: Throwable) {
        }
        refreshStatus()
    }

    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (OverlayService.isRunning) {
            stopService(Intent(this, OverlayService::class.java))
            btnStartHudText.postDelayed({ refreshStatus() }, 200)
        } else {
            val intent = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            btnStartHudText.postDelayed({ refreshStatus() }, 300)
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
        val mouseOk = VirtualHidController.isConnected

        checkOverlay.text = if (overlayOk) "✔  Permiso Overlay" else "⚪  Permiso Overlay"
        checkOverlay.setTextColor(if (overlayOk) Color.parseColor("#4CAF50") else Color.parseColor("#808492"))

        checkShizukuAlive.text = if (shizukuAlive) "✔  Shizuku Activo" else "⚪  Shizuku Activo"
        checkShizukuAlive.setTextColor(if (shizukuAlive) Color.parseColor("#4CAF50") else Color.parseColor("#808492"))

        checkShizukuPerm.text = if (shizukuPerm) "✔  Permiso Shizuku" else "⚪  Permiso Shizuku"
        checkShizukuPerm.setTextColor(if (shizukuPerm) Color.parseColor("#4CAF50") else Color.parseColor("#808492"))

        checkMouse.text = if (mouseOk) "✔  Estado Ratón" else "⚪  Estado Ratón"
        checkMouse.setTextColor(if (mouseOk) Color.parseColor("#4CAF50") else Color.parseColor("#808492"))

        tvGfnSub.text = if (shizukuPerm && mouseOk) "Conectado" else "Listo para iniciar"
        tvGfnSub.setTextColor(if (shizukuPerm && mouseOk) Color.parseColor("#4CAF50") else Color.parseColor("#8D929D"))

        if (::btnStartHudText.isInitialized) {
            btnStartHudText.text = if (OverlayService.isRunning) "Detener HUD Flotante" else "Iniciar HUD Flotante"
            btnStartHudSub.text = if (OverlayService.isRunning) "Cerrar la interfaz en pantalla" else "Activar interfaz táctil en pantalla"
        }
    }

    override fun onDestroy() {
        breathingAnimator?.cancel()
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) {
        }
        VirtualHidController.statusListener = null
        super.onDestroy()
    }
}
