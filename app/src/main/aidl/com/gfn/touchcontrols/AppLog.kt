package com.gfn.touchcontrols

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val MAX_LINES = 300
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val lock = Any()
    private var logFile: File? = null

    fun init(context: Context) {
        synchronized(lock) {
            if (logFile == null) {
                logFile = File(context.applicationContext.filesDir, "gfn_debug.log")
            }
        }
    }

    fun d(tag: String, msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = "$ts [$tag] $msg"
        Log.d(tag, msg)

        synchronized(lock) {
            if (lines.size >= MAX_LINES) {
                lines.removeFirst()
            }
            lines.addLast(entry)
            try {
                logFile?.appendText(entry + "\n")
            } catch (_: Throwable) {
            }
        }
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        val detail = if (tr != null) "$msg | ${tr.javaClass.simpleName}: ${tr.message}" else msg
        d(tag, detail)
    }

    fun dumpText(): String {
        synchronized(lock) {
            if (lines.isNotEmpty()) {
                return lines.joinToString("\n")
            }
            return try {
                val f = logFile
                if (f != null && f.exists()) f.readText() else "Sin registros todavía."
            } catch (_: Throwable) {
                "Sin registros todavía."
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            lines.clear()
            try {
                logFile?.writeText("")
            } catch (_: Throwable) {
            }
        }
    }

    fun copyToClipboard(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("GFN Touch Controls Log", dumpText()))
    }
}
