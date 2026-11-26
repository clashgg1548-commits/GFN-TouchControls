package com.gfn.touchcontrols

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.graphics.ColorUtils
import kotlin.math.abs

class OverlayService : Service(), SensorEventListener {

    companion object {
        private const val CHANNEL_ID = "gfn_overlay_channel"
        private const val NOTIF_ID = 101
        private const val GYRO_INIT_KEY = "gyro_default_off_applied"
        var isRunning = false
            private set
    }

    private lateinit var wm: WindowManager
    private lateinit var config: ControlConfig
    private lateinit var appearancePrefs: SharedPreferences
    private lateinit var sensorManager: SensorManager
    private var gyroSensor: Sensor? = null

    private var hudView: GameOverlayView? = null
    private var tabView: TextView? = null
    private var panelView: LinearLayout? = null
    private var isClosingPanel = false
    private var hudPaused = false
    private var lastGyroTs = 0L

    private var editTargetLabel: TextView? = null
    private var editSizeSeek: SeekBar? = null
    private var editVisibleCheck: CheckBox? = null

    private lateinit var pal: GlassPalette
    private var themeMode = "dark"
    private var blurPct = 50
    private var opacityPct = 45

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        try {
            wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            config = ControlConfig(this)
            ensureGyroDefaultOff()

            appearancePrefs = getSharedPreferences("gfn_appearance", Context.MODE_PRIVATE)
            refreshAppearanceState()

            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

            startForegroundNotif()
            VirtualHidController.bind(this)

            createHudOverlay()
            createFloatingTab()
            registerGyro()
        } catch (t: Throwable) {
            isRunning = false
            val msg = android.util.Log.getStackTraceString(t)
            getSharedPreferences("gfn_controls_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("last_crash", msg)
                .apply()
            stopSelf()
        }
    }

    private fun ensureGyroDefaultOff() {
        val p = getSharedPreferences("gfn_controls_prefs", Context.MODE_PRIVATE)
        if (!p.getBoolean(GYRO_INIT_KEY, false)) {
            config.gyroEnabled = false
            config.save()
            p.edit().putBoolean(GYRO_INIT_KEY, true).apply()
        }
    }

    // ------------------------------------------------------------------
    //  TEMA Y APARIENCIA SINCRONIZADOS CON MAINACTIVITY
    // ------------------------------------------------------------------

    private fun refreshAppearanceState() {
        themeMode = appearancePrefs.getString("theme", "dark") ?: "dark"
        blurPct = appearancePrefs.getInt("blur", 50).coerceIn(0, 100)
        opacityPct = appearancePrefs.getInt("opacity", 45).coerceIn(15, 70)
        pal = buildPalette()
    }

    private fun readWallpaper(): Pair<Int, Boolean>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
        return try {
            val wc = WallpaperManager.getInstance(this)
                .getWallpaperColors(WallpaperManager.FLAG_SYSTEM) ?: return null
            val colors = listOfNotNull(wc.primaryColor, wc.secondaryColor, wc.tertiaryColor)
                .map { it.toArgb() }
            if (colors.isEmpty()) return null
            val hsv = FloatArray(3)
            val best = colors.maxByOrNull {
                Color.colorToHSV(it, hsv)
                hsv[1]
            } ?: return null
            val isLight = ColorUtils.calculateLuminance(colors[0]) > 0.55
            Pair(best, isLight)
        } catch (_: Throwable) {
            null
        }
    }

    private fun tuneAccent(c: Int, light: Boolean): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[1] = hsv[1].coerceIn(0.55f, 1f)
        hsv[2] = if (light) hsv[2].coerceIn(0.45f, 0.70f) else hsv[2].coerceIn(0.75f, 1f)
        return Color.HSVToColor(hsv)
    }

    private fun withAlpha(c: Int, a: Int): Int =
        Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    private fun buildPalette(): GlassPalette {
        var light = false
        var accent = Color.parseColor("#FF3B5C")
        when (themeMode) {
            "light" -> {
                light = true
                accent = Color.parseColor("#C81E3A")
            }
            "wallpaper" -> {
                val wp = readWallpaper()
                if (wp != null) {
                    light = wp.second
                    accent = tuneAccent(wp.first, light)
                }
            }
        }

        val base = if (light) Color.rgb(244, 246, 250) else Color.rgb(18, 20, 26)
        val surfaceTone = if (light) Color.rgb(255, 255, 255) else Color.rgb(28, 31, 40)
        val panelTone = if (light) Color.rgb(248, 250, 253) else Color.rgb(20, 23, 30)
        val fillAlpha = (opacityPct / 100f * 255f).toInt()
        val panelAlpha = (205 + (opacityPct - 15) * 47 / 55).coerceIn(205, 252)
        val scrimAlpha = if (themeMode == "wallpaper") {
            if (light) 115 else 75
        } else {
            if (light) 165 else 95
        }

        return GlassPalette(
            isLight = light,
            accent = accent,
            accentSoft = if (light) accent else ColorUtils.blendARGB(accent, Color.WHITE, 0.22f),
            accentDark = ColorUtils.blendARGB(accent, Color.BLACK, 0.45f),
            text = if (light) Color.rgb(18, 20, 26) else Color.rgb(244, 246, 250),
            textSub = if (light) Color.rgb(55, 62, 75) else Color.rgb(182, 188, 204),
            textMuted = if (light) Color.rgb(95, 103, 120) else Color.rgb(132, 139, 156),
            cardFill = withAlpha(surfaceTone, fillAlpha),
            cardStroke = if (light) Color.argb(24, 18, 20, 26) else Color.argb(30, 255, 255, 255),
            panelFill = withAlpha(panelTone, panelAlpha),
            scrim = withAlpha(base, scrimAlpha),
            ok = if (light) Color.rgb(26, 128, 68) else Color.rgb(74, 204, 122)
        )
    }

    private fun applyPanelBlur(params: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val enabled = try {
                wm.isCrossWindowBlurEnabled
            } catch (_: Throwable) {
                false
            }
            if (enabled) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                params.blurBehindRadius = (blurPct / 100f * 36f * resources.displayMetrics.density).toInt()
            }
        }
    }

    // ------------------------------------------------------------------
    //  SERVICIO Y OVERLAY
    // ------------------------------------------------------------------

    private fun startForegroundNotif() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "GFN Touch Controls",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GFN Touch Controls activo")
            .setContentText("Tocá la pestaña discreta en pantalla para configurar")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Throwable) {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun baseLayoutParams(w: Int, h: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            w,
            h,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun createHudOverlay() {
        val view = GameOverlayView(
            context = this,
            config = config,
            onStateChanged = { config.save() },
            onSelectEditItem = { id -> syncEditPanelSelection(id) }
        )
        val params = baseLayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        wm.addView(view, params)
        hudView = view
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingTab() {
        val density = resources.displayMetrics.density
        val tab = TextView(this).apply {
            text = "≡ HUD"
            textSize = 11.5f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(
                (12 * density).toInt(),
                (7 * density).toInt(),
                (12 * density).toInt(),
                (7 * density).toInt()
            )
            background = GradientDrawable().apply {
                setColor(withAlpha(pal.accent, 215))
                cornerRadius = 16 * density
                setStroke((1.1f * density).toInt(), Color.argb(130, 255, 255, 255))
            }
            alpha = config.tabAlpha
        }

        val params = baseLayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (resources.displayMetrics.heightPixels * 0.35f).toInt()
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        tab.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downRawX).toInt()
                    val dy = (ev.rawY - downRawY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    if (moved) {
                        params.x = (startX + dx).coerceAtLeast(0)
                        params.y = (startY + dy).coerceAtLeast(0)
                        wm.updateViewLayout(tab, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved || (ev.rawX - downRawX) > 25) {
                        toggleControlCenter()
                    }
                    true
                }
                else -> false
            }
        }

        wm.addView(tab, params)
        tabView = tab
    }

    private fun toggleControlCenter() {
        if (panelView != null) closeControlCenter(animated = true) else openControlCenter(animated = true)
    }

    private fun closeControlCenter(animated: Boolean = false, onDone: (() -> Unit)? = null) {
        val currentPanel = panelView
        if (currentPanel == null) {
            onDone?.invoke()
            return
        }
        if (isClosingPanel) return
        isClosingPanel = true
        config.save()

        val finishRemoval = {
            try { wm.removeView(currentPanel) } catch (_: Throwable) {}
            if (panelView === currentPanel) {
                panelView = null
                editTargetLabel = null
                editSizeSeek = null
                editVisibleCheck = null
            }
            isClosingPanel = false
            onDone?.invoke()
        }

        if (!animated) {
            finishRemoval()
            return
        }

        val d = resources.displayMetrics.density
        currentPanel.animate()
            .alpha(0f)
            .translationX(-16f * d)
            .setDuration(150)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { finishRemoval() }
            .start()
    }

    private fun reopenControlCenter() {
        closeControlCenter(animated = false) {
            openControlCenter(animated = false)
        }
    }

    private fun openControlCenter(animated: Boolean = true) {
        if (panelView != null && !isClosingPanel) return
        refreshAppearanceState()
        val d = resources.displayMetrics.density
        val pad = (14 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(pal.panelFill)
                cornerRadius = 22 * d
                setStroke((1.1f * d).toInt(), pal.cardStroke)
            }
            if (animated) {
                alpha = 0f
                translationX = -16f * d
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Centro de Control"
            textSize = 15.5f
            setTextColor(pal.text)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClose = createChipButton("Cerrar", withAlpha(pal.text, 20), pal.textSub, d) {
            closeControlCenter(animated = true)
        }
        header.addView(title)
        header.addView(btnClose)
        root.addView(header)

        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.72f).toInt()
            ).apply { topMargin = (6 * d).toInt() }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (4 * d).toInt(), 0, (12 * d).toInt())
        }

        // --- ACCIONES RÁPIDAS ---
        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (2 * d).toInt(), 0, (6 * d).toInt())
        }

        lateinit var btnPause: TextView
        lateinit var btnEdit: TextView

        btnPause = createChipButton(
            text = if (hudPaused) "Mostrar HUD" else "Modo Cursor",
            bgColor = if (hudPaused) pal.accent else withAlpha(pal.text, 20),
            textColor = if (hudPaused) Color.WHITE else pal.text,
            d = d
        ) {
            hudPaused = !hudPaused
            hudView?.visibility = if (hudPaused) View.GONE else View.VISIBLE
            if (hudPaused) VirtualHidController.releaseAll()
            btnPause.text = if (hudPaused) "Mostrar HUD" else "Modo Cursor"
            (btnPause.background as? GradientDrawable)?.setColor(
                if (hudPaused) pal.accent else withAlpha(pal.text, 20)
            )
            btnPause.setTextColor(if (hudPaused) Color.WHITE else pal.text)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = (4 * d).toInt() }
        }

        val editingNow = hudView?.editMode == true
        btnEdit = createChipButton(
            text = if (editingNow) "Listo" else "Mover HUD",
            bgColor = if (editingNow) pal.accent else withAlpha(pal.text, 20),
            textColor = if (editingNow) Color.WHITE else pal.text,
            d = d
        ) {
            val hv = hudView ?: return@createChipButton
            hv.editMode = !hv.editMode
            if (hv.editMode && hudPaused) {
                hudPaused = false
                hv.visibility = View.VISIBLE
                btnPause.text = "Modo Cursor"
                (btnPause.background as? GradientDrawable)?.setColor(withAlpha(pal.text, 20))
                btnPause.setTextColor(pal.text)
            }
            val activeEdit = hv.editMode
            btnEdit.text = if (activeEdit) "Listo" else "Mover HUD"
            (btnEdit.background as? GradientDrawable)?.setColor(
                if (activeEdit) pal.accent else withAlpha(pal.text, 20)
            )
            btnEdit.setTextColor(if (activeEdit) Color.WHITE else pal.text)
            if (!activeEdit) config.save()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = (4 * d).toInt() }
        }

        quickRow.addView(btnPause)
        quickRow.addView(btnEdit)
        body.addView(quickRow)

        // --- BOTÓN SELECCIONADO (OPCIONES DESPLEGABLES) ---
        val editBox = createSubCard(d)
        val lblTarget = TextView(this).apply {
            setTextColor(pal.accentSoft)
            setTypeface(null, Typeface.BOLD)
            textSize = 12.5f
        }
        editTargetLabel = lblTarget
        editBox.addView(lblTarget)

        val chkVis = CheckBox(this).apply {
            text = "Mostrar en pantalla"
            setTextColor(pal.text)
            textSize = 12f
            buttonTintList = ColorStateList.valueOf(pal.accent)
            setOnCheckedChangeListener { _, checked ->
                val id = hudView?.selectedEditId ?: return@setOnCheckedChangeListener
                config.items[id]?.visible = checked
                hudView?.invalidate()
                config.save()
            }
        }
        editVisibleCheck = chkVis
        editBox.addView(chkVis)

        val seekSize = addSlider(editBox, "Tamaño", 30, 240, 60) { v ->
            val id = hudView?.selectedEditId ?: return@addSlider
            config.items[id]?.sizeDp = v.toFloat()
            hudView?.invalidate()
            config.save()
        }
        editSizeSeek = seekSize
        syncEditPanelSelection(hudView?.selectedEditId ?: ControlId.FIRE)
        body.addView(editBox)

        // --- GIROSCOPIO (DESACTIVADO POR DEFECTO + OPCIONES AL ACTIVAR) ---
        addSectionTitle(body, "Giroscopio")
        val gyroBox = createSubCard(d)
        val gyroOptionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setCollapsibleInitialState(config.gyroEnabled)
        }

        addSwitch(gyroBox, "Activar Giroscopio", config.gyroEnabled) { enabled ->
            config.gyroEnabled = enabled
            config.save()
            setSectionExpanded(gyroOptionsContainer, enabled, d)
        }

        addSwitch(gyroOptionsContainer, "Solo al Apuntar o Disparar", config.gyroOnlyAdsOrFire) {
            config.gyroOnlyAdsOrFire = it
            config.save()
        }
        addSwitch(gyroOptionsContainer, "Invertir eje X", config.gyroInvertX) {
            config.gyroInvertX = it
            config.save()
        }
        addSwitch(gyroOptionsContainer, "Invertir eje Y", config.gyroInvertY) {
            config.gyroInvertY = it
            config.save()
        }
        addSlider(gyroOptionsContainer, "Sensibilidad X", 2, 80, config.gyroSensX.toInt()) {
            config.gyroSensX = it.toFloat()
            config.save()
        }
        addSlider(gyroOptionsContainer, "Sensibilidad Y", 2, 80, config.gyroSensY.toInt()) {
            config.gyroSensY = it.toFloat()
            config.save()
        }
        addSlider(gyroOptionsContainer, "Deadzone", 0, 15, (config.gyroDeadzone * 100f).toInt()) {
            config.gyroDeadzone = it / 100f
            config.save()
        }

        gyroBox.addView(gyroOptionsContainer)
        body.addView(gyroBox)

        // --- MIRA TÁCTIL & JOYSTICK (DESPLEGABLE) ---
        addSectionTitle(body, "Mira y Joystick")
        val aimBox = createSubCard(d)
        val aimDetails = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setCollapsibleInitialState(false)
        }
        val btnAimToggle = createChipButton(
            text = "Ajustes de mira y sprint",
            bgColor = withAlpha(pal.text, 16),
            textColor = pal.text,
            d = d
        ) {
            val expand = aimDetails.visibility != View.VISIBLE
            setSectionExpanded(aimDetails, expand, d)
        }.apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        addSlider(aimDetails, "Trackpad X", 20, 400, (config.touchSensX * 100f).toInt()) {
            config.touchSensX = it / 100f
            config.save()
        }
        addSlider(aimDetails, "Trackpad Y", 20, 400, (config.touchSensY * 100f).toInt()) {
            config.touchSensY = it / 100f
            config.save()
        }
        addSwitch(aimDetails, "Auto-Sprint al borde", config.autoSprint) {
            config.autoSprint = it
            config.save()
        }
        addSwitch(aimDetails, "Apuntar (ADS) Toggle", config.aimToggleMode) {
            config.aimToggleMode = it
            config.save()
        }
        aimBox.addView(btnAimToggle)
        aimBox.addView(aimDetails)
        body.addView(aimBox)

        // --- TECLAS PERSONALIZADAS (AGREGAR / QUITAR) ---
        addSectionTitle(body, "Teclas del HUD")
        val keysBox = createSubCard(d)
        val customKeys = HudPresetManager.getCustomKeys(this)
        val btnAddKey = createChipButton(
            text = if (customKeys.isNotEmpty()) "+ Agregar tecla (${customKeys.size})" else "+ Agregar tecla",
            bgColor = pal.accent,
            textColor = Color.WHITE,
            d = d
        ) {
            showKeyPickerInPanel(d)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = if (customKeys.isNotEmpty()) (8 * d).toInt() else 0 }
        }
        keysBox.addView(btnAddKey)

        for (activeKey in customKeys) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 11 * d
                    setColor(withAlpha(pal.accent, 18))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (5 * d).toInt() }
            }
            val keyTv = TextView(this).apply {
                text = activeKey
                textSize = 12f
                setTextColor(pal.text)
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnRemove = createChipButton("Quitar", withAlpha(pal.accent, 34), pal.accentSoft, d) {
                HudPresetManager.removeCustomKey(this@OverlayService, activeKey)
                reopenControlCenter()
            }
            row.addView(keyTv)
            row.addView(btnRemove)
            keysBox.addView(row)
        }
        body.addView(keysBox)

        // --- VISIBILIDAD DE BOTONES (DESPLEGABLE) ---
        addSectionTitle(body, "Botones")
        val visBox = createSubCard(d)
        val visList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setCollapsibleInitialState(false)
        }
        val btnVisToggle = createChipButton(
            text = "Mostrar / ocultar botones",
            bgColor = withAlpha(pal.text, 16),
            textColor = pal.text,
            d = d
        ) {
            val expand = visList.visibility != View.VISIBLE
            setSectionExpanded(visList, expand, d)
        }.apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        for (id in ControlId.entries) {
            if (id == ControlId.JOYSTICK) continue
            val cb = CheckBox(this).apply {
                text = "${id.title} (${id.badge})"
                setTextColor(pal.textSub)
                textSize = 12f
                buttonTintList = ColorStateList.valueOf(pal.accent)
                isChecked = config.items[id]?.visible == true
                setOnCheckedChangeListener { _, isChecked ->
                    config.items[id]?.visible = isChecked
                    if (hudView?.selectedEditId == id) editVisibleCheck?.isChecked = isChecked
                    hudView?.invalidate()
                    config.save()
                }
            }
            visList.addView(cb)
        }
        visBox.addView(btnVisToggle)
        visBox.addView(visList)
        body.addView(visBox)

        // --- OPACIDAD Y ACCIONES FINALES ---
        addSectionTitle(body, "Opacidad y Overlay")
        val optBox = createSubCard(d)
        addSlider(optBox, "Botones HUD (%)", 15, 95, (config.hudAlpha * 100f).toInt()) {
            config.hudAlpha = it / 100f
            hudView?.invalidate()
            config.save()
        }
        addSlider(optBox, "Pestaña (%)", 15, 85, (config.tabAlpha * 100f).toInt()) {
            config.tabAlpha = it / 100f
            tabView?.alpha = config.tabAlpha
            config.save()
        }
        body.addView(optBox)

        val btnReset = createChipButton(
            text = "Restablecer posiciones",
            bgColor = withAlpha(pal.text, 20),
            textColor = pal.text,
            d = d
        ) {
            config.resetDefaults()
            config.gyroEnabled = false
            config.save()
            hudView?.invalidate()
            tabView?.alpha = config.tabAlpha
            closeControlCenter(animated = true)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * d).toInt() }
        }
        body.addView(btnReset)

        val btnStop = createChipButton(
            text = "Detener Overlay",
            bgColor = withAlpha(pal.accent, 34),
            textColor = pal.accentSoft,
            d = d
        ) {
            closeControlCenter(animated = true) {
                stopSelf()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * d).toInt() }
        }
        body.addView(btnStop)

        scroll.addView(body)
        root.addView(scroll)

        val panelW = (resources.displayMetrics.widthPixels * 0.44f).toInt()
            .coerceIn((270 * d).toInt(), (390 * d).toInt())
        val params = baseLayoutParams(panelW, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            x = (16 * d).toInt()
        }
        applyPanelBlur(params)
        wm.addView(root, params)
        panelView = root

        if (animated) {
            root.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(210)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun View.setCollapsibleInitialState(expanded: Boolean) {
        visibility = if (expanded) View.VISIBLE else View.GONE
        alpha = if (expanded) 1f else 0f
    }

    private fun setSectionExpanded(container: View, expanded: Boolean, d: Float) {
        container.animate().cancel()
        if (expanded) {
            container.visibility = View.VISIBLE
            container.translationY = -8f * d
            container.alpha = 0f
            container.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            container.animate()
                .alpha(0f)
                .translationY(-6f * d)
                .setDuration(140)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    container.visibility = View.GONE
                    container.translationY = 0f
                }
                .start()
        }
    }

    private fun createSubCard(d: Float): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((11 * d).toInt(), (9 * d).toInt(), (11 * d).toInt(), (9 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 15 * d
                setColor(withAlpha(pal.text, if (pal.isLight) 10 else 14))
                setStroke((1 * d).toInt(), pal.cardStroke)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * d).toInt() }
        }
    }

    private fun showKeyPickerInPanel(d: Float) {
        val existing = HudPresetManager.getCustomKeys(this)
        val candidates = HudPresetManager.availableExtraKeys.filter { !existing.contains(it) }

        val picker = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt())
            background = GradientDrawable().apply {
                setColor(pal.panelFill)
                cornerRadius = 22 * d
                setStroke((1.1f * d).toInt(), pal.cardStroke)
            }
            alpha = 0f
            translationX = -12f * d
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Elegir tecla"
            textSize = 15f
            setTextColor(pal.text)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnBack = createChipButton("Volver", withAlpha(pal.text, 20), pal.text, d) {
            reopenControlCenter()
        }
        header.addView(title)
        header.addView(btnBack)
        picker.addView(header)

        val sc = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.55f).toInt()
            ).apply { topMargin = (8 * d).toInt() }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        for (keyName in candidates) {
            val item = createChipButton(keyName, withAlpha(pal.text, 16), pal.text, d) {
                HudPresetManager.addCustomKey(this@OverlayService, keyName)
                reopenControlCenter()
            }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (6 * d).toInt() }
            }
            box.addView(item)
        }

        sc.addView(box)
        picker.addView(sc)

        closeControlCenter(animated = false)
        val panelW = (resources.displayMetrics.widthPixels * 0.42f).toInt()
            .coerceIn((260 * d).toInt(), (360 * d).toInt())
        val params = baseLayoutParams(panelW, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            x = (16 * d).toInt()
        }
        applyPanelBlur(params)
        wm.addView(picker, params)
        panelView = picker

        picker.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createChipButton(
        text: String,
        bgColor: Int,
        textColor: Int,
        d: Float,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor(bgColor)
                setStroke((1 * d).toInt(), pal.cardStroke)
            }
            isClickable = true
            setOnTouchListener { view, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN ->
                        view.animate().scaleX(0.965f).scaleY(0.965f).setDuration(75).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
                false
            }
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }
    }

    private fun syncEditPanelSelection(id: ControlId) {
        val st = config.items[id] ?: return
        editTargetLabel?.text = "${id.title} (${id.badge})"
        editSizeSeek?.progress = st.sizeDp.toInt().coerceIn(30, 240)
        editVisibleCheck?.isChecked = st.visible
    }

    private fun addSectionTitle(parent: LinearLayout, text: String) {
        val d = resources.displayMetrics.density
        val tv = TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(pal.accentSoft)
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding((2 * d).toInt(), (10 * d).toInt(), 0, (4 * d).toInt())
        }
        parent.addView(tv)
    }

    private fun addSwitch(
        parent: LinearLayout,
        label: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        @SuppressLint("UseSwitchCompatOrMaterialCode")
        val sw = Switch(this).apply {
            text = label
            setTextColor(pal.text)
            textSize = 12.5f
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            val offThumb = if (pal.isLight) Color.rgb(176, 176, 176) else Color.rgb(136, 136, 136)
            thumbTintList = ColorStateList(states, intArrayOf(pal.accent, offThumb))
            trackTintList = ColorStateList(
                states,
                intArrayOf(withAlpha(pal.accent, 110), withAlpha(pal.text, 60))
            )
        }
        parent.addView(sw)
    }

    private fun addSlider(
        parent: LinearLayout,
        label: String,
        minVal: Int,
        maxVal: Int,
        initial: Int,
        onChange: (Int) -> Unit
    ): SeekBar {
        val tv = TextView(this).apply {
            text = "$label: $initial"
            setTextColor(pal.textSub)
            textSize = 12f
        }
        val sb = SeekBar(this).apply {
            max = maxVal - minVal
            progress = (initial - minVal).coerceIn(0, max)
            val tint = ColorStateList.valueOf(pal.accent)
            progressTintList = tint
            thumbTintList = tint
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
        return sb
    }

    private fun registerGyro() {
        lastGyroTs = 0L
        gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        if (!config.gyroEnabled || hudPaused) {
            lastGyroTs = event.timestamp
            return
        }
        val hv = hudView ?: return
        if (hv.editMode) {
            lastGyroTs = event.timestamp
            return
        }
        if (config.gyroOnlyAdsOrFire && !hv.isAdsOrFiringActive) {
            lastGyroTs = event.timestamp
            return
        }
        if (lastGyroTs == 0L) {
            lastGyroTs = event.timestamp
            return
        }
        val dt = (event.timestamp - lastGyroTs) * 1e-9f
        lastGyroTs = event.timestamp
        if (dt <= 0f || dt > 0.1f) return

        val gx = event.values[0]
        val gy = event.values[1]

        val rot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hv.display?.rotation ?: tabView?.display?.rotation ?: Surface.ROTATION_90
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.rotation
        }

        val yawRate: Float
        val pitchRate: Float
        when (rot) {
            Surface.ROTATION_90 -> {
                yawRate = -gx
                pitchRate = gy
            }
            Surface.ROTATION_270 -> {
                yawRate = gx
                pitchRate = -gy
            }
            Surface.ROTATION_180 -> {
                yawRate = -gy
                pitchRate = gx
            }
            else -> {
                yawRate = gy
                pitchRate = -gx
            }
        }

        val dz = config.gyroDeadzone
        val cleanYaw = if (abs(yawRate) < dz) 0f else yawRate
        val cleanPitch = if (abs(pitchRate) < dz) 0f else pitchRate
        if (cleanYaw == 0f && cleanPitch == 0f) return

        val xSign = if (config.gyroInvertX) -1f else 1f
        val ySign = if (config.gyroInvertY) -1f else 1f

        val dx = cleanYaw * dt * config.gyroSensX * 12f * xSign
        val dy = cleanPitch * dt * config.gyroSensY * 12f * ySign

        VirtualHidController.addMouseDelta(dx, dy)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        isRunning = false
        sensorManager.unregisterListener(this)
        closeControlCenter(animated = false)
        hudView?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        tabView?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        VirtualHidController.unbind(this)
        super.onDestroy()
    }
}
