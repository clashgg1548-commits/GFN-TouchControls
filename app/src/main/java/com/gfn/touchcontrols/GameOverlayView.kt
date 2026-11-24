package com.gfn.touchcontrols

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
        data object AimPad : PointerRole()
        data class Joystick(val startX: Float, val startY: Float) : PointerRole()
        data class Button(val id: ControlId) : PointerRole()
        data class EditDrag(val id: ControlId, val offsetX: Float, val offsetY: Float) : PointerRole()
    }

    private val activePointers = HashMap<Int, PointerRole>()
    private val lastAimPos = HashMap<Int, Pair<Float, Float>>()

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

            if (id == ControlId.JOYSTICK) {
                drawJoystick(canvas, cx, cy, r, itemAlpha, st)
            } else {
                drawActionButton(canvas, cx, cy, r, itemAlpha, st)
            }

            if (editMode && id == selectedEditId) {
                strokePaint.color = Color.parseColor("#00E5FF")
                strokePaint.strokeWidth = dp(2.5f)
                strokePaint.alpha = 240
                canvas.drawCircle(cx, cy, r + dp(4f), strokePaint)
            }
        }
    }

    private fun drawJoystick(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        alpha: Float,
        st: ControlItemState
    ) {
        val a255 = (alpha * 255).toInt().coerceIn(20, 255)
        fillPaint.color = Color.argb((a255 * 0.45f).toInt(), 18, 22, 30)
        canvas.drawCircle(cx, cy, r, fillPaint)

        strokePaint.color = if (joySprinting) Color.parseColor("#FFB300") else Color.WHITE
        strokePaint.alpha = a255
        strokePaint.strokeWidth = dp(2f)
        canvas.drawCircle(cx, cy, r, strokePaint)

        // Guías direccionales discretas
        textPaint.color = Color.WHITE
        textPaint.alpha = (a255 * 0.65f).toInt()
        textPaint.textSize = r * 0.22f
        canvas.drawText("W", cx, cy - r * 0.68f + textPaint.textSize * 0.35f, textPaint)
        canvas.drawText("S", cx, cy + r * 0.72f + textPaint.textSize * 0.35f, textPaint)
        canvas.drawText("A", cx - r * 0.72f, cy + textPaint.textSize * 0.35f, textPaint)
        canvas.drawText("D", cx + r * 0.72f, cy + textPaint.textSize * 0.35f, textPaint)

        // Knob interno
        val maxDist = r * 0.62f
        val kx = cx + joyDx * maxDist
        val ky = cy + joyDy * maxDist
        val kr = r * 0.36f
        fillPaint.color = if (joySprinting) Color.parseColor("#FFB300") else Color.WHITE
        fillPaint.alpha = (a255 * 0.85f).toInt()
        canvas.drawCircle(kx, ky, kr, fillPaint)

        if (editMode && !st.visible) {
            badgePaint.color = Color.RED
            badgePaint.textSize = dp(10f)
            canvas.drawText("OCULTO", cx, cy, badgePaint)
        }
    }

    private fun drawActionButton(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        alpha: Float,
        st: ControlItemState
    ) {
        val isPressed = pressedButtons.contains(st.id) || (st.id == ControlId.AIM && aimToggled)
        val a255 = (alpha * 255).toInt().coerceIn(20, 255)

        val isCombatPrimary = st.id == ControlId.FIRE || st.id == ControlId.FIRE_LEFT || st.id == ControlId.AIM
        fillPaint.color = when {
            isPressed -> Color.parseColor("#00E5FF")
            isCombatPrimary -> Color.rgb(30, 36, 48)
            else -> Color.rgb(18, 22, 30)
        }
        fillPaint.alpha = if (isPressed) (a255 * 0.9f).toInt().coerceAtLeast(150) else (a255 * 0.65f).toInt()
        canvas.drawCircle(cx, cy, r, fillPaint)

        strokePaint.color = when {
            isPressed -> Color.WHITE
            st.id == ControlId.FIRE || st.id == ControlId.FIRE_LEFT -> Color.parseColor("#FF6E40")
            st.id == ControlId.AIM -> Color.parseColor("#40C4FF")
            else -> Color.WHITE
        }
        strokePaint.alpha = a255
        strokePaint.strokeWidth = dp(1.8f)
        canvas.drawCircle(cx, cy, r, strokePaint)

        iconPaint.color = if (isPressed) Color.BLACK else Color.WHITE
        iconPaint.alpha = a255
        iconPaint.strokeWidth = dp(2f)
        iconFillPaint.color = iconPaint.color
        iconFillPaint.alpha = a255

        drawVectorIcon(canvas, st, cx, cy, r * 0.48f)

        // Etiqueta pequeña abajo (tecla o número)
        badgePaint.color = if (isPressed) Color.BLACK else Color.WHITE
        badgePaint.alpha = (a255 * 0.85f).toInt()
        badgePaint.textSize = (r * 0.28f).coerceAtLeast(dp(8.5f))
        if (st.id.icon != IconStyle.SLOT) {
            canvas.drawText(st.id.badge, cx, cy + r * 0.72f, badgePaint)
        }
    }

    private fun drawVectorIcon(canvas: Canvas, st: ControlItemState, cx: Float, cy: Float, s: Float) {
        val oy = cy - s * 0.14f
        when (st.id.icon) {
            IconStyle.FIRE -> {
                // Bala estilizada
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
                // Retícula de mira
                canvas.drawCircle(cx, oy, s * 0.62f, iconPaint)
                canvas.drawLine(cx - s * 0.88f, oy, cx - s * 0.32f, oy, iconPaint)
                canvas.drawLine(cx + s * 0.32f, oy, cx + s * 0.88f, oy, iconPaint)
                canvas.drawLine(cx, oy - s * 0.88f, cx, oy - s * 0.32f, iconPaint)
                canvas.drawLine(cx, oy + s * 0.32f, cx, oy + s * 0.88f, iconPaint)
                canvas.drawCircle(cx, oy, dp(2f), iconFillPaint)
            }
            IconStyle.JUMP -> {
                // Flecha salto hacia arriba
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
                // Silueta agachado
                canvas.drawCircle(cx + s * 0.12f, oy - s * 0.45f, s * 0.22f, iconPaint)
                tempPath.reset()
                tempPath.moveTo(cx + s * 0.08f, oy - s * 0.20f)
                tempPath.lineTo(cx - s * 0.22f, oy + s * 0.20f)
                tempPath.lineTo(cx + s * 0.25f, oy + s * 0.32f)
                tempPath.lineTo(cx + s * 0.20f, oy + s * 0.65f)
                canvas.drawPath(tempPath, iconPaint)
            }
            IconStyle.PRONE -> {
                // Silueta acostado
                canvas.drawCircle(cx + s * 0.52f, oy + s * 0.15f, s * 0.20f, iconPaint)
                canvas.drawLine(cx - s * 0.68f, oy + s * 0.35f, cx + s * 0.28f, oy + s * 0.28f, iconPaint)
                canvas.drawLine(cx - s * 0.68f, oy + s * 0.55f, cx + s * 0.68f, oy + s * 0.55f, iconPaint)
            }
            IconStyle.RELOAD -> {
                // Flecha circular recarga
                tempRect.set(cx - s * 0.55f, oy - s * 0.55f, cx + s * 0.55f, oy + s * 0.55f)
                canvas.drawArc(tempRect, -40f, 280f, false, iconPaint)
                canvas.drawLine(cx + s * 0.32f, oy - s * 0.55f, cx + s * 0.60f, oy - s * 0.28f, iconPaint)
                canvas.drawLine(cx + s * 0.60f, oy - s * 0.28f, cx + s * 0.25f, oy - s * 0.15f, iconPaint)
            }
            IconStyle.GRENADE -> {
                // Granada clásica
                tempRect.set(cx - s * 0.42f, oy - s * 0.28f, cx + s * 0.42f, oy + s * 0.58f)
                canvas.drawOval(tempRect, iconPaint)
                canvas.drawLine(cx - s * 0.40f, oy + s * 0.15f, cx + s * 0.40f, oy + s * 0.15f, iconPaint)
                canvas.drawLine(cx - s * 0.22f, oy - s * 0.28f, cx - s * 0.22f, oy - s * 0.55f, iconPaint)
                canvas.drawLine(cx + s * 0.22f, oy - s * 0.28f, cx + s * 0.22f, oy - s * 0.55f, iconPaint)
                canvas.drawLine(cx - s * 0.22f, oy - s * 0.55f, cx + s * 0.38f, oy - s * 0.55f, iconPaint)
                canvas.drawCircle(cx - s * 0.42f, oy - s * 0.42f, s * 0.16f, iconPaint)
            }
            IconStyle.SPRINT -> {
                // Doble chevrón correr
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
                // Cuchillo diagonal
                canvas.drawLine(cx - s * 0.48f, oy + s * 0.48f, cx + s * 0.52f, oy - s * 0.52f, iconPaint)
                canvas.drawLine(cx - s * 0.38f, oy + s * 0.08f, cx - s * 0.08f, oy + s * 0.38f, iconPaint)
            }
            IconStyle.INTERACT -> {
                // Caja / Interactuar
                tempRect.set(cx - s * 0.48f, oy - s * 0.45f, cx + s * 0.48f, oy + s * 0.45f)
                canvas.drawRoundRect(tempRect, dp(4f), dp(4f), iconPaint)
                canvas.drawCircle(cx, oy, s * 0.16f, iconFillPaint)
            }
            IconStyle.PING -> {
                // Pin / Spot enemigo
                tempPath.reset()
                tempPath.moveTo(cx, oy + s * 0.62f)
                tempPath.lineTo(cx - s * 0.44f, oy - s * 0.05f)
                tempPath.quadTo(cx, oy - s * 0.72f, cx + s * 0.44f, oy - s * 0.05f)
                tempPath.close()
                canvas.drawPath(tempPath, iconPaint)
                canvas.drawCircle(cx, oy - s * 0.08f, s * 0.14f, iconFillPaint)
            }
            IconStyle.SLOT -> {
                textPaint.color = iconPaint.color
                textPaint.alpha = iconPaint.alpha
                textPaint.textSize = s * 1.15f
                canvas.drawText(st.id.badge, cx, cy + textPaint.textSize * 0.35f, textPaint)
            }
            IconStyle.MAP, IconStyle.TAB, IconStyle.ESC -> {
                textPaint.color = iconPaint.color
                textPaint.alpha = iconPaint.alpha
                textPaint.textSize = s * 0.72f
                canvas.drawText(st.id.badge, cx, oy + textPaint.textSize * 0.4f, textPaint)
            }
            IconStyle.JOYSTICK -> {}
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
            if (hypot(x - cx, y - cy) <= r * 1.12f) {
                return st
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
            if (hit.id == ControlId.JOYSTICK) {
                val (cx, cy) = centerOf(hit)
                activePointers[pid] = PointerRole.Joystick(cx, cy)
                updateJoystick(x - cx, y - cy, radiusOf(hit))
            } else {
                activePointers[pid] = PointerRole.Button(hit.id)
                onButtonDown(hit.id)
            }
        } else {
            activePointers[pid] = PointerRole.AimPad
            lastAimPos[pid] = x to y
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
            is PointerRole.AimPad -> {
                val prev = lastAimPos[pid]
                if (prev != null) {
                    val dx = (x - prev.first) * config.touchSensX
                    val dy = (y - prev.second) * config.touchSensY
                    VirtualHidController.addMouseDelta(dx, dy)
                }
                lastAimPos[pid] = x to y
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
            is PointerRole.AimPad -> {
                lastAimPos.remove(pid)
            }
            null -> Unit
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
            ControlType.JOYSTICK_WASD -> Unit
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
            ControlType.KEY_TAP, ControlType.JOYSTICK_WASD -> Unit
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
