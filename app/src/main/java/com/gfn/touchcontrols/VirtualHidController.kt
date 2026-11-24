package com.gfn.touchcontrols

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import kotlin.math.roundToInt

object VirtualHidController {

    private var hidService: IHidService? = null
    private var isBound = false
    private val executor = Executors.newSingleThreadExecutor()

    private val heldBySource = LinkedHashMap<String, Pair<Int, Set<Int>>>()
    private val mouseSources = LinkedHashMap<String, Int>()
    private var currentMouseButtons = 0

    private var accDx = 0f
    private var accDy = 0f
    private val lock = Any()

    var statusListener: ((String) -> Unit)? = null
    var lastStatus: String = "Sin conectar"
        private set

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            hidService = IHidService.Stub.asInterface(service)
            isBound = true
            executor.execute {
                val res = try {
                    hidService?.openHid() ?: "NULL_SERVICE"
                } catch (e: Throwable) {
                    "ERROR: ${e.message}"
                }
                updateStatus(res)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            hidService = null
            isBound = false
            updateStatus("Desconectado")
        }
    }

    fun bind(context: Context) {
        if (isBound && hidService != null) {
            executor.execute {
                val res = try {
                    hidService?.openHid() ?: "NULL_SERVICE"
                } catch (e: Throwable) {
                    "ERROR: ${e.message}"
                }
                updateStatus(res)
            }
            return
        }
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, HidUserService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("hid_service")
                .debuggable(false)
                .version(1)
            updateStatus("Conectando con Shizuku...")
            Shizuku.bindUserService(args, serviceConnection)
        } catch (e: Throwable) {
            updateStatus("Error Shizuku: ${e.message}")
        }
    }

    fun unbind(context: Context) {
        releaseAll()
        if (isBound) {
            try {
                val args = Shizuku.UserServiceArgs(
                    ComponentName(context.packageName, HidUserService::class.java.name)
                )
                    .daemon(false)
                    .processNameSuffix("hid_service")
                    .debuggable(false)
                    .version(1)
                Shizuku.unbindUserService(args, serviceConnection, true)
            } catch (_: Throwable) {
            }
            isBound = false
            hidService = null
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
            try {
                hidService?.sendKeyboard(reportMods, reportKeys)
            } catch (_: Throwable) {
            }
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
            try {
                hidService?.sendMouse(btnState, 0, 0, 0)
            } catch (_: Throwable) {
            }
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
            try {
                hidService?.sendMouse(btnState, sendX, sendY, 0)
            } catch (_: Throwable) {
            }
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
            try {
                hidService?.sendKeyboard(0, ByteArray(6))
                hidService?.sendMouse(0, 0, 0, 0)
            } catch (_: Throwable) {
            }
        }
    }

    private fun updateStatus(msg: String) {
        lastStatus = msg
        statusListener?.invoke(msg)
    }
}
