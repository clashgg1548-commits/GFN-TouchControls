package com.gfn.touchcontrols

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

class ShizukuBridge(
    private val context: Context,
    private val onStatusChanged: () -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectExecutor = Executors.newSingleThreadExecutor()
    private var started = false

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            connectVirtualHidAsync()
        } else {
            notifyStatus()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        if (hasShizukuPermission() && !VirtualHidController.isConnected) {
            connectVirtualHidAsync()
        } else {
            notifyStatus()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        notifyStatus()
    }

    private val controllerCallback: () -> Unit = {
        notifyStatus()
    }

    fun start() {
        if (started) return
        started = true
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            VirtualHidController.onStateChanged = controllerCallback
        } catch (_: Throwable) {
        }
        if (isShizukuAlive() && hasShizukuPermission() && !VirtualHidController.isConnected) {
            connectVirtualHidAsync()
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
        if (VirtualHidController.onStateChanged === controllerCallback) {
            VirtualHidController.onStateChanged = null
        }
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

    fun isReady(): Boolean = hasShizukuPermission() && isMouseConnected()

    fun statusText(): String = VirtualHidController.lastStatus

    fun connectOrRequestPermission(requestCode: Int = 1001) {
        try {
            if (!isShizukuAlive()) {
                notifyStatus()
                return
            }
            if (!hasShizukuPermission()) {
                Shizuku.requestPermission(requestCode)
                notifyStatus()
                return
            }
            connectVirtualHidAsync()
        } catch (_: Throwable) {
            notifyStatus()
        }
    }

    fun connectVirtualHidAsync() {
        connectExecutor.execute {
            try {
                VirtualHidController.connect(appContext)
            } catch (_: Throwable) {
            }
            notifyStatus()
        }
    }

    private fun notifyStatus() {
        mainHandler.post { onStatusChanged() }
    }
}
