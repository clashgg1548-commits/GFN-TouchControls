package com.gfn.touchcontrols

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method
import java.util.concurrent.Executors
import kotlin.math.roundToInt

object VirtualHidController {

    private const val TAG = "VirtualHidController"
    private val executor = Executors.newSingleThreadExecutor()

    private var inputManager: Any? = null
    private var injectMethod: Method? = null

    var isConnected = false
        private set

    var statusListener: ((String) -> Unit)? = null
    var lastStatus: String = "Sin conectar"
        private set

    private val heldBySource = LinkedHashMap<String, Pair<Int, Set<Int>>>()
    private val mouseSources = LinkedHashMap<String, Int>()
    private var currentMouseButtons = 0
    private var lastKeys = ByteArray(6)
    private var lastModifiers = 0
    private var lastMouseButtons = 0

    private var mouseX = 960f
    private var mouseY = 540f
    private var accDx = 0f
    private var accDy = 0f
    private val lock = Any()

    fun bind(context: Context) {
        connect(context)
    }

    fun unbind(context: Context) {
        releaseAll()
    }

    fun connect(context: Context): Boolean {
        try {
            if (!Shizuku.pingBinder()) {
                updateStatus("Shizuku no está activo (abrí Shizuku)")
                isConnected = false
                return false
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                updateStatus("Falta autorizar Shizuku")
                isConnected = false
                return false
            }

            val rawBinder = SystemServiceHelper.getSystemService("input")
            if (rawBinder == null) {
                updateStatus("Error: Servicio input no disponible")
                isConnected = false
                return false
            }

            val wrapper = ShizukuBinderWrapper(rawBinder)
            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            val im = asInterface.invoke(null, wrapper)

            val method = im.javaClass.methods.firstOrNull {
                it.name == "injectInputEvent" && it.parameterTypes.isNotEmpty() &&
                    InputEvent::class.java.isAssignableFrom(it.parameterTypes[0])
            }

            if (method == null) {
                updateStatus("Error: injectInputEvent no encontrado")
                isConnected = false
                return false
            }

            inputManager = im
            injectMethod = method
            isConnected = true
            updateStatus("CONECTADO (Directo) ✅")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Error connect", e)
            updateStatus("Error: ${e.message}")
            isConnected = false
            return false
        }
    }

    private fun injectEvent(event: InputEvent) {
        val m = injectMethod ?: return
        val im = inputManager ?: return
        try {
            when (m.parameterTypes.size) {
                2 -> m.invoke(im, event, 0)
                3 -> m.invoke(im, event, 0, -1)
                else -> m.invoke(im, event, 0)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "injectEvent falló: ${e.message}")
        }
    }

    fun setKeys(sourceId: String, modifiers: Int, hidKeys: Set<Int>) {
        val reportMods: Int
        val reportKeys: ByteArray
        synchronized(lock) {
            if (modifiers == 0 && hidKeys.isEmpty()) {
                heldBySource.remove(sourceId)
            } else {
                heldBySource[sourceId] = modifiers to hidKeys
            }
            var mods = 0
            val merged = LinkedHashSet<Int>()
            for ((m, ks) in heldBySource.values) {
                mods = mods or m
                merged.addAll(ks)
            }
            reportMods = mods
            reportKeys = ByteArray(6)
            var idx = 0
            for (k in merged) {
                if (idx >= 6) break
                reportKeys[idx++] = k.toByte()
            }
        }
        executor.execute {
            injectKeyboard(reportMods, reportKeys)
        }
    }

    fun tapKey(hidKey: Int, modifiers: Int = 0) {
        val id = "tap_$hidKey"
        setKeys(id, modifiers, setOf(hidKey))
        executor.execute {
            try {
                Thread.sleep(35)
            } catch (_: InterruptedException) {
            }
            setKeys(id, 0, emptySet())
        }
    }

    fun setMouseButton(sourceId: String, buttonMask: Int, pressed: Boolean) {
        val btnState: Int
        synchronized(lock) {
            if (pressed) {
                mouseSources[sourceId] = buttonMask
            } else {
                mouseSources.remove(sourceId)
            }
            var mask = 0
            for (b in mouseSources.values) {
                mask = mask or b
            }
            currentMouseButtons = mask
            btnState = mask
        }
        executor.execute {
            injectMouse(btnState, 0, 0)
        }
    }

    fun addMouseDelta(dxFloat: Float, dyFloat: Float) {
        val sendX: Int
        val sendY: Int
        val btnState: Int
        synchronized(lock) {
            accDx += dxFloat
            accDy += dyFloat
            sendX = accDx.roundToInt()
            sendY = accDy.roundToInt()
            if (sendX == 0 && sendY == 0) return
            accDx -= sendX
            accDy -= sendY
            btnState = currentMouseButtons
        }
        executor.execute {
            injectMouse(btnState, sendX, sendY)
        }
    }

    fun releaseAll() {
        synchronized(lock) {
            heldBySource.clear()
            mouseSources.clear()
            currentMouseButtons = 0
            accDx = 0f
            accDy = 0f
        }
        executor.execute {
            injectKeyboard(0, ByteArray(6))
            injectMouse(0, 0, 0)
        }
    }

    private fun injectKeyboard(modifiers: Int, keys: ByteArray) {
        val now = SystemClock.uptimeMillis()
        val oldSet = lastKeys.filter { it.toInt() != 0 }.map { it.toInt() and 0xFF }.toSet()
        val newSet = keys.filter { it.toInt() != 0 }.map { it.toInt() and 0xFF }.toSet()

        if ((lastModifiers and 0x02) != (modifiers and 0x02)) {
            val action = if ((modifiers and 0x02) != 0) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
            injectKey(KeyEvent(now, now, action, KeyEvent.KEYCODE_SHIFT_LEFT, 0))
        }
        if ((lastModifiers and 0x01) != (modifiers and 0x01)) {
            val action = if ((modifiers and 0x01) != 0) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
            injectKey(KeyEvent(now, now, action, KeyEvent.KEYCODE_CTRL_LEFT, 0))
        }
        for (hid in oldSet - newSet) {
            val kc = hidToAndroidKeyCode(hid)
            if (kc != KeyEvent.KEYCODE_UNKNOWN) {
                injectKey(KeyEvent(now, now, KeyEvent.ACTION_UP, kc, 0))
            }
        }
        for (hid in newSet - oldSet) {
            val kc = hidToAndroidKeyCode(hid)
            if (kc != KeyEvent.KEYCODE_UNKNOWN) {
                injectKey(KeyEvent(now, now, KeyEvent.ACTION_DOWN, kc, 0))
            }
        }
        lastModifiers = modifiers
        lastKeys = keys.copyOf(6)
    }

    private fun injectKey(ke: KeyEvent) {
        try {
            ke.source = InputDevice.SOURCE_KEYBOARD
        } catch (_: Throwable) {
        }
        injectEvent(ke)
    }

    var screenWidth = 2400f
    var screenHeight = 1080f

    private fun injectMouse(buttons: Int, dx: Int, dy: Int) {
        val now = SystemClock.uptimeMillis()
        val cx = screenWidth / 2f
        val cy = screenHeight / 2f

        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })

        val changed = buttons xor lastMouseButtons
        if (changed != 0) {
            val isDown = (buttons and changed) != 0
            // El clic se hace siempre en el CENTRO de la pantalla para impactar en el juego
            val clickCoords = arrayOf(MotionEvent.PointerCoords().apply {
                x = cx
                y = cy
                setAxisValue(MotionEvent.AXIS_X, cx)
                setAxisValue(MotionEvent.AXIS_Y, cy)
            })
            val action = if (isDown) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP
            val ev = MotionEvent.obtain(
                now, now, action, 1, props, clickCoords, 0, buttons,
                1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
            )
            injectEvent(ev)
            ev.recycle()
            lastMouseButtons = buttons
        }

        if (dx != 0 || dy != 0) {
            // Se envía el movimiento relativo desde el centro hacia (cx + dx, cy + dy)
            // de modo que el cursor nunca toque los bordes de la pantalla
            val moveCoords = arrayOf(MotionEvent.PointerCoords().apply {
                x = cx + dx.toFloat()
                y = cy + dy.toFloat()
                setAxisValue(MotionEvent.AXIS_X, cx + dx.toFloat())
                setAxisValue(MotionEvent.AXIS_Y, cy + dy.toFloat())
                setAxisValue(MotionEvent.AXIS_RELATIVE_X, dx.toFloat())
                setAxisValue(MotionEvent.AXIS_RELATIVE_Y, dy.toFloat())
            })
            val ev = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_MOVE, 1, props, moveCoords, 0, buttons,
                1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
            )
            injectEvent(ev)
            ev.recycle()
        }
    }
    private fun hidToAndroidKeyCode(hid: Int): Int = when (hid) {
        0x04 -> KeyEvent.KEYCODE_A
        0x06 -> KeyEvent.KEYCODE_C
        0x07 -> KeyEvent.KEYCODE_D
        0x08 -> KeyEvent.KEYCODE_E
        0x09 -> KeyEvent.KEYCODE_F
        0x0A -> KeyEvent.KEYCODE_G
        0x10 -> KeyEvent.KEYCODE_M
        0x14 -> KeyEvent.KEYCODE_Q
        0x15 -> KeyEvent.KEYCODE_R
        0x16 -> KeyEvent.KEYCODE_S
        0x1A -> KeyEvent.KEYCODE_W
        0x1D -> KeyEvent.KEYCODE_Z
        0x1E -> KeyEvent.KEYCODE_1
        0x1F -> KeyEvent.KEYCODE_2
        0x20 -> KeyEvent.KEYCODE_3
        0x21 -> KeyEvent.KEYCODE_4
        0x29 -> KeyEvent.KEYCODE_ESCAPE
        0x2B -> KeyEvent.KEYCODE_TAB
        0x2C -> KeyEvent.KEYCODE_SPACE
        else -> KeyEvent.KEYCODE_UNKNOWN
    }

    private fun updateStatus(msg: String) {
        lastStatus = msg
        statusListener?.invoke(msg)
    }
}
