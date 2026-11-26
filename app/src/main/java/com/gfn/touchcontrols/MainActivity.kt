package com.gfn.touchcontrols

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import rikka.shizuku.Shizuku
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    // Vistas de estado para actualizar tildes vectoriales
    private lateinit var checkOverlay: StatusCheckView
    private lateinit var checkShizukuAlive: StatusCheckView
    private lateinit var checkShizukuPerm: StatusCheckView
    private lateinit var checkMouse: StatusCheckView
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
        val padH = (16 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, (24 * d).toInt(), padH, (16 * d).toInt())
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#120A0D"), Color.parseColor("#0A0A0C"), Color.parseColor("#070709"))
            )
        }

        // --- 1. CABECERA: ÍCONO GAMER VECTORIAL + TÍTULO + ENGRANAJE AJUSTES ---
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (18 * d).toInt())
        }

        val logoIcon = VectorIconView(this, VectorType.GAMEPAD, Color.parseColor("#FF2A4D"), dp(32f))

        val titleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * d).toInt(), 0, 0, 0)
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
            textSize = 14.5f
            setTextColor(Color.parseColor("#FF2A4D"))
            setTypeface(null, Typeface.BOLD)
        }
        titleContainer.addView(titleMain)
        titleContainer.addView(titleSub)

        // Botón engranaje con icono vectorial que abre los Ajustes de Sensibilidad directamente
        val btnSettings = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
            background = createCardBg(12 * d, Color.parseColor("#1F1418"), Color.parseColor("#4D1822"))
            addView(VectorIconView(context, VectorType.GEAR, Color.parseColor("#D5D8E0"), dp(22f)))
            setOnClickListener { showSettingsDialog() }
        }

        header.addView(logoIcon)
        header.addView(titleContainer)
        header.addView(btnSettings)
        root.addView(header)

        // --- 2. TARJETA HERO: ESTADO GEFORCE NOW + CHECKLIST ---
        val heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt())
            background = createCardBg(18 * d, Color.parseColor("#180F13"), Color.parseColor("#801524"), true)
        }

        val gfnCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val gfnLogo = VectorIconView(this, VectorType.GFN_LOGO, Color.parseColor("#76B900"), dp(42f))
        val gfnText = TextView(this).apply {
            text = "GeForce NOW"
            textSize = 14.5f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, (6 * d).toInt(), 0, 0)
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

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams((1 * d).toInt(), (85 * d).toInt())
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
        }

        val checklistCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * d).toInt(), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.25f)
        }

        checkOverlay = StatusCheckView(this, "Permiso Overlay")
        checkShizukuAlive = StatusCheckView(this, "Shizuku Activo")
        checkShizukuPerm = StatusCheckView(this, "Permiso Shizuku")
        checkMouse = StatusCheckView(this, "Estado Ratón")

        checklistCol.addView(checkOverlay)
        checklistCol.addView(checkShizukuAlive)
        checklistCol.addView(checkShizukuPerm)
        checklistCol.addView(checkMouse)

        heroCard.addView(gfnCol)
        heroCard.addView(divider)
        heroCard.addView(checklistCol)
        root.addView(heroCard)

        root.addView(space(16 * d))

        // --- 3. ACCIONES EN CUADRÍCULA 2x2 ---
        val gridContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Fila 1: Permisos Overlay + Conectar Shizuku
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val cardOverlay = createGridCard(
            d = d,
            icon = VectorType.OVERLAY_LAYERS,
            title = "Permiso Overlay",
            subtitle = "Mostrar sobre apps",
            weight = 1f
        ) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Throwable) {
            }
        }

        val cardShizuku = createGridCard(
            d = d,
            icon = VectorType.SHIZUKU_LINK,
            title = "Conectar Shizuku",
            subtitle = "Hardware Mouse (uinput)",
            weight = 1f
        ) {
            connectShizuku()
        }

        row1.addView(cardOverlay)
        row1.addView(spaceW(12 * d))
        row1.addView(cardShizuku)

        // Fila 2: HUD Flotante + Abrir GeForce Now (Resaltado Metálico)
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (12 * d).toInt(), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val cardHud = createGridCard(
            d = d,
            icon = VectorType.HUD_MONITOR,
            title = "Iniciar HUD",
            subtitle = "Activar controles",
            weight = 1f,
            onBindText = { tvTitle, tvSub ->
                btnStartHudText = tvTitle
                btnStartHudSub = tvSub
            }
        ) {
            toggleOverlay()
        }

        val cardGfn = createHighlightedGridCard(
            d = d,
            icon = VectorType.PLAY_ARROW,
            title = "Abrir GFN",
            subtitle = "Iniciar juego",
            weight = 1f
        ) {
            val launch = packageManager.getLaunchIntentForPackage("com.nvidia.geforcenow")
            if (launch != null) {
                startActivity(launch)
            } else {
                tvGfnSub.text = "GFN no instalado"
            }
        }

        row2.addView(cardHud)
        row2.addView(spaceW(12 * d))
        row2.addView(cardGfn)

        gridContainer.addView(row1)
        gridContainer.addView(row2)
        root.addView(gridContainer)

        // --- 4. FOOTER CYBERPUNK MECHA ---
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, (18 * d).toInt(), 0, (4 * d).toInt())
        }

        val footerFrame = TextView(this).apply {
            text = "GFN TOUCH CONTROLS"
            textSize = 10f
            setTextColor(Color.parseColor("#9D606C"))
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.22f
            gravity = Gravity.CENTER
            setPadding((16 * d).toInt(), (5 * d).toInt(), (16 * d).toInt(), (5 * d).toInt())
            background = createCardBg(12 * d, Color.parseColor("#160B0E"), Color.parseColor("#5A1420"), true)
        }

        val footerSub = TextView(this).apply {
            text = "— CONTROL TOTAL EN TUS MANOS —"
            textSize = 9f
            setTextColor(Color.parseColor("#6A454E"))
            letterSpacing = 0.16f
            gravity = Gravity.CENTER
            setPadding(0, (5 * d).toInt(), 0, 0)
        }

        footer.addView(footerFrame)
        footer.addView(footerSub)
        root.addView(footer)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)

        // ARREGLO 1: respetar la barra de estado (hora, batería) y la barra de navegación
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        startBreathingAnimation()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
            }
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    // Diálogo de ajustes tácticos que se abre directo desde el engranaje
    private fun showSettingsDialog() {
        val config = ControlConfig(this)
        val d = resources.displayMetrics.density
        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val pad = (16 * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.argb(240, 20, 14, 20))
                cornerRadius = 20 * d
                setStroke((1.5f * d).toInt(), Color.parseColor("#FF2A4D"))
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Ajustes de Sensibilidad"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClose = Button(this).apply {
            text = "Cerrar"
            textSize = 12f
            // ARREGLO 3: botón rojo
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#8E0016"))
            setTextColor(Color.WHITE)
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(title)
        header.addView(btnClose)
        root.addView(header)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.88f).toInt(),
                (resources.displayMetrics.heightPixels * 0.70f).toInt()
            )
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
        }

        addDialogTitle(body, "Giroscopio (RAW Ultrarrápido)")
        addDialogSwitch(body, "Activar Giroscopio", config.gyroEnabled) { config.gyroEnabled = it; config.save() }
        addDialogSwitch(body, "Solo al apuntar o disparar", config.gyroOnlyAdsOrFire) { config.gyroOnlyAdsOrFire = it; config.save() }
        addDialogSwitch(body, "Invertir eje horizontal (X)", config.gyroInvertX) { config.gyroInvertX = it; config.save() }
        addDialogSwitch(body, "Invertir eje vertical (Y)", config.gyroInvertY) { config.gyroInvertY = it; config.save() }
        addDialogSlider(body, "Sensibilidad Giro X", 2, 80, config.gyroSensX.toInt()) { config.gyroSensX = it.toFloat(); config.save() }
        addDialogSlider(body, "Sensibilidad Giro Y", 2, 80, config.gyroSensY.toInt()) { config.gyroSensY = it.toFloat(); config.save() }
        addDialogSlider(body, "Anti-temblor (Deadzone)", 0, 15, (config.gyroDeadzone * 100f).toInt()) { config.gyroDeadzone = it / 100f; config.save() }

        addDialogTitle(body, "Mira Táctil / Trackpad")
        addDialogSlider(body, "Sensibilidad Trackpad X", 20, 400, (config.touchSensX * 100f).toInt()) { config.touchSensX = it / 100f; config.save() }
        addDialogSlider(body, "Sensibilidad Trackpad Y", 20, 400, (config.touchSensY * 100f).toInt()) { config.touchSensY = it / 100f; config.save() }
        addDialogSwitch(body, "Auto-Sprint al empujar joystick", config.autoSprint) { config.autoSprint = it; config.save() }
        addDialogSwitch(body, "Apuntar (ADS) por toque (Toggle)", config.aimToggleMode) { config.aimToggleMode = it; config.save() }

        addDialogTitle(body, "Opacidad de Interfaz")
        addDialogSlider(body, "Opacidad de botones HUD (%)", 15, 95, (config.hudAlpha * 100f).toInt()) { config.hudAlpha = it / 100f; config.save() }
        addDialogSlider(body, "Opacidad de pestaña lateral (%)", 15, 85, (config.tabAlpha * 100f).toInt()) { config.tabAlpha = it / 100f; config.save() }

        val btnReset = Button(this).apply {
            text = "Restablecer valores por defecto"
            textSize = 12f
            // ARREGLO 3: botón rojo
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#8E0016"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                config.resetDefaults()
                dialog.dismiss()
            }
        }
        body.addView(btnReset)

        scroll.addView(body)
        root.addView(scroll)
        dialog.setContentView(root)
        dialog.show()
    }

    private fun addDialogTitle(parent: LinearLayout, title: String) {
        val tv = TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(Color.parseColor("#FF5C78"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(14f).toInt(), 0, dp(4f).toInt())
        }
        parent.addView(tv)
    }

    private fun addDialogSwitch(parent: LinearLayout, label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
        @SuppressLint("UseSwitchCompatOrMaterialCode")
        val sw = Switch(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 12.5f
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
            // ARREGLO 3: interruptor rojo
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(
                states,
                intArrayOf(Color.parseColor("#FF2A4D"), Color.parseColor("#888888"))
            )
            trackTintList = ColorStateList(
                states,
                intArrayOf(Color.parseColor("#66FF2A4D"), Color.parseColor("#44FFFFFF"))
            )
        }
        parent.addView(sw)
    }

    private fun addDialogSlider(parent: LinearLayout, label: String, minVal: Int, maxVal: Int, initial: Int, onChange: (Int) -> Unit) {
        val tv = TextView(this).apply {
            text = "$label: $initial"
            setTextColor(Color.LTGRAY)
            textSize = 12f
        }
        val sb = SeekBar(this).apply {
            max = maxVal - minVal
            progress = (initial - minVal).coerceIn(0, max)
            // ARREGLO 3: slider rojo
            val red = ColorStateList.valueOf(Color.parseColor("#FF2A4D"))
            progressTintList = red
            thumbTintList = red
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + minVal
                    tv.text = "$label: $v"
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        parent.addView(tv)
        parent.addView(sb)
    }

    // Animación de respiración LED metálica
    private fun startBreathingAnimation() {
        breathingAnimator = ValueAnimator.ofFloat(0.35f, 1.0f).apply {
            duration = 2800L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                val redAlpha = (fraction * 255).toInt().coerceIn(60, 255)
                val strokeColor = Color.argb(redAlpha, 255, 42, 77)
                for (bg in glowingBorders) {
                    bg.setStroke((1.4f * resources.displayMetrics.density).toInt(), strokeColor)
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

    // Tarjeta individual en cuadrícula 2x2
    private fun createGridCard(
        d: Float,
        icon: VectorType,
        title: String,
        subtitle: String,
        weight: Float,
        onBindText: ((TextView, TextView) -> Unit)? = null,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt(), (14 * d).toInt())
            // ARREGLO 2: altura fija de 120 en vez de estirarse por toda la pantalla
            layoutParams = LinearLayout.LayoutParams(0, (120 * d).toInt(), weight)
            background = createCardBg(16 * d, Color.parseColor("#150E12"), Color.parseColor("#3D161F"), true)

            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val iconView = VectorIconView(context, icon, Color.parseColor("#FF4060"), 24 * d)
            val spacer = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }
            val arrow = VectorIconView(context, VectorType.CHEVRON_RIGHT, Color.parseColor("#FF2A4D"), 14 * d)
            topRow.addView(iconView)
            topRow.addView(spacer)
            topRow.addView(arrow)

            val tvTitle = TextView(context).apply {
                text = title
                textSize = 13.5f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, (14 * d).toInt(), 0, (2 * d).toInt())
            }

            val tvSub = TextView(context).apply {
                text = subtitle
                textSize = 11f
                setTextColor(Color.parseColor("#7D8290"))
            }

            onBindText?.invoke(tvTitle, tvSub)

            addView(topRow)
            addView(tvTitle)
            addView(tvSub)

            setOnClickListener { onClick() }
        }
    }

    private fun createHighlightedGridCard(
        d: Float,
        icon: VectorType,
        title: String,
        subtitle: String,
        weight: Float,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt(), (14 * d).toInt())
            // ARREGLO 2: altura fija de 120
            layoutParams = LinearLayout.LayoutParams(0, (120 * d).toInt(), weight)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#D50024"), Color.parseColor("#8E0016"), Color.parseColor("#4A020C"))
            ).apply {
                cornerRadius = 16 * d
                setStroke((1.5f * d).toInt(), Color.parseColor("#FF5C78"))
            }

            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val iconView = VectorIconView(context, icon, Color.WHITE, 24 * d)
            val spacer = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }
            val arrow = VectorIconView(context, VectorType.CHEVRON_RIGHT, Color.WHITE, 14 * d)
            topRow.addView(iconView)
            topRow.addView(spacer)
            topRow.addView(arrow)

            val tvTitle = TextView(context).apply {
                text = title
                textSize = 14f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, (14 * d).toInt(), 0, (2 * d).toInt())
            }

            val tvSub = TextView(context).apply {
                text = subtitle
                textSize = 11f
                setTextColor(Color.parseColor("#FFD0D8"))
            }

            addView(topRow)
            addView(tvTitle)
            addView(tvSub)

            setOnClickListener { onClick() }
        }
    }

    private fun space(h: Float) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h.toInt())
    }

    private fun spaceW(w: Float) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(w.toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
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

        checkOverlay.setChecked(overlayOk)
        checkShizukuAlive.setChecked(shizukuAlive)
        checkShizukuPerm.setChecked(shizukuPerm)
        checkMouse.setChecked(mouseOk)

        tvGfnSub.text = if (shizukuPerm && mouseOk) "Conectado" else "Listo para iniciar"
        tvGfnSub.setTextColor(if (shizukuPerm && mouseOk) Color.parseColor("#4CAF50") else Color.parseColor("#8D929D"))

        if (::btnStartHudText.isInitialized) {
            btnStartHudText.text = if (OverlayService.isRunning) "Detener HUD" else "Iniciar HUD"
            btnStartHudSub.text = if (OverlayService.isRunning) "Cerrar controles" else "Activar controles"
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

// --- CLASES VECTORIALES PERSONALIZADAS (CERO EMOJIS, 100% CÓDIGO VECTORIAL NATIVO) ---

enum class VectorType {
    GAMEPAD,
    GEAR,
    GFN_LOGO,
    OVERLAY_LAYERS,
    SHIZUKU_LINK,
    HUD_MONITOR,
    PLAY_ARROW,
    CHEVRON_RIGHT
}

@SuppressLint("ViewConstructor")
class VectorIconView(context: Context, private val type: VectorType, private val color: Int, private val sizePx: Float) : View(context) {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    init {
        layoutParams = ViewGroup.LayoutParams(sizePx.toInt(), sizePx.toInt())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx.toInt(), sizePx.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        strokePaint.color = color
        strokePaint.strokeWidth = w * 0.085f
        fillPaint.color = color

        when (type) {
            VectorType.GAMEPAD -> {
                // Silueta de mando gaming
                val r = RectF(w * 0.12f, h * 0.26f, w * 0.88f, h * 0.76f)
                canvas.drawRoundRect(r, w * 0.22f, w * 0.22f, strokePaint)
                // Cruz direccional
                canvas.drawLine(w * 0.28f, h * 0.51f, w * 0.42f, h * 0.51f, strokePaint)
                canvas.drawLine(w * 0.35f, h * 0.44f, w * 0.35f, h * 0.58f, strokePaint)
                // Botones
                canvas.drawCircle(w * 0.65f, h * 0.46f, w * 0.045f, fillPaint)
                canvas.drawCircle(w * 0.73f, h * 0.54f, w * 0.045f, fillPaint)
            }
            VectorType.GEAR -> {
                // Engranaje mecánico
                val cx = w * 0.5f
                val cy = h * 0.5f
                val radius = w * 0.30f
                canvas.drawCircle(cx, cy, radius, strokePaint)
                val teeth = 6
                for (i in 0 until teeth) {
                    val angle = (i * (360.0 / teeth)) * (Math.PI / 180.0)
                    val x1 = cx + (radius * 0.85f * cos(angle)).toFloat()
                    val y1 = cy + (radius * 0.85f * sin(angle)).toFloat()
                    val x2 = cx + (w * 0.45f * cos(angle)).toFloat()
                    val y2 = cy + (h * 0.45f * sin(angle)).toFloat()
                    canvas.drawLine(x1, y1, x2, y2, strokePaint)
                }
                canvas.drawCircle(cx, cy, w * 0.12f, fillPaint)
            }
            VectorType.GFN_LOGO -> {
                // Polígono estilizado GeForce NOW
                path.reset()
                path.moveTo(w * 0.5f, h * 0.16f)
                path.lineTo(w * 0.85f, h * 0.36f)
                path.lineTo(w * 0.85f, h * 0.76f)
                path.lineTo(w * 0.5f, h * 0.94f)
                path.lineTo(w * 0.15f, h * 0.76f)
                path.lineTo(w * 0.15f, h * 0.36f)
                path.close()
                canvas.drawPath(path, strokePaint)
                canvas.drawCircle(w * 0.5f, h * 0.55f, w * 0.14f, fillPaint)
            }
            VectorType.OVERLAY_LAYERS -> {
                // Ventanas flotantes superpuestas
                val rBack = RectF(w * 0.12f, h * 0.12f, w * 0.62f, h * 0.62f)
                strokePaint.alpha = 130
                canvas.drawRoundRect(rBack, w * 0.1f, w * 0.1f, strokePaint)
                strokePaint.alpha = 255
                val rFront = RectF(w * 0.35f, h * 0.35f, w * 0.88f, h * 0.88f)
                canvas.drawRoundRect(rFront, w * 0.1f, w * 0.1f, strokePaint)
            }
            VectorType.SHIZUKU_LINK -> {
                // Eslabones entrelazados
                path.reset()
                path.moveTo(w * 0.28f, h * 0.52f)
                path.lineTo(w * 0.45f, h * 0.35f)
                path.lineTo(w * 0.62f, h * 0.52f)
                path.lineTo(w * 0.45f, h * 0.69f)
                path.close()
                canvas.drawPath(path, strokePaint)
                canvas.drawLine(w * 0.52f, h * 0.45f, w * 0.76f, h * 0.21f, strokePaint)
                canvas.drawLine(w * 0.21f, h * 0.76f, w * 0.45f, h * 0.52f, strokePaint)
            }
            VectorType.HUD_MONITOR -> {
                // Pantalla con mira HUD en el centro
                val rScreen = RectF(w * 0.12f, h * 0.18f, w * 0.88f, h * 0.68f)
                canvas.drawRoundRect(rScreen, w * 0.08f, w * 0.08f, strokePaint)
                canvas.drawLine(w * 0.5f, h * 0.68f, w * 0.5f, h * 0.84f, strokePaint)
                canvas.drawLine(w * 0.35f, h * 0.84f, w * 0.65f, h * 0.84f, strokePaint)
                canvas.drawCircle(w * 0.5f, h * 0.43f, w * 0.05f, fillPaint)
            }
            VectorType.PLAY_ARROW -> {
                // Flecha angular de lanzamiento
                path.reset()
                path.moveTo(w * 0.30f, h * 0.22f)
                path.lineTo(w * 0.82f, h * 0.50f)
                path.lineTo(w * 0.30f, h * 0.78f)
                path.close()
                canvas.drawPath(path, fillPaint)
            }
            VectorType.CHEVRON_RIGHT -> {
                path.reset()
                path.moveTo(w * 0.32f, h * 0.18f)
                path.lineTo(w * 0.68f, h * 0.50f)
                path.lineTo(w * 0.32f, h * 0.82f)
                canvas.drawPath(path, strokePaint)
            }
        }
    }
}

// Fila de estado con indicador vectorial circular (sin emojis de texto)
@SuppressLint("ViewConstructor")
class StatusCheckView(context: Context, title: String) : LinearLayout(context) {

    private val dotView = View(context)
    private val label = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val d = resources.displayMetrics.density
        setPadding(0, (3 * d).toInt(), 0, (3 * d).toInt())

        val dotSize = (10 * d).toInt()
        dotView.layoutParams = LayoutParams(dotSize, dotSize).apply {
            setMargins(0, 0, (8 * d).toInt(), 0)
        }
        setChecked(false)

        label.text = title
        label.textSize = 12.5f
        label.setTextColor(Color.parseColor("#9AA0B0"))

        addView(dotView)
        addView(label)
    }

    fun setChecked(ok: Boolean) {
        dotView.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (ok) Color.parseColor("#4CAF50") else Color.parseColor("#5A6070"))
        }
        label.setTextColor(if (ok) Color.parseColor("#E0E4F0") else Color.parseColor("#7A8090"))
    }
}
 
