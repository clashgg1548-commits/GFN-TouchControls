package com.gfn.touchcontrols

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.lang.reflect.Method
import java.util.concurrent.Executors
import kotlin.math.roundToInt

object VirtualHidController {

    private const val TAG = "VirtualHidController"
    private val executor = Executors.newSingleThreadExecutor()

    private var inputManager: Any? = null
    private var injectMethod: Method? = null

    // Proceso nativo de ratón por hardware uinput
    private var uinputProcess: Process? = null
    private var uinputWriter: BufferedWriter? = null

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

            // 1. Teclado vía Shizuku Binder (ya comprobado que funciona 100%)
            val rawBinder = SystemServiceHelper.getSystemService("input")
            if (rawBinder != null) {
                val wrapper = ShizukuBinderWrapper(rawBinder)
                val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
                val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
                inputManager = asInterface.invoke(null, wrapper)
                injectMethod = inputManager?.javaClass?.methods?.firstOrNull {
                    it.name == "injectInputEvent" && it.parameterTypes.isNotEmpty() &&
                        InputEvent::class.java.isAssignableFrom(it.parameterTypes[0])
                }
            }

            // 2. Ratón FÍSICO real a nivel de kernel mediante /system/bin/uinput
            initHardwareMouse()

            isConnected = true
            updateStatus("CONECTADO (Hardware Mouse) ✅")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Error connect", e)
            updateStatus("Error: ${e.message}")
            isConnected = false
            return false
        }
    }

    private fun initHardwareMouse() {
        try {
            uinputProcess?.destroy()
            val proc = Shizuku.newProcess(arrayOf("/system/bin/uinput", "-"), null, null)
            val writer = BufferedWriter(OutputStreamWriter(proc.outputStream))

            // Registra un ratón USB real con clics (izquierdo, derecho, medio) y movimiento relativo X/Y
            val regJson = "{\"id\":1,\"command\":\"register\",\"name\":\"GFN Hardware Mouse\",\"vid\":6225,\"pid\":20209,\"bus\":\"usb\",\"configuration\":[{\"type\":\"UI_SET_EVBIT\",\"data\":[\"EV_KEY\",\"EV_REL\"]},{\"type\":\"UI_SET_KEYBIT\",\"data\":[272,273,274]},{\"type\":\"UI_SET_RELBIT\",\"data\":[0,1,8]}]}"
            writer.write(regJson)
            writer.newLine()
            writer.flush()

            uinputProcess = proc
            uinputWriter = writer
            Log.i(TAG, "Ratón por hardware registrado exitosamente con uinput")
        } catch (e: Throwable) {
            Log.e(TAG, "Fallo al iniciar /system/bin/uinput", e)
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
            injectHardwareMouse(btnState, 0, 0)
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
            injectHardwareMouse(btnState, sendX, sendY)
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
            injectHardwareMouse(0, 0, 0)
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

    // Inyección de eventos físicos directos al kernel (uinput)
    private fun injectHardwareMouse(buttons: Int, dx: Int, dy: Int) {
        val writer = uinputWriter
        if (writer == null) {
            // Intento de reinicializar si se desconectó
            initHardwareMouse()
            return
        }

        val changed = buttons xor lastMouseButtons
        try {
            if (changed != 0) {
                // Click izquierdo (LMB = 0x01 -> BTN_LEFT 272)
                if ((changed and 0x01) != 0) {
                    val v = if ((buttons and 0x01) != 0) 1 else 0
                    writer.write("{\"id\":1,\"command\":\"inject\",\"events\":[1,272,$v,0,0,0]}\n")
                }
                // Click derecho / apuntar (RMB = 0x02 -> BTN_RIGHT 273)
                if ((changed and 0x02) != 0) {
                    val v = if ((buttons and 0x02) != 0) 1 else 0
                    writer.write("{\"id\":1,\"command\":\"inject\",\"events\":[1,273,$v,0,0,0]}\n")
                }
                lastMouseButtons = buttons
            }

            // Movimiento relativo de ratón al kernel: EV_REL (2), REL_X (0), REL_Y (1), EV_SYN (0)
            if (dx != 0 || dy != 0) {
                writer.write("{\"id\":1,\"command\":\"inject\",\"events\":[2,0,$dx,2,1,$dy,0,0,0]}\n")
            }
            writer.flush()
        } catch (e: Throwable) {
            Log.e(TAG, "Error escribiendo a uinput", e)
            initHardwareMouse()
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
