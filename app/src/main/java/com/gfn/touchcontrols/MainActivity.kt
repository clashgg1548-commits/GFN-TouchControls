package com.gfn.touchcontrols

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import rikka.shizuku.Shizuku
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    private class StepRefs(
        val root: LinearLayout,
        val badge: StepBadgeView,
        val title: TextView,
        val sub: TextView,
        val bg: GradientDrawable
    )

    private lateinit var prefs: SharedPreferences
    private var themeMode = "dark"      // "dark", "light" o "wallpaper"
    private var blurPct = 50            // 0..100
    private var opacityPct = 45         // 15..70
    private lateinit var pal: GlassPalette
    private var settingsDialog: Dialog? = null

    private lateinit var outerContainer: LinearLayout
    private lateinit var rootLayout: LinearLayout
    private lateinit var logoBadge: LinearLayout
    private lateinit var logoIcon: VectorIconView
    private lateinit var titleMain: TextView
    private lateinit var titleSub: TextView
    private lateinit var btnSettings: LinearLayout
    private lateinit var btnSettingsBg: GradientDrawable
    private lateinit var btnSettingsIcon: VectorIconView

    private lateinit var heroCard: LinearLayout
    private lateinit var heroCardBg: GradientDrawable
    private lateinit var gfnTitleTv: TextView
    private lateinit var checkOverlay: StatusCheckView
    private lateinit var checkShizukuAlive: StatusCheckView
    private lateinit var checkShizukuPerm: StatusCheckView
    private lateinit var checkMouse: StatusCheckView
    private lateinit var tvGfnSub: TextView

    private lateinit var sectionLabel: TextView
    private lateinit var stepOverlay: StepRefs
    private lateinit var stepShizuku: StepRefs
    private lateinit var stepHud: StepRefs

    private lateinit var playCard: LinearLayout
    private lateinit var playTitle: TextView
    private lateinit var playSub: TextView
    private lateinit var playIcon: VectorIconView
    private lateinit var playArrow: VectorIconView
    private lateinit var playReadyBg: GradientDrawable
    private lateinit var playIdleBg: GradientDrawable
    private var allReady = false

    private lateinit var footerFrame: TextView
    private lateinit var footerFrameBg: GradientDrawable

    private var topInsetPx = 0
    private var bottomInsetPx = 0
    private var uiReady = false
    private var breathingAnimator: ValueAnimator? = null

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            safeConnect()
        }
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("gfn_appearance", Context.MODE_PRIVATE)
        themeMode = prefs.getString("theme", "dark") ?: "dark"
        blurPct = prefs.getInt("blur", 50).coerceIn(0, 100)
        opacityPct = prefs.getInt("opacity", 45).coerceIn(15, 70)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            VirtualHidController.statusListener = { runOnUiThread { refreshStatus() } }
        } catch (_: Throwable) {
        }

        buildUi()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
            }
        }
    }

    // ------------------------------------------------------------------
    //  APARIENCIA: paleta, desenfoque de fondo y barras del sistema
    // ------------------------------------------------------------------

    private fun saveAppearance() {
        prefs.edit()
            .putString("theme", themeMode)
            .putInt("blur", blurPct)
            .putInt("opacity", opacityPct)
            .apply()
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
        val panelTone = if (light) Color.rgb(250, 251, 253) else Color.rgb(22, 25, 33)
        val fillAlpha = (opacityPct / 100f * 255f).toInt()
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
            cardStroke = if (light) Color.argb(26, 18, 20, 26) else Color.argb(28, 255, 255, 255),
            panelFill = withAlpha(panelTone, 248),
            scrim = withAlpha(base, scrimAlpha),
            ok = if (light) Color.rgb(26, 128, 68) else Color.rgb(74, 204, 122)
        )
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun crossWindowBlurEnabled(): Boolean {
        return try {
            windowManager.isCrossWindowBlurEnabled
        } catch (_: Throwable) {
            false
        }
    }

    private fun blurSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && crossWindowBlurEnabled()
    }

    private fun applyWindowBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val radius = if (crossWindowBlurEnabled()) {
                (blurPct / 100f * 45f * resources.displayMetrics.density).toInt()
            } else {
                0
            }
            try {
                window.setBackgroundBlurRadius(radius)
                window.setDimAmount(if (radius > 0) 0.14f else 0.0f)
            } catch (_: Throwable) {
            }
        }
    }

    private fun applySystemBars() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.isAppearanceLightStatusBars = pal.isLight
        c.isAppearanceLightNavigationBars = pal.isLight
    }

    private fun applyAppearanceLive() {
        saveAppearance()
        pal = buildPalette()
        val d = resources.displayMetrics.density

        rootLayout.setBackgroundColor(pal.scrim)
        (logoBadge.background as? GradientDrawable)?.setColor(withAlpha(pal.accent, if (pal.isLight) 28 else 36))
        logoIcon.iconColor = pal.accent

        titleMain.setTextColor(pal.text)
        titleSub.setTextColor(pal.textSub)

        btnSettingsBg.setColor(pal.cardFill)
        btnSettingsBg.setStroke((1 * d).toInt(), pal.cardStroke)
        btnSettingsIcon.iconColor = pal.text

        heroCardBg.setColor(pal.cardFill)
        heroCardBg.setStroke((1 * d).toInt(), pal.cardStroke)
        gfnTitleTv.setTextColor(pal.text)

        checkOverlay.applyPalette(pal)
        checkShizukuAlive.applyPalette(pal)
        checkShizukuPerm.applyPalette(pal)
        checkMouse.applyPalette(pal)

        sectionLabel.setTextColor(pal.textMuted)

        playReadyBg.colors = intArrayOf(
            pal.accent,
            ColorUtils.blendARGB(pal.accent, pal.accentDark, 0.55f)
        )
        playIdleBg.setColor(pal.cardFill)
        playIdleBg.setStroke((1 * d).toInt(), pal.cardStroke)

        footerFrame.setTextColor(pal.textSub)
        footerFrameBg.setColor(withAlpha(pal.cardFill, 140))

        applyInsetsPadding()
        applySystemBars()
        applyWindowBlur()
        refreshStatus()
    }

    private fun applyInsetsPadding() {
        val d = resources.displayMetrics.density
        val padH = (18 * d).toInt()
        val fallbackTop = resources.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it > 0 }
            ?.let { resources.getDimensionPixelSize(it) }
            ?: (28 * d).toInt()
        val safeTop = maxOf(topInsetPx, fallbackTop)
        val safeBottom = maxOf(bottomInsetPx, (12 * d).toInt())
        outerContainer.setPadding(0, 0, 0, 0)
        rootLayout.setPadding(
            padH,
            safeTop + (12 * d).toInt(),
            padH,
            safeBottom + (8 * d).toInt()
        )
    }

    // ------------------------------------------------------------------
    //  PANTALLA PRINCIPAL
    // ------------------------------------------------------------------

    private fun buildUi() {
        uiReady = false
        breathingAnimator?.cancel()
        pal = buildPalette()

        val d = resources.displayMetrics.density
        val padH = (18 * d).toInt()

        outerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padH, (14 * d).toInt(), padH, (12 * d).toInt())
            setBackgroundColor(pal.scrim)
        }

        // --- 1. CABECERA LIMPIA ---
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((2 * d).toInt(), (6 * d).toInt(), (2 * d).toInt(), (18 * d).toInt())
        }

        logoIcon = VectorIconView(this, VectorType.GAMEPAD, pal.accent, dp(24f))
        logoBadge = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            val sz = (44 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            background = createEdgeShadedBg(14 * d, withAlpha(pal.accent, if (pal.isLight) 28 else 36), Color.TRANSPARENT)
            addView(logoIcon)
        }

        val titleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * d).toInt(), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        titleMain = TextView(this).apply {
            text = "GFN"
            textSize = 23f
            setTextColor(pal.text)
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.02f
        }

        titleSub = TextView(this).apply {
            text = "Touch Controls"
            textSize = 13f
            setTextColor(pal.textSub)
            setTypeface(null, Typeface.NORMAL)
        }
        titleContainer.addView(titleMain)
        titleContainer.addView(titleSub)

        btnSettingsBg = createEdgeShadedBg(14 * d, pal.cardFill, pal.cardStroke)
        btnSettingsIcon = VectorIconView(this, VectorType.GEAR, pal.text, dp(20f))
        btnSettings = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            val sz = (42 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            background = btnSettingsBg
            addView(btnSettingsIcon)
        }
        makeClickable(btnSettings) { showSettingsDialog() }

        header.addView(logoBadge)
        header.addView(titleContainer)
        header.addView(btnSettings)
        rootLayout.addView(header)

        // --- 2. TARJETA DE ESTADO ---
        heroCardBg = createEdgeShadedBg(22 * d, pal.cardFill, pal.cardStroke)
        heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * d).toInt(), (18 * d).toInt(), (18 * d).toInt(), (16 * d).toInt())
            background = heroCardBg
        }

        val heroTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val gfnLogo = VectorIconView(this, VectorType.GFN_LOGO, Color.parseColor("#76B900"), dp(36f))
        val gfnTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * d).toInt(), 0, 0, 0)
        }
        gfnTitleTv = TextView(this).apply {
            text = "GeForce NOW"
            textSize = 15.5f
            setTextColor(pal.text)
            setTypeface(null, Typeface.BOLD)
        }
        tvGfnSub = TextView(this).apply {
            text = "Esperando..."
            textSize = 12f
            setTextColor(pal.textSub)
        }
        gfnTextCol.addView(gfnTitleTv)
        gfnTextCol.addView(tvGfnSub)
        heroTop.addView(gfnLogo)
        heroTop.addView(gfnTextCol)
        heroCard.addView(heroTop)

        checkOverlay = StatusCheckView(this, "Overlay", VectorType.OVERLAY_LAYERS, pal)
        checkShizukuAlive = StatusCheckView(this, "Shizuku activo", VectorType.SHIZUKU_LINK, pal)
        checkShizukuPerm = StatusCheckView(this, "Permiso Shizuku", VectorType.SHIZUKU_LINK, pal)
        checkMouse = StatusCheckView(this, "Ratón virtual", VectorType.HUD_MONITOR, pal)

        heroCard.addView(
            pillRow(d, checkOverlay, checkShizukuAlive),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (16 * d).toInt() }
        )
        heroCard.addView(
            pillRow(d, checkShizukuPerm, checkMouse),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (8 * d).toInt() }
        )
        rootLayout.addView(heroCard)

        rootLayout.addView(space(20 * d))

        // --- 3. PASOS NUMERADOS ---
        sectionLabel = TextView(this).apply {
            text = "CONFIGURACIÓN"
            textSize = 10.5f
            setTextColor(pal.textMuted)
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.16f
            setPadding((6 * d).toInt(), 0, 0, (10 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        rootLayout.addView(sectionLabel)

        stepOverlay = createStepCard(d, 1, "Permiso Overlay", "Mostrar sobre apps") {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Throwable) {
            }
        }
        stepShizuku = createStepCard(d, 2, "Conectar Shizuku", "Ratón virtual (uinput)") {
            connectShizuku()
        }
        stepHud = createStepCard(d, 3, "Iniciar HUD", "Activar controles") {
            toggleOverlay()
        }
        rootLayout.addView(stepOverlay.root)
        rootLayout.addView(stepShizuku.root)
        rootLayout.addView(stepHud.root)

        rootLayout.addView(space(18 * d))

        // --- 4. BOTÓN GRANDE JUGAR ---
        playReadyBg = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                pal.accent,
                ColorUtils.blendARGB(pal.accent, pal.accentDark, 0.55f)
            )
        ).apply {
            cornerRadius = 20 * d
        }
        playIdleBg = createEdgeShadedBg(20 * d, pal.cardFill, pal.cardStroke)

        playCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * d).toInt(), 0, (20 * d).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (70 * d).toInt()
            )
            background = playIdleBg
        }
        playIcon = VectorIconView(this, VectorType.PLAY_ARROW, pal.textMuted, 24 * d)
        val playTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * d).toInt(), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        playTitle = TextView(this).apply {
            text = "JUGAR"
            textSize = 15.5f
            setTextColor(pal.textMuted)
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.12f
        }
        playSub = TextView(this).apply {
            text = "Completa los pasos"
            textSize = 11.5f
            setTextColor(pal.textMuted)
        }
        playTextCol.addView(playTitle)
        playTextCol.addView(playSub)
        playArrow = VectorIconView(this, VectorType.CHEVRON_RIGHT, pal.textMuted, 16 * d)
        playCard.addView(playIcon)
        playCard.addView(playTextCol)
        playCard.addView(playArrow)
        makeClickable(playCard) {
            if (allReady) {
                val launch = packageManager.getLaunchIntentForPackage("com.nvidia.geforcenow")
                if (launch != null) {
                    startActivity(launch)
                } else {
                    playSub.text = "GFN no instalado"
                }
            } else {
                playSub.text = "Primero completa los pasos de arriba"
            }
        }
        rootLayout.addView(playCard)

        // --- 5. ESPACIADOR FLEXIBLE + PIE DE PÁGINA ARRIBA DE LA BARRA ---
        val bottomSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (20 * d).toInt(),
                1f
            )
        }
        rootLayout.addView(bottomSpacer)

        footerFrameBg = createEdgeShadedBg(12 * d, withAlpha(pal.cardFill, 140), Color.TRANSPARENT)
        footerFrame = TextView(this).apply {
            text = "GFN TOUCH CONTROLS"
            textSize = 10f
            setTextColor(pal.textSub)
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.20f
            gravity = Gravity.CENTER
            setPadding((16 * d).toInt(), (7 * d).toInt(), (16 * d).toInt(), (7 * d).toInt())
            background = footerFrameBg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * d).toInt()
                bottomMargin = (4 * d).toInt()
            }
        }
        rootLayout.addView(footerFrame)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            addView(rootLayout)
        }
        outerContainer.addView(scroll)
        setContentView(outerContainer)

        applyInsetsPadding()
        ViewCompat.setOnApplyWindowInsetsListener(outerContainer) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            topInsetPx = maxOf(bars.top, cutout.top)
            bottomInsetPx = bars.bottom
            applyInsetsPadding()
            insets
        }
        ViewCompat.requestApplyInsets(outerContainer)

        applySystemBars()
        applyWindowBlur()

        uiReady = true
        refreshStatus()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    @SuppressLint("ClickableViewAccessibility")
    private fun makeClickable(v: View, onClick: () -> Unit) {
        v.isClickable = true
        v.setOnTouchListener { view, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.975f).scaleY(0.975f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
        v.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
    }

    private fun pillRow(d: Float, a: View, b: View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                a,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { rightMargin = (5 * d).toInt() }
            )
            addView(
                b,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { leftMargin = (5 * d).toInt() }
            )
        }
    }

    private fun createStepCard(
        d: Float,
        number: Int,
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ): StepRefs {
        val bg = createEdgeShadedBg(18 * d, pal.cardFill, pal.cardStroke)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * d).toInt() }
            background = bg
        }

        val badgeSize = (30 * d).toInt()
        val badge = StepBadgeView(this, number, badgeSize.toFloat(), pal)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * d).toInt(), 0, (8 * d).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvTitle = TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(pal.text)
            setTypeface(null, Typeface.BOLD)
        }
        val tvSub = TextView(this).apply {
            text = subtitle
            textSize = 11.5f
            setTextColor(pal.textSub)
            setPadding(0, (2 * d).toInt(), 0, 0)
        }
        textCol.addView(tvTitle)
        textCol.addView(tvSub)

        val arrow = VectorIconView(this, VectorType.CHEVRON_RIGHT, pal.textSub, 15 * d)

        card.addView(badge, LinearLayout.LayoutParams(badgeSize, badgeSize))
        card.addView(textCol)
        card.addView(arrow)

        makeClickable(card, onClick)
        return StepRefs(card, badge, tvTitle, tvSub, bg)
    }

    private fun applyStepStyle(s: StepRefs, done: Boolean, current: Boolean) {
        val d = resources.displayMetrics.density
        s.badge.updatePalette(pal)
        s.badge.setState(done, current)
        when {
            current -> {
                s.bg.setColor(withAlpha(pal.text, if (pal.isLight) 16 else 20))
                s.bg.setStroke((1 * d).toInt(), withAlpha(pal.accent, 52))
            }
            done -> {
                s.bg.setColor(pal.cardFill)
                s.bg.setStroke((1 * d).toInt(), withAlpha(pal.ok, 55))
            }
            else -> {
                s.bg.setColor(pal.cardFill)
                s.bg.setStroke((1 * d).toInt(), pal.cardStroke)
            }
        }
        s.title.setTextColor(if (done) pal.textSub else pal.text)
    }

    // ------------------------------------------------------------------
    //  AJUSTES (engranaje)
    // ------------------------------------------------------------------

    private fun showSettingsDialog() {
        val config = ControlConfig(this)
        val d = resources.displayMetrics.density
        val dialog = Dialog(this)
        settingsDialog = dialog
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.55f)

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
        }
        val title = TextView(this).apply {
            text = "Ajustes"
            textSize = 17f
            setTextColor(pal.text)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClose = createStyledActionChip("Cerrar", pal.accentDark, Color.WHITE, d) {
            dialog.dismiss()
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

        addDialogTitle(body, "Apariencia")
        addThemeSelector(body) { mode ->
            if (mode != themeMode) {
                themeMode = mode
                applyAppearanceLive()
                dialog.dismiss()
                showSettingsDialog()
            }
        }
        addDialogSlider(body, "Desenfoque", 0, 100, blurPct) {
            blurPct = it
            saveAppearance()
            applyWindowBlur()
        }
        addDialogSlider(
            body, "Opacidad de paneles (%) · máx. 70", 15, 70, opacityPct,
            onStop = {
                applyAppearanceLive()
            }
        ) {
            opacityPct = it
            applyAppearanceLive()
        }
        if (!blurSupported()) {
            val hint = TextView(this).apply {
                text = "El desenfoque necesita Android 12 o superior y los efectos de ventana activados."
                textSize = 11f
                setTextColor(pal.textMuted)
                setPadding(0, (6 * d).toInt(), 0, 0)
            }
            body.addView(hint)
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

        addDialogTitle(body, "Opacidad de Interfaz (HUD)")
        addDialogSlider(body, "Opacidad de botones HUD (%)", 15, 95, (config.hudAlpha * 100f).toInt()) { config.hudAlpha = it / 100f; config.save() }
        addDialogSlider(body, "Opacidad de pestaña lateral (%)", 15, 85, (config.tabAlpha * 100f).toInt()) { config.tabAlpha = it / 100f; config.save() }

        val btnReset = Button(this).apply {
            text = "Restablecer valores por defecto"
            textSize = 12f
            backgroundTintList = ColorStateList.valueOf(pal.accentDark)
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

    // Helper para unificar botones/chips en diálogos y Centro de Control
    private fun createStyledActionChip(
        text: String,
        bgColor: Int,
        textColor: Int,
        d: Float,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor(bgColor)
            }
            makeClickable(this, onClick)
        }
    }

    private fun addDialogTitle(parent: LinearLayout, title: String) {
        val tv = TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(pal.accentSoft)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(14f).toInt(), 0, dp(4f).toInt())
        }
        parent.addView(tv)
    }

    private fun addThemeSelector(parent: LinearLayout, onPick: (String) -> Unit) {
        val d = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
        }
        val options = listOf("dark" to "Oscuro", "light" to "Claro", "wallpaper" to "Fondo")
        for ((key, label) in options) {
            val selected = key == themeMode
            val chip = TextView(this).apply {
                text = label
                textSize = 12.5f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (selected) Color.WHITE else pal.textSub)
                setPadding(0, (11 * d).toInt(), 0, (11 * d).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 12 * d
                    setColor(if (selected) pal.accent else withAlpha(pal.text, 28))
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins((3 * d).toInt(), 0, (3 * d).toInt(), 0)
                }
                setOnClickListener { onPick(key) }
            }
            row.addView(chip)
        }
        parent.addView(row)
    }

    private fun addDialogSwitch(parent: LinearLayout, label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
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

    private fun addDialogSlider(
        parent: LinearLayout,
        label: String,
        minVal: Int,
        maxVal: Int,
        initial: Int,
        onStop: (() -> Unit)? = null,
        onChange: (Int) -> Unit
    ) {
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
                override fun onStopTrackingTouch(s: SeekBar?) {
                    onStop?.invoke()
                }
            })
        }
        parent.addView(tv)
        parent.addView(sb)
    }

    // ------------------------------------------------------------------
    //  UTILIDADES VISUALES
    // ------------------------------------------------------------------

    private fun createEdgeShadedBg(
        radius: Float,
        fillColor: Int,
        edgeShadeColor: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = radius
            if (edgeShadeColor != Color.TRANSPARENT) {
                setStroke((1f * resources.displayMetrics.density).toInt(), edgeShadeColor)
            }
        }
    }

    private fun space(h: Float) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h.toInt())
    }

    override fun onResume() {
        super.onResume()
        if (themeMode == "wallpaper" && uiReady) {
            val np = buildPalette()
            if (np.accent != pal.accent || np.isLight != pal.isLight) {
                applyAppearanceLive()
                return
            }
        }
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
            stepHud.root.postDelayed({ refreshStatus() }, 200)
        } else {
            val intent = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            stepHud.root.postDelayed({ refreshStatus() }, 300)
        }
    }

    private fun refreshStatus() {
        if (!uiReady) return

        val overlayOk = Settings.canDrawOverlays(this)
        val shizukuAlive = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        val shizukuPerm = shizukuAlive && try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        val mouseOk = VirtualHidController.isConnected
        val connected = shizukuPerm && mouseOk
        val hudRunning = OverlayService.isRunning

        checkOverlay.setChecked(overlayOk)
        checkShizukuAlive.setChecked(shizukuAlive)
        checkShizukuPerm.setChecked(shizukuPerm)
        checkMouse.setChecked(mouseOk)

        tvGfnSub.text = if (connected) "Conectado" else "Listo para iniciar"
        tvGfnSub.setTextColor(if (connected) pal.ok else pal.textSub)

        val current = when {
            !overlayOk -> 1
            !connected -> 2
            !hudRunning -> 3
            else -> 0
        }

        applyStepStyle(stepOverlay, overlayOk, current == 1)
        stepOverlay.sub.text = if (overlayOk) "Permiso concedido" else "Mostrar sobre apps"

        applyStepStyle(stepShizuku, connected, current == 2)
        stepShizuku.sub.text = when {
            connected -> "Ratón virtual conectado"
            !shizukuAlive -> "Abre Shizuku y actívalo"
            !shizukuPerm -> "Toca para dar permiso"
            else -> "Toca para conectar el ratón"
        }

        applyStepStyle(stepHud, hudRunning, current == 3)
        stepHud.title.text = if (hudRunning) "Detener HUD" else "Iniciar HUD"
        stepHud.sub.text = if (hudRunning) "Cerrar controles" else "Activar controles"

        allReady = current == 0
        if (allReady) {
            playCard.background = playReadyBg
            playIcon.iconColor = Color.WHITE
            playArrow.iconColor = Color.WHITE
            playTitle.setTextColor(Color.WHITE)
            playSub.setTextColor(Color.argb(225, 255, 255, 255))
            playSub.text = "Abrir GeForce NOW"
        } else {
            playCard.background = playIdleBg
            playIcon.iconColor = pal.textMuted
            playArrow.iconColor = pal.textMuted
            playTitle.setTextColor(pal.textMuted)
            playSub.setTextColor(pal.textMuted)
            playSub.text = "Completa el paso $current"
        }
    }

    override fun onDestroy() {
        breathingAnimator?.cancel()
        settingsDialog?.dismiss()
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) {
        }
        VirtualHidController.statusListener = null
        super.onDestroy()
    }
}

// ----------------------------------------------------------------------
//  PALETA DE COLORES DEL TEMA
// ----------------------------------------------------------------------

private fun withAlpha(c: Int, a: Int): Int =
    Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

class GlassPalette(
    val isLight: Boolean,
    val accent: Int,
    val accentSoft: Int,
    val accentDark: Int,
    val text: Int,
    val textSub: Int,
    val textMuted: Int,
    val cardFill: Int,
    val cardStroke: Int,
    val panelFill: Int,
    val scrim: Int,
    val ok: Int
)

// --- ICONOS VECTORIALES CLEAN ---

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
class VectorIconView(
    context: Context,
    private val type: VectorType,
    color: Int,
    private val sizePx: Float
) : View(context) {

    var iconColor: Int = color
        set(value) {
            field = value
            invalidate()
        }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.TRANSPARENT)
        layoutParams = ViewGroup.LayoutParams(sizePx.toInt(), sizePx.toInt())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val target = sizePx.toInt()
        setMeasuredDimension(target, target)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        strokePaint.color = iconColor
        strokePaint.strokeWidth = (w * 0.09f).coerceAtLeast(2.2f)
        fillPaint.color = iconColor

        when (type) {
            VectorType.GAMEPAD -> {
                val r = RectF(w * 0.12f, h * 0.26f, w * 0.88f, h * 0.76f)
                canvas.drawRoundRect(r, w * 0.22f, w * 0.22f, strokePaint)
                canvas.drawLine(w * 0.27f, h * 0.51f, w * 0.41f, h * 0.51f, strokePaint)
                canvas.drawLine(w * 0.34f, h * 0.44f, w * 0.34f, h * 0.58f, strokePaint)
                canvas.drawCircle(w * 0.64f, h * 0.46f, w * 0.045f, fillPaint)
                canvas.drawCircle(w * 0.73f, h * 0.55f, w * 0.045f, fillPaint)
            }
            VectorType.GEAR -> {
                val cx = w * 0.5f
                val cy = h * 0.5f
                val radius = w * 0.27f
                canvas.drawCircle(cx, cy, radius, strokePaint)
                val teeth = 6
                for (i in 0 until teeth) {
                    val angle = (i * (360.0 / teeth)) * (Math.PI / 180.0)
                    val x1 = cx + (radius * 0.92f * cos(angle)).toFloat()
                    val y1 = cy + (radius * 0.92f * sin(angle)).toFloat()
                    val x2 = cx + (w * 0.43f * cos(angle)).toFloat()
                    val y2 = cy + (h * 0.43f * sin(angle)).toFloat()
                    canvas.drawLine(x1, y1, x2, y2, strokePaint)
                }
                canvas.drawCircle(cx, cy, w * 0.095f, fillPaint)
            }
            VectorType.GFN_LOGO -> {
                path.reset()
                path.moveTo(w * 0.5f, h * 0.14f)
                path.lineTo(w * 0.85f, h * 0.34f)
                path.lineTo(w * 0.85f, h * 0.74f)
                path.lineTo(w * 0.5f, h * 0.93f)
                path.lineTo(w * 0.15f, h * 0.74f)
                path.lineTo(w * 0.15f, h * 0.34f)
                path.close()
                canvas.drawPath(path, strokePaint)
                canvas.drawCircle(w * 0.5f, h * 0.535f, w * 0.135f, fillPaint)
            }
            VectorType.OVERLAY_LAYERS -> {
                val rBack = RectF(w * 0.12f, h * 0.12f, w * 0.62f, h * 0.62f)
                strokePaint.alpha = 150
                canvas.drawRoundRect(rBack, w * 0.12f, w * 0.12f, strokePaint)
                strokePaint.alpha = 255
                val rFront = RectF(w * 0.36f, h * 0.36f, w * 0.88f, h * 0.88f)
                canvas.drawRoundRect(rFront, w * 0.12f, w * 0.12f, strokePaint)
            }
            VectorType.SHIZUKU_LINK -> {
                path.reset()
                path.moveTo(w * 0.25f, h * 0.54f)
                path.lineTo(w * 0.46f, h * 0.33f)
                path.lineTo(w * 0.67f, h * 0.54f)
                path.lineTo(w * 0.46f, h * 0.75f)
                path.close()
                canvas.drawPath(path, strokePaint)
                canvas.drawLine(w * 0.56f, h * 0.44f, w * 0.78f, h * 0.22f, strokePaint)
                canvas.drawLine(w * 0.22f, h * 0.78f, w * 0.42f, h * 0.58f, strokePaint)
            }
            VectorType.HUD_MONITOR -> {
                val rScreen = RectF(w * 0.12f, h * 0.18f, w * 0.88f, h * 0.66f)
                canvas.drawRoundRect(rScreen, w * 0.10f, w * 0.10f, strokePaint)
                canvas.drawLine(w * 0.5f, h * 0.66f, w * 0.5f, h * 0.83f, strokePaint)
                canvas.drawLine(w * 0.34f, h * 0.83f, w * 0.66f, h * 0.83f, strokePaint)
                canvas.drawCircle(w * 0.5f, h * 0.42f, w * 0.05f, fillPaint)
            }
            VectorType.PLAY_ARROW -> {
                path.reset()
                path.moveTo(w * 0.32f, h * 0.24f)
                path.lineTo(w * 0.80f, h * 0.50f)
                path.lineTo(w * 0.32f, h * 0.76f)
                path.close()
                canvas.drawPath(path, fillPaint)
            }
            VectorType.CHEVRON_RIGHT -> {
                path.reset()
                path.moveTo(w * 0.35f, h * 0.22f)
                path.lineTo(w * 0.66f, h * 0.50f)
                path.lineTo(w * 0.35f, h * 0.78f)
                canvas.drawPath(path, strokePaint)
            }
        }
    }
}

@SuppressLint("ViewConstructor")
class StepBadgeView(
    context: Context,
    private val number: Int,
    private val sizePx: Float,
    private var pal: GlassPalette
) : View(context) {

    private var done = false
    private var current = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val path = Path()

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun updatePalette(newPal: GlassPalette) {
        pal = newPal
        invalidate()
    }

    fun setState(done: Boolean, current: Boolean) {
        this.done = done
        this.current = current
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx.toInt(), sizePx.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f
        val r = w / 2f - 1f

        fillPaint.color = when {
            done -> pal.ok
            current -> pal.accent
            else -> withAlpha(pal.text, 28)
        }
        canvas.drawCircle(cx, cy, r, fillPaint)

        if (done) {
            linePaint.strokeWidth = w * 0.085f
            path.reset()
            path.moveTo(w * 0.30f, h * 0.52f)
            path.lineTo(w * 0.44f, h * 0.66f)
            path.lineTo(w * 0.71f, h * 0.37f)
            canvas.drawPath(path, linePaint)
        } else {
            textPaint.color = if (current) Color.WHITE else pal.textSub
            textPaint.textSize = w * 0.44f
            val y = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(number.toString(), cx, y, textPaint)
        }
    }
}

@SuppressLint("ViewConstructor")
class StatusCheckView(
    context: Context,
    title: String,
    iconType: VectorType,
    private var pal: GlassPalette
) : LinearLayout(context) {

    private val d = resources.displayMetrics.density
    private val iconSize = (15f * d).toInt()
    private val iconView = VectorIconView(context, iconType, pal.textMuted, iconSize.toFloat())
    private val label = TextView(context)
    private val pillBg = GradientDrawable()
    private var checkedState = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())

        pillBg.cornerRadius = 14 * d
        background = pillBg

        val lp = LayoutParams(iconSize, iconSize).apply {
            setMargins(0, 0, (7 * d).toInt(), 0)
        }
        iconView.layoutParams = lp

        label.text = title
        label.textSize = 11.5f
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END

        addView(iconView)
        addView(label)

        setChecked(false)
    }

    fun applyPalette(newPal: GlassPalette) {
        pal = newPal
        setChecked(checkedState)
    }

    fun setChecked(ok: Boolean) {
        checkedState = ok
        iconView.iconColor = if (ok) pal.ok else pal.textMuted
        pillBg.setColor(if (ok) withAlpha(pal.ok, 34) else withAlpha(pal.text, 18))
        pillBg.setStroke((1f * d).toInt(), if (ok) withAlpha(pal.ok, 75) else pal.cardStroke)
        label.setTextColor(if (ok) pal.text else pal.textMuted)
    }
}
