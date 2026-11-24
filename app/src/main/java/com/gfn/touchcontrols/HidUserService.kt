package com.gfn.touchcontrols

import android.annotation.SuppressLint
import android.hardware.input.InputManager
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

class HidUserService : IHidService.Stub() {

    companion object {
        private const val TAG = "GFN_HidService"
        private const val UHID_CREATE2 = 11
        private const val UHID_DESTROY = 1
        private const val UHID_INPUT2 = 12
        private const val UHID_EVENT_SIZE = 4380
        private const val BUS_USB: Short = 0x03

        // Report ID 1: Keyboard (1 modifier byte + 1 reserved + 6 keycodes = 8 bytes)
        // Report ID 2: Mouse (1 buttons byte + dx(int8) + dy(int8) + wheel(int8) = 4 bytes)
        private val HID_DESCRIPTOR = byteArrayOf(
            // --- Keyboard (Report ID 1) ---
            0x05, 0x01,       // Usage Page (Generic Desktop)
            0x09, 0x06,       // Usage (Keyboard)
            0xA1.toByte(), 0x01, // Collection (Application)
            0x85.toByte(), 0x01, //   Report ID (1)
            0x05, 0x07,       //   Usage Page (Keyboard/Keypad)
            0x19, 0xE0.toByte(), //   Usage Minimum (224)
            0x29, 0xE7.toByte(), //   Usage Maximum (231)
            0x15, 0x00,       //   Logical Minimum (0)
            0x25, 0x01,       //   Logical Maximum (1)
            0x75, 0x01,       //   Report Size (1)
            0x95.toByte(), 0x08, //   Report Count (8)
            0x81.toByte(), 0x02, //   Input (Data, Variable, Absolute)
            0x95.toByte(), 0x01, //   Report Count (1)
            0x75, 0x08,       //   Report Size (8)
            0x81.toByte(), 0x01, //   Input (Constant)
            0x95.toByte(), 0x06, //   Report Count (6)
            0x75, 0x08,       //   Report Size (8)
            0x15, 0x00,       //   Logical Minimum (0)
            0x26, 0xFF.toByte(), 0x00, // Logical Maximum (255)
            0x05, 0x07,       //   Usage Page (Keyboard/Keypad)
            0x19, 0x00,       //   Usage Minimum (0)
            0x29, 0xFF.toByte(), //   Usage Maximum (255)
            0x81.toByte(), 0x00, //   Input (Data, Array)
            0xC0.toByte(),    // End Collection

            // --- Mouse (Report ID 2) ---
            0x05, 0x01,       // Usage Page (Generic Desktop)
            0x09, 0x02,       // Usage (Mouse)
            0xA1.toByte(), 0x01, // Collection (Application)
            0x85.toByte(), 0x02, //   Report ID (2)
            0x09, 0x01,       //   Usage (Pointer)
            0xA1.toByte(), 0x00, //   Collection (Physical)
            0x05, 0x09,       //     Usage Page (Buttons)
            0x19, 0x01,       //     Usage Minimum (Button 1)
            0x29, 0x05,       //     Usage Maximum (Button 5)
            0x15, 0x00,       //     Logical Minimum (0)
            0x25, 0x01,       //     Logical Maximum (1)
            0x95.toByte(), 0x05, //     Report Count (5)
            0x75, 0x01,       //     Report Size (1)
            0x81.toByte(), 0x02, //     Input (Data, Variable, Absolute)
            0x95.toByte(), 0x01, //     Report Count (1)
            0x75, 0x03,       //     Report Size (3)
            0x81.toByte(), 0x01, //     Input (Constant)
            0x05, 0x01,       //     Usage Page (Generic Desktop)
            0x09, 0x30,       //     Usage (X)
            0x09, 0x31,       //     Usage (Y)
            0x09, 0x38,       //     Usage (Wheel)
            0x15, 0x81.toByte(), //     Logical Minimum (-127)
            0x25, 0x7F,       //     Logical Maximum (127)
            0x75, 0x08,       //     Report Size (8)
            0x95.toByte(), 0x03, //     Report Count (3)
            0x81.toByte(), 0x06, //     Input (Data, Variable, Relative)
            0xC0.toByte(),    //   End Collection
            0xC0.toByte()     // End Collection
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

    @Synchronized
    override fun openHid(): String {
        if (uhidStream != null) return "UHID_OK"
        return try {
            val fos = FileOutputStream("/dev/uhid")
            eventBuf.clear()
            // type = UHID_CREATE2 (offset 0, 4 bytes)
            eventBuf.putInt(UHID_CREATE2)
            // name[128] (offset 4)
            val nameBytes = "GFN Virtual Keyboard Mouse".toByteArray(Charsets.UTF_8)
            eventBuf.position(4)
            eventBuf.put(nameBytes, 0, minOf(nameBytes.size, 127))
            // phys[64] (offset 132), uniq[64] (offset 196) -> zeroes
            // rd_size (offset 260, uint16)
            eventBuf.position(260)
            eventBuf.putShort(HID_DESCRIPTOR.size.toShort())
            // bus (offset 262, uint16)
            eventBuf.putShort(BUS_USB)
            // vendor (offset 264, uint32)
            eventBuf.putInt(0x18D1)
            // product (offset 268, uint32)
            eventBuf.putInt(0x4EE1)
            // version (offset 272, uint32)
            eventBuf.putInt(1)
            // country (offset 276, uint32)
            eventBuf.putInt(0)
            // rd_data[4096] (offset 280)
            eventBuf.put(HID_DESCRIPTOR)
            fos.write(eventBuf.array(), 0, UHID_EVENT_SIZE)
            fos.flush()
            uhidStream = fos
            useInputFallback = false
            "UHID_OK"
        } catch (e: Throwable) {
            Log.e(TAG, "UHID failed: ${e.message}", e)
            initInputFallback()
            if (injectMethod != null) {
                useInputFallback = true
                "FALLBACK_OK (${e.message})"
            } else {
                "ERROR: ${e.message}"
            }
        }
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
                // Report ID 1 + modifiers + reserved + 6 keycodes = 9 bytes
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
                    // Report ID 2 + buttons + dx + dy + wheel = 5 bytes
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
    private fun initInputFallback() {
        if (injectMethod != null) return
        try {
            val imClass = InputManager::class.java
            val getInstance = imClass.getMethod("getInstance")
            inputManagerObj = getInstance.invoke(null)
            injectMethod = imClass.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
        } catch (e: Throwable) {
            Log.e(TAG, "InputManager fallback init failed", e)
        }
    }

    private fun injectEvent(event: InputEvent) {
        try {
            injectMethod?.invoke(inputManagerObj, event, 0)
        } catch (_: Throwable) {
        }
    }

    private fun injectKeyboardFallback(modifiers: Int, keys: ByteArray) {
        val now = SystemClock.uptimeMillis()
        val oldSet = lastKeys.filter { it.toInt() != 0 }.map { it.toInt() and 0xFF }.toSet()
        val newSet = keys.filter { it.toInt() != 0 }.map { it.toInt() and 0xFF }.toSet()

        if ((lastModifiers and 0x02) != (modifiers and 0x02)) {
            val action = if ((modifiers and 0x02) != 0) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
            injectEvent(KeyEvent(now, now, action, KeyEvent.KEYCODE_SHIFT_LEFT, 0))
        }
        if ((lastModifiers and 0x01) != (modifiers and 0x01)) {
            val action = if ((modifiers and 0x01) != 0) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
            injectEvent(KeyEvent(now, now, action, KeyEvent.KEYCODE_CTRL_LEFT, 0))
        }
        for (hid in oldSet - newSet) {
            val kc = hidToAndroidKeyCode(hid)
            if (kc != KeyEvent.KEYCODE_UNKNOWN) {
                injectEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, kc, 0))
            }
        }
        for (hid in newSet - oldSet) {
            val kc = hidToAndroidKeyCode(hid)
            if (kc != KeyEvent.KEYCODE_UNKNOWN) {
                injectEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, kc, 0))
            }
        }
        lastModifiers = modifiers
        lastKeys = keys.copyOf(6)
    }

    private fun injectMouseFallback(buttons: Int, dx: Int, dy: Int) {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = dx.toFloat()
            y = dy.toFloat()
            setAxisValue(MotionEvent.AXIS_RELATIVE_X, dx.toFloat())
            setAxisValue(MotionEvent.AXIS_RELATIVE_Y, dy.toFloat())
        })
        val changed = buttons xor lastMouseButtons
        if (changed != 0) {
            val action = if ((buttons and changed) != 0) MotionEvent.ACTION_BUTTON_PRESS else MotionEvent.ACTION_BUTTON_RELEASE
            val ev = MotionEvent.obtain(
                now, now, action, 1, props, coords, 0, buttons,
                1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
            )
            injectEvent(ev)
            ev.recycle()
            lastMouseButtons = buttons
        }
        if (dx != 0 || dy != 0) {
            val ev = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_MOVE, 1, props, coords, 0, buttons,
                1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE_RELATIVE, 0
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
