package com.gfn.touchcontrols

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

@SuppressLint("ViewConstructor")
class GameOverlayView(
    context: Context,
    private val config: ControlConfig,
    private val onStateChanged: () -> Unit,
    private val onSelectEditItem: (ControlId) -> Unit
) : View(context) {

    var editMode: Boolean = false
        set(value) {
            field = value
            if (value) {
                VirtualHidController.releaseAll()
                activePointers.clear()
                pressedButtons.clear()
                aimToggled = false
                joyDx = 0f
                joyDy = 0f
            }
            invalidate()
        }

    var selectedEditId: ControlId = ControlId.FIRE
        set(value) {
            field = value
            invalidate()
        }

    var isAdsOrFiringActive: Boolean = false
        private set

    private var aimToggled = false
    private val pressedButtons = HashSet<ControlId>()

    private sealed class PointerRole {
        data class Trackpad(var lastX: Float, var lastY: Float) : PointerRole()
        data class Joystick(val startX: Float, val startY: Float) : PointerRole()
        data class Button(val id: ControlId) : PointerRole()
        data class EditDrag(val id: ControlId, val offsetX: Float, val offsetY: Float) : PointerRole()
    }

    private val activePointers = HashMap<Int, PointerRole>()

    private var joyDx = 0f
    private var joyDy = 0f
    private var joySprinting = false

    private val density = resources.displayMetrics.density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val tempRect = RectF()
    private val tempPath = Path()

    private fun dp(v: Float): Float = v * density

    private fun centerOf(st: ControlItemState): Pair<Float, Float> {
        return (st.xFrac * width) to (st.yFrac * height)
    }

    private fun radiusOf(st: ControlItemState): Float = dp(st.sizeDp) * 0.5f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            VirtualHidController.screenWidth = w.toFloat()
            VirtualHidController.screenHeight = h.toFloat()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, w, h))
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val baseAlpha = if (editMode) 0.85f else config.hudAlpha

        for (id in ControlId.entries) {
            val st = config.items[id] ?: continue
            if (!st.visible && !editMode) continue

            val (cx, cy) = centerOf(st)
            val r = radiusOf(st)
            val itemAlpha = if (!st.visible && editMode) 0.25f else baseAlpha

            when (id) {
                ControlId.JOYSTICK -> drawLiquidJoystick(canvas, cx, cy, r, itemAlpha)
                ControlId.TRACKPAD -> drawLiquidTrackpad(canvas, cx, cy, r, itemAlpha)
                else -> drawLiquidActionButton(canvas, cx, cy, r, itemAlpha, st)
            }

            if (editMode && id == selectedEditId) {
                strokePaint.shader = null
                strokePaint.color = Color.parseColor("#00E5FF")
                strokePaint.strokeWidth = dp(2.5f)
                strokePaint.alpha = 240
                if (id == ControlId.TRACKPAD) {
                    val wBox = r * 1.5f
                    val hBox = r * 1.1f
                    tempRect.set(cx - wBox, cy - hBox, cx + wBox, cy + hBox)
                    canvas.drawRoundRect(tempRect, dp(22f), dp(22f), strokePaint)
                } else {
                    canvas.drawCircle(cx, cy, r + dp(5f), strokePaint)
                }
            }
        }
    }

    // --- JOYSTICK 100% LIMPIO ESTILO LIQUID GLASS (SIN WASD) ---
    private fun drawLiquidJoystick(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        alpha: Float
    ) {
        val a255 = (alpha * 255).toInt().coerceIn(20, 255)

        // 1. Base exterior cóncava de cristal ahumado
        fillPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(
                Color.argb((a255 * 0.15f).toInt(), 255, 255, 255),
                Color.argb((a255 * 0.38f).toInt(), 12, 16, 26),
                Color.argb((a255 * 0.60f).toInt(), 6, 8, 14)
            ),
            floatArrayOf(0.0f, 0.70f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, fillPaint)

        // 2. Anillo de cristal exterior con reflejo superior ("Rim Light")
        strokePaint.shader = LinearGradient(
            cx, cy - r, cx, cy + r,
            intArrayOf(
                Color.argb((a255 * 0.75f).toInt(), 255, 255, 255),
                Color.argb((a255 * 0.20f).toInt(), 180, 210, 255),
                Color.argb((a255 * 0.10f).toInt(), 255, 255, 255)
            ),
            floatArrayOf(0.0f, 0.5f, 1.0f),
            Shader.TileMode.CLAMP
        )
        strokePaint.strokeWidth = dp(1.8f)
        canvas.drawCircle(cx, cy, r, strokePaint)

        // 3. Anillo concéntrico interno minimalista (guía sutil)
        strokePaint.shader = null
        strokePaint.color = if (joySprinting) Color.parseColor("#FFD54F") else Color.WHITE
        strokePaint.alpha = (a255 * 0.20f).toInt()
        strokePaint.strokeWidth = dp(1f)
        canvas.drawCircle(cx, cy, r * 0.60f, strokePaint)

        // 4. Perilla (Knob) de cristal 3D con luz especular
        val maxDist = r * 0.62f
        val kx = cx + joyDx * maxDist
        val ky = cy + joyDy * maxDist
        val kr = r * 0.38f

        // Sombra de cristal de la perilla
        fillPaint.shader = null
        fillPaint.color = Color.argb((a255 * 0.45f).toInt(), 0, 0, 0)
        canvas.drawCircle(kx, ky + dp(3f), kr, fillPaint)

        // Vidrio con luz radial arriba a la izquierda
        fillPaint.shader = RadialGradient(
            kx - kr * 0.35f, ky - kr * 0.35f, kr * 1.3f,
            intArrayOf(
                if (joySprinting) Color.argb(a255, 255, 238, 150) else Color.argb((a255 * 0.95f).toInt(), 245, 250, 255),
                if (joySprinting) Color.argb(a255, 255, 179, 0) else Color.argb((a255 * 0.65f).toInt(), 120, 160, 210),
                Color.argb((a255 * 0.85f).toInt(), 18, 26, 42)
            ),
            floatArrayOf(0.0f, 0.45f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(kx, ky, kr, fillPaint)

        // Borde fino de cristal en la perilla
        strokePaint.shader = LinearGradient(
            kx, ky - kr, kx, ky + kr,
            intArrayOf(
                Color.argb(a255, 255, 255, 255),
                Color.argb((a255 * 0.3f).toInt(), 255, 255, 255)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        strokePaint.strokeWidth = dp(1.5f)
        canvas.drawCircle(kx, ky, kr, strokePaint)
    }

    // --- TRACKPAD ESTILO LOSA DE CRISTAL ESMERILADO (FROSTED GLASS) ---
    private fun drawLiquidTrackpad(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        alpha: Float
    ) {
        val a255 = (alpha * 255).toInt().coerceIn(20, 255)
        val wBox = r * 1.45f
        val hBox = r * 1.05f
        tempRect.set(cx - wBox, cy - hBox, cx + wBox, cy + hBox)

        // Degradado de cristal templado oscuro
        fillPaint.shader = LinearGradient(
            cx - wBox, cy - hBox, cx + wBox, cy + hBox,
            intArrayOf(
                Color.argb((a255 * 0.28f).toInt(), 160, 190, 240),
                Color.argb((a255 * 0.35f).toInt(), 14, 18, 28),
                Color.argb((a255 * 0.50f).toInt(), 8, 10, 16)
            ),
            floatArrayOf(0.0f, 0.4f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(tempRect, dp(22f), dp(22f), fillPaint)

        // Borde superior iluminado (Rim Light)
        strokePaint.shader = LinearGradient(
            cx, cy - hBox, cx, cy + hBox,
            intArrayOf(
                Color.argb((a255 * 0.70f).toInt(), 255, 255, 255),
                Color.argb((a255 * 0.25f).toInt(), 100, 180, 255),
                Color.argb((a255 * 0.12f).toInt(), 255, 255, 255)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        strokePaint.strokeWidth = dp(1.6f)
        canvas.drawRoundRect(tempRect, dp(22f), dp(22f), strokePaint)

        // Retícula central de precisión
        strokePaint.shader = null
        strokePaint.color = Color.WHITE
        strokePaint.alpha = (a255 * 0.28f).toInt()
        strokePaint.strokeWidth = dp(1.2f)
        canvas.drawLine(cx - dp(14f), cy, cx + dp(14f), cy, strokePaint)
        canvas.drawLine(cx, cy - dp(14f), cx, cy + dp(14f), strokePaint)

        textPaint.shader = null
        textPaint.color = Color.WHITE
        textPaint.alpha = (a255 * 0.65f).toInt()
        textPaint.textSize = dp(10.5f)
        canvas.drawText("TRACKPAD MIRA", cx, cy + hBox - dp(12f), textPaint)
    }

    // --- BOTONES CON CRISTAL LÍQUIDO Y BRILLO BISORT ---
    private fun drawLiquidActionButton(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        alpha: Float,
        st: ControlItemState
    ) {
        val isPressed = pressedButtons.contains(st.id) || (st.id == ControlId.AIM && aimToggled)
        val a255 = (alpha * 255).toInt().coerceIn(20, 255)

        // Relleno de cristal
        if (isPressed) {
            // Cristal comprimido iluminado desde adentro
            fillPaint.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(
                    Color.argb((a255 * 0.95f).toInt(), 0, 229, 255),
                    Color.argb((a255 * 0.75f).toInt(), 24, 70, 130),
                    Color.argb((a255 * 0.85f).toInt(), 8, 16, 32)
                ),
                floatArrayOf(0.0f, 0.65f, 1.0f),
                Shader.TileMode.CLAMP
            )
        } else {
            // Vidrio translúcido reposado con degradado de luz superior
            val isFire = st.id == ControlId.FIRE || st.id == ControlId.FIRE_LEFT
            val isAim = st.id == ControlId.AIM
            val tintR = if (isFire) 255 else if (isAim) 80 else 180
            val tintG = if (isFire) 110 else if (isAim) 180 else 200
            val tintB = if (isFire) 64 else if (isAim) 255 else 235

            fillPaint.shader = LinearGradient(
                cx, cy - r, cx, cy + r,
                intArrayOf(
                    Color.argb((a255 * 0.32f).toInt(), tintR, tintG, tintB),
                    Color.argb((a255 * 0.38f).toInt(), 20, 26, 40),
                    Color.argb((a255 * 0.62f).toInt(), 8, 12, 20)
                ),
                floatArrayOf(0.0f, 0.45f, 1.0f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, r, fillPaint)

        // Borde de cristal biselado con reflejo luminoso
        strokePaint.shader = LinearGradient(
            cx, cy - r, cx, cy + r,
            intArrayOf(
                if (isPressed) Color.WHITE else Color.argb((a255 * 0.85f).toInt(), 255, 255, 255),
                if (isPressed) Color.argb(a255, 0, 229, 255) else Color.argb((a255 * 0.25f).toInt(), 160, 200, 255),
                Color.argb((a255 * 0.12f).toInt(), 255, 255, 255)
            ),
            floatArrayOf(0.0f, 0.55f, 1.0f),
            Shader.TileMode.CLAMP
        )
        strokePaint.strokeWidth = dp(1.6f)
        canvas.drawCircle(cx, cy, r, strokePaint)

        // Íconos nítidos
        iconPaint.shader = null
        iconPaint.color = if (isPressed) Color.WHITE else Color.WHITE
        iconPaint.alpha = if (isPressed) 255 else (a255 * 0.95f).toInt()
        iconPaint.strokeWidth = dp(1.9f)
        iconFillPaint.shader = null
        iconFillPaint.color = iconPaint.color
        iconFillPaint.alpha = iconPaint.alpha

        drawVectorIcon(canvas, st, cx, cy, r * 0.46f)

        // Badge / tecla con brillo sutil
        if (st.id.icon != IconStyle.SLOT) {
            badgePaint.shader = null
            badgePaint.color = if (isPressed) Color.parseColor("#E0F7FA") else Color.WHITE
            badgePaint.alpha = (a255 * 0.80f).toInt()
            badgePaint.textSize = (r * 0.26f).coerceAtLeast(dp(8.5f))
            canvas.drawText(st.id.badge, cx, cy + r * 0.73f, badgePaint)
        }
    }

    private fun drawVectorIcon(canvas: Canvas, st: ControlItemState, cx: Float, cy: Float, s: Float) {
        val oy = cy - s * 0.14f
        when (st.id.icon) {
            IconStyle.FIRE -> {
                tempPath.reset()
                tempPath.moveTo(cx - s * 0.35f, oy + s * 0.55f)
                tempPath.lineTo(cx - s * 0.35f, oy - s * 0.05f)
                tempPath.quadTo(cx, oy - s * 0.78f, cx + s * 0.35f, oy - s * 0.05f)
                tempPath.lineTo(cx + s * 0.35f, oy + s * 0.55f)
                tempPath.close()
                canvas.drawPath(tempPath, iconPaint)
                canvas.drawLine(cx - s * 0.35f, oy + s * 0.25f, cx + s * 0.35f, oy + s * 0.25f, iconPaint)
            }
            IconStyle.AIM -> {
                canvas.drawCircle(cx, oy, s * 0.62f, iconPaint)
                canvas.drawLine(cx - s * 0.88f, oy, cx - s * 0.32f, oy, iconPaint)
                canvas.drawLine(cx + s * 0.32f, oy, cx + s * 0.88f, oy, iconPaint)
                canvas.drawLine(cx, oy - s * 0.88f, cx, oy - s * 0.32f, iconPaint)
                canvas.drawLine(cx, oy + s * 0.32f, cx, oy + s * 0.88f, iconPaint)
                canvas.drawCircle(cx, oy, dp(2f), iconFillPaint)
            }
            IconStyle.JUMP -> {
                tempPath.reset()
                tempPath.moveTo(cx, oy - s * 0.65f)
                tempPath.lineTo(cx - s * 0.55f, oy + s * 0.05f)
                tempPath.moveTo(cx, oy - s * 0.65f)
                tempPath.lineTo(cx + s * 0.55f, oy + s * 0.05f)
                tempPath.moveTo(cx, oy - s * 0.65f)
                tempPath.lineTo(cx, oy + s * 0.55f)
                canvas.drawPath(tempPath, iconPaint)
                canvas.drawLine(cx - s * 0.5f, oy + s * 0.62f, cx + s * 0.5f, oy + s * 0.62f, iconPaint)
            }
            IconStyle.CROUCH -> {
                canvas.drawCircle(cx + s * 0.12f, oy - s * 0.45f, s * 0.22f, iconPaint)
                tempPath.reset()
                tempPath.moveTo(cx + s * 0.08f, oy - s * 0.20f)
                tempPath.lineTo(cx - s * 0.22f, oy + s * 0.20f)
                tempPath.lineTo(cx + s * 0.25f, oy + s * 0.32f)
                tempPath.lineTo(cx + s * 0.20f, oy + s * 0.65f)
                canvas.drawPath(tempPath, iconPaint)
            }
            IconStyle.PRONE -> {
                canvas.drawCircle(cx + s * 0.52f, oy + s * 0.15f, s * 0.20f, iconPaint)
                canvas.drawLine(cx - s * 0.68f, oy + s * 0.35f, cx + s * 0.28f, oy + s * 0.28f, iconPaint)
                canvas.drawLine(cx - s * 0.68f, oy + s * 0.55f, cx + s * 0.68f, oy + s * 0.55f, iconPaint)
            }
            IconStyle.RELOAD -> {
                tempRect.set(cx - s * 0.55f, oy - s * 0.55f, cx + s * 0.55f, oy + s * 0.55f)
                canvas.drawArc(tempRect, -40f, 280f, false, iconPaint)
                canvas.drawLine(cx + s * 0.32f, oy - s * 0.55f, cx + s * 0.60f, oy - s * 0.28f, iconPaint)
                canvas.drawLine(cx + s * 0.60f, oy - s * 0.28f, cx + s * 0.25f, oy - s * 0.15f, iconPaint)
            }
            IconStyle.GRENADE -> {
                tempRect.set(cx - s * 0.42f, oy - s * 0.28f, cx + s * 0.42f, oy + s * 0.58f)
                canvas.drawOval(tempRect, iconPaint)
                canvas.drawLine(cx - s * 0.40f, oy + s * 0.15f, cx + s * 0.40f, oy + s * 0.15f, iconPaint)
                canvas.drawLine(cx - s * 0.22f, oy - s * 0.28f, cx - s * 0.22f, oy - s * 0.55f, iconPaint)
                canvas.drawLine(cx + s * 0.22f, oy - s * 0.28f, cx + s * 0.22f, oy - s * 0.55f, iconPaint)
                canvas.drawLine(cx - s * 0.22f, oy - s * 0.55f, cx + s * 0.38f, oy - s * 0.55f, iconPaint)
                canvas.drawCircle(cx - s * 0.42f, oy - s * 0.42f, s * 0.16f, iconPaint)
            }
            IconStyle.SPRINT -> {
                tempPath.reset()
                tempPath.moveTo(cx - s * 0.45f, oy + s * 0.20f)
                tempPath.lineTo(cx, oy - s * 0.25f)
                tempPath.lineTo(cx + s * 0.45f, oy + s * 0.20f)
                tempPath.moveTo(cx - s * 0.45f, oy + s * 0.55f)
                tempPath.lineTo(cx, oy + s * 0.10f)
                tempPath.lineTo(cx + s * 0.45f, oy + s * 0.55f)
                canvas.drawPath(tempPath, iconPaint)
            }
            IconStyle.MELEE -> {
                canvas.drawLine(cx - s * 0.48f, oy + s * 0.48f, cx + s * 0.52f, oy - s * 0.52f, iconPaint)
                canvas.drawLine(cx - s * 0.38f, oy + s * 0.08f, cx - s * 0.08f, oy + s * 0.38f, iconPaint)
            }
            IconStyle.INTERACT -> {
                tempRect.set(cx - s * 0.48f, oy - s * 0.45f, cx + s * 0.48f, oy + s * 0.45f)
                canvas.drawRoundRect(tempRect, dp(6f), dp(6f), iconPaint)
                canvas.drawCircle(cx, oy, s * 0.16f, iconFillPaint)
            }
            IconStyle.PING -> {
                tempPath.reset()
                tempPath.moveTo(cx, oy + s * 0.62f)
                tempPath.lineTo(cx - s * 0.44f, oy - s * 0.05f)
                tempPath.quadTo(cx, oy - s * 0.72f, cx + s * 0.44f, oy - s * 0.05f)
                tempPath.close()
                canvas.drawPath(tempPath, iconPaint)
                canvas.drawCircle(cx, oy - s * 0.08f, s * 0.14f, iconFillPaint)
            }
            IconStyle.SLOT -> {
                textPaint.shader = null
                textPaint.color = iconPaint.color
                textPaint.alpha = iconPaint.alpha
                textPaint.textSize = s * 1.15f
                canvas.drawText(st.id.badge, cx, cy + textPaint.textSize * 0.35f, textPaint)
            }
            IconStyle.MAP, IconStyle.TAB, IconStyle.ESC -> {
                textPaint.shader = null
                textPaint.color = iconPaint.color
                textPaint.alpha = iconPaint.alpha
                textPaint.textSize = s * 0.72f
                canvas.drawText(st.id.badge, cx, oy + textPaint.textSize * 0.4f, textPaint)
            }
            IconStyle.JOYSTICK, IconStyle.TRACKPAD -> {}
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width == 0 || height == 0) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                val x = event.getX(idx)
                val y = event.getY(idx)
                handlePointerDown(pid, x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    handlePointerMove(pid, event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                handlePointerUp(pid)
            }
        }
        return true
    }

    private fun hitTestControl(x: Float, y: Float, includeHiddenInEdit: Boolean): ControlItemState? {
        for (id in ControlId.entries.reversed()) {
            val st = config.items[id] ?: continue
            if (!st.visible && !includeHiddenInEdit) continue
            val (cx, cy) = centerOf(st)
            val r = radiusOf(st)

            if (id == ControlId.TRACKPAD) {
                val wBox = r * 1.45f
                val hBox = r * 1.05f
                if (x >= cx - wBox && x <= cx + wBox && y >= cy - hBox && y <= cy + hBox) {
                    return st
                }
            } else {
                if (hypot(x - cx, y - cy) <= r * 1.12f) {
                    return st
                }
            }
        }
        return null
    }

    private fun handlePointerDown(pid: Int, x: Float, y: Float) {
        if (editMode) {
            val hit = hitTestControl(x, y, true)
            if (hit != null) {
                selectedEditId = hit.id
                onSelectEditItem(hit.id)
                val (cx, cy) = centerOf(hit)
                activePointers[pid] = PointerRole.EditDrag(hit.id, x - cx, y - cy)
            }
            invalidate()
            return
        }
        val hit = hitTestControl(x, y, false)
        if (hit != null) {
            when (hit.id) {
                ControlId.JOYSTICK -> {
                    val (cx, cy) = centerOf(hit)
                    activePointers[pid] = PointerRole.Joystick(cx, cy)
                    updateJoystick(x - cx, y - cy, radiusOf(hit))
                }
                ControlId.TRACKPAD -> {
                    activePointers[pid] = PointerRole.Trackpad(x, y)
                }
                else -> {
                    activePointers[pid] = PointerRole.Button(hit.id)
                    onButtonDown(hit.id)
                }
            }
        }
        invalidate()
    }

    private fun handlePointerMove(pid: Int, x: Float, y: Float) {
        when (val role = activePointers[pid]) {
            is PointerRole.EditDrag -> {
                val st = config.items[role.id] ?: return
                val nx = ((x - role.offsetX) / width).coerceIn(0.04f, 0.96f)
                val ny = ((y - role.offsetY) / height).coerceIn(0.06f, 0.94f)
                st.xFrac = nx
                st.yFrac = ny
                invalidate()
            }
            is PointerRole.Joystick -> {
                val st = config.items[ControlId.JOYSTICK] ?: return
                val (cx, cy) = centerOf(st)
                updateJoystick(x - cx, y - cy, radiusOf(st))
                invalidate()
            }
            is PointerRole.Trackpad -> {
                val dx = (x - role.lastX) * config.touchSensX
                val dy = (y - role.lastY) * config.touchSensY
                role.lastX = x
                role.lastY = y
                VirtualHidController.addMouseDelta(dx, dy)
            }
            is PointerRole.Button, null -> Unit
        }
    }

    private fun handlePointerUp(pid: Int) {
        when (val role = activePointers.remove(pid)) {
            is PointerRole.EditDrag -> {
                config.save()
                onStateChanged()
            }
            is PointerRole.Joystick -> {
                joyDx = 0f
                joyDy = 0f
                joySprinting = false
                VirtualHidController.setKeys("joystick", 0, emptySet())
                invalidate()
            }
            is PointerRole.Button -> {
                onButtonUp(role.id)
                invalidate()
            }
            is PointerRole.Trackpad, null -> Unit
        }
    }

    private fun updateJoystick(rawDx: Float, rawDy: Float, radius: Float) {
        val maxDist = (radius * 0.62f).coerceAtLeast(10f)
        val dist = hypot(rawDx, rawDy)
        val clamped = min(dist, maxDist)
        val angle = atan2(rawDy, rawDx)
        joyDx = if (dist > 1f) (cos(angle) * clamped) / maxDist else 0f
        joyDy = if (dist > 1f) (sin(angle) * clamped) / maxDist else 0f

        val keys = HashSet<Int>()
        val dead = 0.22f
        if (joyDy < -dead) keys.add(0x1A) // W
        if (joyDy > dead) keys.add(0x16)  // S
        if (joyDx < -dead) keys.add(0x04) // A
        if (joyDx > dead) keys.add(0x07)  // D

        val sprint = config.autoSprint && joyDy < -0.78f && (dist / maxDist) > 0.85f
        joySprinting = sprint
        val mods = if (sprint) 0x02 else 0

        VirtualHidController.setKeys("joystick", mods, keys)
    }

    private fun onButtonDown(id: ControlId) {
        pressedButtons.add(id)
        if (id == ControlId.AIM && config.aimToggleMode) {
            aimToggled = !aimToggled
            VirtualHidController.setMouseButton("aim_toggle", id.code, aimToggled)
            updateAdsFlag()
            return
        }
        when (id.type) {
            ControlType.MOUSE_BUTTON -> VirtualHidController.setMouseButton(id.name, id.code, true)
            ControlType.KEY_HOLD -> {
                if (id == ControlId.SPRINT) {
                    VirtualHidController.setKeys(id.name, id.code, emptySet())
                } else {
                    VirtualHidController.setKeys(id.name, 0, setOf(id.code))
                }
            }
            ControlType.KEY_TAP -> VirtualHidController.tapKey(id.code)
            ControlType.JOYSTICK_WASD, ControlType.TRACKPAD -> Unit
        }
        updateAdsFlag()
    }

    private fun onButtonUp(id: ControlId) {
        pressedButtons.remove(id)
        if (id == ControlId.AIM && config.aimToggleMode) {
            updateAdsFlag()
            return
        }
        when (id.type) {
            ControlType.MOUSE_BUTTON -> VirtualHidController.setMouseButton(id.name, id.code, false)
            ControlType.KEY_HOLD -> VirtualHidController.setKeys(id.name, 0, emptySet())
            ControlType.KEY_TAP, ControlType.JOYSTICK_WASD, ControlType.TRACKPAD -> Unit
        }
        updateAdsFlag()
    }

    private fun updateAdsFlag() {
        isAdsOrFiringActive = aimToggled ||
            pressedButtons.contains(ControlId.FIRE) ||
            pressedButtons.contains(ControlId.FIRE_LEFT) ||
            pressedButtons.contains(ControlId.AIM)
    }
}
