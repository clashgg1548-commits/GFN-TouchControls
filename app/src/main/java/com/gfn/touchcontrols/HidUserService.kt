package com.gfn.touchcontrols

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.exitProcess

import androidx.annotation.Keep

@Keep
class HidUserService() : IHidService.Stub() {
    constructor(context: android.content.Context) : this()

    companion object {
        private const val TAG = "GFN_HidService"
        private const val UHID_CREATE2 = 11
        private const val UHID_DESTROY = 1
        private const val UHID_INPUT2 = 12
        private const val UHID_EVENT_SIZE = 4380
        private const val BUS_USB: Short = 0x03

        private val HID_DESCRIPTOR = byteArrayOf(
            // Keyboard (Report ID 1)
            0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01,
            0x85.toByte(), 0x01, 0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(),
            0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x08,
            0x81.toByte(), 0x02, 0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x01,
            0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x26, 0xFF.toByte(), 0x00,
            0x05, 0x07, 0x19, 0x00, 0x29, 0xFF.toByte(), 0x81.toByte(), 0x00, 0xC0.toByte(),

            // Mouse (Report ID 2)
            0x05, 0x01, 0x09, 0x02, 0xA1.toByte(), 0x01,
            0x85.toByte(), 0x02, 0x09, 0x01, 0xA1.toByte(), 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x05, 0x15, 0x00, 0x25, 0x01,
            0x95.toByte(), 0x05, 0x75, 0x01, 0x81.toByte(), 0x02,
            0x95.toByte(), 0x01, 0x75, 0x03, 0x81.toByte(), 0x01,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
            0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x03,
            0x81.toByte(), 0x06, 0xC0.toByte(), 0xC0.toByte()
        )
    }

    private var uhidStream: FileOutputStream? = null
    private val eventBuf = ByteBuffer.allocate(UHID_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
    private var useInputFallback = false
    private var inputManagerObj: Any? = null
    private var injectMethod: Method? = null
    private var lastKeys = ByteArray(6)
    private var lastModifiers = 0
    private var lastMouseButtons = 0

    private var mouseX = 960f
    private var mouseY = 540f

    @Synchronized
    override fun openHid(): String {
        if (uhidStream != null) return "UHID_OK"
        if (useInputFallback) return "FALLBACK_OK"

        // 1. Probar /dev/uhid nativo
        try {
            val fos = FileOutputStream("/dev/uhid")
            eventBuf.clear()
            eventBuf.putInt(UHID_CREATE2)
            val nameBytes = "GFN Virtual Keyboard Mouse".toByteArray(Charsets.UTF_8)
            eventBuf.position(4)
            eventBuf.put(nameBytes, 0, minOf(nameBytes.size, 127))
            eventBuf.position(260)
            eventBuf.putShort(HID_DESCRIPTOR.size.toShort())
            eventBuf.putShort(BUS_USB)
            eventBuf.putInt(0x18D1)
            eventBuf.putInt(0x4EE1)
            eventBuf.putInt(1)
            eventBuf.putInt(0)
            eventBuf.position(280)
            eventBuf.put(HID_DESCRIPTOR)
            fos.write(eventBuf.array(), 0, UHID_EVENT_SIZE)
            fos.flush()
            uhidStream = fos
            useInputFallback = false
            return "UHID_OK"
        } catch (_: Throwable) {
            // SELinux suele bloquear /dev/uhid a apps sin root en Android 16
        }

        // 2. Conectar directamente con IInputManager del sistema
        val fallbackRes = initInputFallback()
        return if (useInputFallback) "FALLBACK_OK" else "ERROR: $fallbackRes"
    }

    @Synchronized
    override fun closeHid() {
        val fos = uhidStream ?: return
        try {
            eventBuf.clear()
            eventBuf.putInt(UHID_DESTROY)
            fos.write(eventBuf.array(), 0, 4)
            fos.flush()
        } catch (_: Throwable) {
        }
        try {
            fos.close()
        } catch (_: Throwable) {
        }
        uhidStream = null
    }

    @Synchronized
    override fun sendKeyboard(modifiers: Int, keys: ByteArray) {
        val fos = uhidStream
        if (fos != null) {
            try {
                eventBuf.clear()
                eventBuf.putInt(UHID_INPUT2)
                eventBuf.putShort(9.toShort())
                eventBuf.put(0x01)
                eventBuf.put(modifiers.toByte())
                eventBuf.put(0x00)
                for (i in 0 until 6) {
                    eventBuf.put(if (i < keys.size) keys[i] else 0x00)
                }
                fos.write(eventBuf.array(), 0, 15)
                fos.flush()
                return
            } catch (e: Throwable) {
                Log.e(TAG, "UHID keyboard write failed", e)
            }
        }
        if (useInputFallback) {
            injectKeyboardFallback(modifiers, keys)
        }
    }

    @Synchronized
    override fun sendMouse(buttons: Int, dx: Int, dy: Int, wheel: Int) {
        val fos = uhidStream
        if (fos != null) {
            var remX = dx
            var remY = dy
            var remW = wheel
            do {
                val stepX = remX.coerceIn(-127, 127)
                val stepY = remY.coerceIn(-127, 127)
                val stepW = remW.coerceIn(-127, 127)
                try {
                    eventBuf.clear()
                    eventBuf.putInt(UHID_INPUT2)
                    eventBuf.putShort(5.toShort())
                    eventBuf.put(0x02)
                    eventBuf.put(buttons.toByte())
                    eventBuf.put(stepX.toByte())
                    eventBuf.put(stepY.toByte())
                    eventBuf.put(stepW.toByte())
                    fos.write(eventBuf.array(), 0, 11)
                    fos.flush()
                } catch (e: Throwable) {
                    Log.e(TAG, "UHID mouse write failed", e)
                    break
                }
                remX -= stepX
                remY -= stepY
                remW -= stepW
            } while (remX != 0 || remY != 0 || remW != 0)
            return
        }
        if (useInputFallback) {
            injectMouseFallback(buttons, dx, dy)
        }
    }

    override fun destroy() {
        closeHid()
        exitProcess(0)
    }

    @SuppressLint("PrivateApi")
    private fun initInputFallback(): String {
        if (injectMethod != null && inputManagerObj != null) {
            useInputFallback = true
            return "FALLBACK_OK"
        }
        try {
            // Android 14/15/16: Obtener IInputManager vía ServiceManager
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "input") as? IBinder
                ?: return "ServiceManager no devolvió el servicio 'input'"

            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            val im = asInterface.invoke(null, binder) ?: return "IInputManager es nulo"
            inputManagerObj = im

            // Buscar método injectInputEvent compatible
            val method = im.javaClass.methods.firstOrNull {
                it.name == "injectInputEvent" && it.parameterTypes.isNotEmpty() &&
                    InputEvent::class.java.isAssignableFrom(it.parameterTypes[0])
            } ?: return "No se encontró injectInputEvent en IInputManager"

            injectMethod = method
            useInputFallback = true
            return "FALLBACK_OK"
        } catch (e: Throwable) {
            Log.e(TAG, "initInputFallback error: ${e.message}", e)
            return "Error fallback: ${e.message}"
        }
    }

    private fun injectEvent(event: InputEvent) {
        val m = injectMethod ?: return
        val obj = inputManagerObj ?: return
        try {
            when (m.parameterTypes.size) {
                2 -> m.invoke(obj, event, 0)
                3 -> m.invoke(obj, event, 0, -1)
                else -> m.invoke(obj, event, 0)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "injectEvent falló: ${e.message}")
        }
    }

    private fun injectKeyboardFallback(modifiers: Int, keys: ByteArray) {
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

    private fun injectMouseFallback(buttons: Int, dx: Int, dy: Int) {
        val now = SystemClock.uptimeMillis()

        mouseX = (mouseX + dx).coerceIn(100f, 2200f)
        mouseY = (mouseY + dy).coerceIn(100f, 1000f)

        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = mouseX
            y = mouseY
            setAxisValue(MotionEvent.AXIS_RELATIVE_X, dx.toFloat())
            setAxisValue(MotionEvent.AXIS_RELATIVE_Y, dy.toFloat())
        })

        val changed = buttons xor lastMouseButtons
        if (changed != 0) {
            val isDown = (buttons and changed) != 0
            val action = if (isDown) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP
            val ev = MotionEvent.obtain(
                now, now, action, 1, props, coords, 0, buttons,
                1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
            )
            injectEvent(ev)
            ev.recycle()

            val btnAction = if (isDown) MotionEvent.ACTION_BUTTON_PRESS else MotionEvent.ACTION_BUTTON_RELEASE
            val evBtn = MotionEvent.obtain(
                now, now, btnAction, 1, props, coords, 0, buttons,
                1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
            )
            injectEvent(evBtn)
            evBtn.recycle()

            lastMouseButtons = buttons
        }
        if (dx != 0 || dy != 0) {
            val ev = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_MOVE, 1, props, coords, 0, buttons,
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
}
