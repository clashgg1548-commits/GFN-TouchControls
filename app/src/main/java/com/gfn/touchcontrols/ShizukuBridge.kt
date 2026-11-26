package com.gfn.touchcontrols

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import rikka.shizuku.Shizuku

class ShizukuBridge(
    private val appContext: Context,
    private val onStatusChanged: () -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var started = false

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            connectVirtualHid()
        }
        notifyStatus()
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        notifyStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        notifyStatus()
    }

    fun start() {
        if (started) return
        started = true
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            VirtualHidController.statusListener = { notifyStatus() }
        } catch (_: Throwable) {
        }
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (_: Throwable) {
        }
        VirtualHidController.statusListener = null
    }

    fun isShizukuAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun hasShizukuPermission(): Boolean {
        if (!isShizukuAlive()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun isMouseConnected(): Boolean = try {
        VirtualHidController.isConnected
    } catch (_: Throwable) {
        false
    }

    fun isFullyReady(): Boolean = hasShizukuPermission() && isMouseConnected()

    fun connectOrRequestPermission(requestCode: Int = 1001) {
        try {
            if (!isShizukuAlive()) {
                notifyStatus()
                return
            }
            if (hasShizukuPermission()) {
                connectVirtualHid()
            } else {
                Shizuku.requestPermission(requestCode)
            }
        } catch (_: Throwable) {
        }
        notifyStatus()
    }

    fun connectVirtualHid() {
        Thread {
            try {
                VirtualHidController.connect(appContext)
            } catch (_: Throwable) {
            }
            notifyStatus()
        }.start()
    }

    private fun notifyStatus() {
        mainHandler.post { onStatusChanged() }
    }
}
