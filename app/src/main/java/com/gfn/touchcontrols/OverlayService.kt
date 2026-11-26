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
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
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
        var isRunning = false
            private set
    }

    private data class PanelPalette(
        val isLight: Boolean,
        val accent: Int,
        val accentSoft: Int,
        val accentDark: Int,
        val text: Int,
        val textSub: Int,
        val textMuted: Int,
        val cardFill: Int,
        val cardStroke: Int,
        val panelFill: Int
    )

    private lateinit var wm: WindowManager
    private lateinit var config: ControlConfig
    private lateinit var sensorManager: SensorManager
    private var gyroSensor: Sensor? = null
    private lateinit var pal: PanelPalette

    private var hudView: GameOverlayView? = null
    private var tabView: TextView? = null
    private var panelView: LinearLayout? = null
    private var hudPaused = false
    private var lastGyroTs = 0L

    private var editTargetLabel: TextView? = null
    private var editSizeSeek: SeekBar? = null
    private var editVisibleCheck: CheckBox? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        try {
            wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            config = ControlConfig(this)
            pal = buildPanelPalette()
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

    private fun withAlpha(c: Int, a: Int): Int =
        Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

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

    private fun buildPanelPalette(): PanelPalette {
        val prefs = getSharedPreferences("gfn_appearance", Context.MODE_PRIVATE)
        val themeMode = prefs.getString("theme", "dark") ?: "dark"
        val opacityPct = prefs.getInt("opacity", 45).coerceIn(15, 70)

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

        val surfaceTone = if (light) Color.rgb(255, 255, 255) else Color.rgb(28, 31, 40)
        val panelTone = if (light) Color.rgb(248, 250, 252) else Color.rgb(22, 25, 33)
        val fillAlpha = (opacityPct / 100f * 255f).toInt()

        return PanelPalette(
            isLight = light,
            accent = accent,
            accentSoft = if (light) accent else ColorUtils.blendARGB(accent, Color.WHITE, 0.22f),
            accentDark = ColorUtils.blendARGB(accent, Color.BLACK, 0.45f),
            text = if (light) Color.rgb(18, 20, 26) else Color.rgb(244, 246, 250),
            textSub = if (light) Color.rgb(55, 62, 75) else Color.rgb(182, 188, 204),
            textMuted = if (light) Color.rgb(95, 103, 120) else Color.rgb(132, 139, 156),
            cardFill = withAlpha(surfaceTone, fillAlpha),
            cardStroke = if (light) Color.argb(28, 18, 20, 26) else Color.argb(32, 255, 255, 255),
            panelFill = withAlpha(panelTone, 246)
        )
    }

    private fun applyTabStyle(tab: TextView, d: Float) {
        tab.setTextColor(pal.text)
        tab.background = GradientDrawable().apply {
            setColor(withAlpha(pal.panelFill, 205))
            cornerRadius = 16 * d
            setStroke((1 * d).toInt(), pal.cardStroke)
        }
        tab.alpha = config.tabAlpha
    }

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
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(
                (12 * density).toInt(),
                (7 * density).toInt(),
                (12 * density).toInt(),
                (7 * density).toInt()
            )
            applyTabStyle(this, density)
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
        if (panelView != null) closeControlCenter() else openControlCenter()
    }

    private fun closeControlCenter() {
        panelView?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        panelView = null
        editTargetLabel = null
        editSizeSeek = null
        editVisibleCheck = null
        config.save()
    }

    private fun createChip(
        label: String,
        bgColor: Int,
        textColor: Int,
        d: Float,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            setPadding((12 * d).toInt(), (9 * d).toInt(), (12 * d).toInt(), (9 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor(bgColor)
                setStroke((1 * d).toInt(), pal.cardStroke)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun openControlCenter() {
        pal = buildPanelPalette()
        val d = resources.displayMetrics.density
        tabView?.let { applyTabStyle(it, d) }

        val pad = (16 * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(pal.panelFill)
                cornerRadius = 22 * d
                setStroke((1 * d).toInt(), pal.cardStroke)
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (10 * d).toInt())
        }
        val title = TextView(this).apply {
            text = "Centro de Control"
            textSize = 17f
            setTextColor(pal.text)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClose = createChip("Cerrar", pal.accentDark, Color.WHITE, d) {
            closeControlCenter()
        }
        header.addView(title)
        header.addView(btnClose)
        root.addView(header)

        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.68f).toInt()
            )
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (4 * d).toInt(), 0, (10 * d).toInt())
        }

        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (2 * d).toInt(), 0, (6 * d).toInt())
        }
        val btnPause = createChip(
            if (hudPaused) "Mostrar HUD" else "Modo Cursor",
            withAlpha(pal.text, 24),
            pal.text,
            d
        ) {}.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = (4 * d).toInt() }
        }
        val btnEdit = createChip(
            if (hudView?.editMode == true) "Listo (Guardar)" else "Mover Botones",
            withAlpha(pal.text, 24),
            pal.text,
            d
        ) {}.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = (4 * d).toInt() }
        }

        btnPause.setOnClickListener {
            hudPaused = !hudPaused
            hudView?.visibility = if (hudPaused) View.GONE else View.VISIBLE
            if (hudPaused) VirtualHidController.releaseAll()
            btnPause.text = if (hudPaused) "Mostrar HUD" else "Modo Cursor"
        }
        btnEdit.setOnClickListener {
            val hv = hudView ?: return@setOnClickListener
            hv.editMode = !hv.editMode
            if (hv.editMode && hudPaused) {
                hudPaused = false
                hv.visibility = View.VISIBLE
                btnPause.text = "Modo Cursor"
            }
            btnEdit.text = if (hv.editMode) "Listo (Guardar)" else "Mover Botones"
            (btnEdit.background as? GradientDrawable)?.setColor(
                if (hv.editMode) pal.accent else withAlpha(pal.text, 24)
            )
            btnEdit.setTextColor(if (hv.editMode) Color.WHITE else pal.text)
            if (!hv.editMode) config.save()
        }

        quickRow.addView(btnPause)
        quickRow.addView(btnEdit)
        body.addView(quickRow)

        addSectionTitle(body, "Botón seleccionado")
        val lblTarget = TextView(this).apply {
            setTextColor(pal.accentSoft)
            setTypeface(null, Typeface.BOLD)
            textSize = 13f
            setPadding(0, (2 * d).toInt(), 0, (4 * d).toInt())
        }
        editTargetLabel = lblTarget
        body.addView(lblTarget)

        val chkVis = CheckBox(this).apply {
            text = "Mostrar en pantalla"
            setTextColor(pal.text)
            textSize = 12.5f
            buttonTintList = ColorStateList.valueOf(pal.accent)
            setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
            setOnCheckedChangeListener { _, checked ->
                val id = hudView?.selectedEditId ?: return@setOnCheckedChangeListener
                config.items[id]?.visible = checked
                hudView?.invalidate()
                config.save()
            }
        }
        editVisibleCheck = chkVis
        body.addView(chkVis)

        val seekSize = addSlider(body, "Tamaño del botón / área", 30, 240, 60) { v ->
            val id = hudView?.selectedEditId ?: return@addSlider
            config.items[id]?.sizeDp = v.toFloat()
            hudView?.invalidate()
            config.save()
        }
        editSizeSeek = seekSize
        syncEditPanelSelection(hudView?.selectedEditId ?: ControlId.FIRE)

        addSectionTitle(body, "Botones en pantalla")
        for (id in ControlId.entries) {
            if (id == ControlId.JOYSTICK) continue
            val cb = CheckBox(this).apply {
                text = "${id.title} (${id.badge})"
                setTextColor(pal.textSub)
                textSize = 12f
                buttonTintList = ColorStateList.valueOf(pal.accent)
                isChecked = config.items[id]?.visible == true
                setPadding(0, (2 * d).toInt(), 0, (2 * d).toInt())
                setOnCheckedChangeListener { _, isChecked ->
                    config.items[id]?.visible = isChecked
                    if (hudView?.selectedEditId == id) editVisibleCheck?.isChecked = isChecked
                    hudView?.invalidate()
                    config.save()
                }
            }
            body.addView(cb)
        }

        addSectionTitle(body, "Giroscopio (RAW Ultrarrápido)")
        addSwitch(body, "Activar Giroscopio", config.gyroEnabled) {
            config.gyroEnabled = it
            config.save()
        }
        addSwitch(body, "Solo al apuntar o disparar", config.gyroOnlyAdsOrFire) {
            config.gyroOnlyAdsOrFire = it
            config.save()
        }
        addSwitch(body, "Invertir eje horizontal (X)", config.gyroInvertX) {
            config.gyroInvertX = it
            config.save()
        }
        addSwitch(body, "Invertir eje vertical (Y)", config.gyroInvertY) {
            config.gyroInvertY = it
            config.save()
        }
        addSlider(body, "Sensibilidad Giro X", 2, 80, config.gyroSensX.toInt()) {
            config.gyroSensX = it.toFloat()
            config.save()
        }
        addSlider(body, "Sensibilidad Giro Y", 2, 80, config.gyroSensY.toInt()) {
            config.gyroSensY = it.toFloat()
            config.save()
        }
        addSlider(body, "Anti-temblor (Deadzone)", 0, 15, (config.gyroDeadzone * 100f).toInt()) {
            config.gyroDeadzone = it / 100f
            config.save()
        }

        addSectionTitle(body, "Mira Táctil / Trackpad")
        addSlider(body, "Sensibilidad Trackpad X", 20, 400, (config.touchSensX * 100f).toInt()) {
            config.touchSensX = it / 100f
            config.save()
        }
        addSlider(body, "Sensibilidad Trackpad Y", 20, 400, (config.touchSensY * 100f).toInt()) {
            config.touchSensY = it / 100f
            config.save()
        }
        addSwitch(body, "Auto-Sprint al empujar joystick", config.autoSprint) {
            config.autoSprint = it
            config.save()
        }
        addSwitch(body, "Apuntar (ADS) por toque (Toggle)", config.aimToggleMode) {
            config.aimToggleMode = it
            config.save()
        }

        addSectionTitle(body, "Vista y Opacidad")
        addSlider(body, "Opacidad de botones (%)", 15, 95, (config.hudAlpha * 100f).toInt()) {
            config.hudAlpha = it / 100f
            hudView?.invalidate()
            config.save()
        }
        addSlider(body, "Opacidad de pestaña lateral (%)", 15, 85, (config.tabAlpha * 100f).toInt()) {
            config.tabAlpha = it / 100f
            tabView?.alpha = config.tabAlpha
            config.save()
        }

        val btnReset = createChip(
            "Restablecer posiciones por defecto",
            withAlpha(pal.text, 24),
            pal.text,
            d
        ) {
            config.resetDefaults()
            hudView?.invalidate()
            tabView?.alpha = config.tabAlpha
            closeControlCenter()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * d).toInt() }
        }
        body.addView(btnReset)

        val btnStop = createChip(
            "Cerrar Overlay completamente",
            pal.accentDark,
            Color.WHITE,
            d
        ) {
            stopSelf()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * d).toInt() }
        }
        body.addView(btnStop)

        scroll.addView(body)
        root.addView(scroll)

        val panelW = (resources.displayMetrics.widthPixels * 0.44f).toInt()
            .coerceIn((280 * d).toInt(), (390 * d).toInt())
        val params = baseLayoutParams(panelW, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            x = (16 * d).toInt()
        }
        wm.addView(root, params)
        panelView = root
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
            textSize = 13f
            setTextColor(pal.accentSoft)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, (14 * d).toInt(), 0, (4 * d).toInt())
        }
        parent.addView(tv)
    }

    private fun addSwitch(
        parent: LinearLayout,
        label: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        val d = resources.displayMetrics.density
        @SuppressLint("UseSwitchCompatOrMaterialCode")
        val sw = Switch(this).apply {
            text = label
            setTextColor(pal.text)
            textSize = 12.5f
            isChecked = initial
            setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
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
        val d = resources.displayMetrics.density
        val tv = TextView(this).apply {
            text = "$label: $initial"
            setTextColor(pal.textSub)
            textSize = 12f
            setPadding(0, (6 * d).toInt(), 0, (2 * d).toInt())
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
        closeControlCenter()
        hudView?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        tabView?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        VirtualHidController.unbind(this)
        super.onDestroy()
    }
}
