package com.gfn.touchcontrols

import android.content.Context

object HudPresetManager {
    private const val PREFS_NAME = "gfn_hud_presets"
    private const val CONFIG_PREFS_NAME = "gfn_hud_config"
    private const val KEY_ACTIVE_PRESET = "active_preset"
    private const val KEY_CUSTOM_KEYS = "custom_keys_csv"

    val availableExtraKeys = listOf(
        "TAB (TAB)", "SHIFT (SHIFT)", "CTRL (CTRL)", "ALT (ALT)",
        "ESPACIO (SPACE)", "ESC (ESC)", "E (E)", "Q (Q)", "R (R)",
        "F (F)", "G (G)", "C (C)", "Z (Z)", "1 (1)", "2 (2)", "3 (3)", "4 (4)", "M (M)"
    )

    fun getActivePreset(ctx: Context): String =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_PRESET, "shooter") ?: "shooter"

    fun applyPreset(ctx: Context, preset: String) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE_PRESET, preset).apply()

        val editor = ctx.getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE).edit()
        when (preset) {
            "shooter" -> {
                editor.putBoolean("gyro_enabled", true)
                editor.putBoolean("gyro_only_ads_or_fire", true)
                editor.putFloat("touch_sens_x", 1.35f)
                editor.putFloat("touch_sens_y", 1.15f)
                editor.putBoolean("auto_sprint", true)
            }
            "adventure" -> {
                editor.putBoolean("gyro_enabled", false)
                editor.putFloat("touch_sens_x", 1.10f)
                editor.putFloat("touch_sens_y", 1.00f)
                editor.putBoolean("auto_sprint", false)
            }
        }
        editor.apply()
    }

    fun getCustomKeys(ctx: Context): MutableList<String> {
        val raw = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_KEYS, "") ?: ""
        if (raw.isBlank()) return mutableListOf()
        return raw.split("|").filter { it.isNotBlank() }.toMutableList()
    }

    fun addCustomKey(ctx: Context, keyLabel: String) {
        val list = getCustomKeys(ctx)
        if (!list.contains(keyLabel)) {
            list.add(keyLabel)
            saveCustomKeys(ctx, list)
        }
    }

    fun removeCustomKey(ctx: Context, keyLabel: String) {
        val list = getCustomKeys(ctx)
        if (list.remove(keyLabel)) {
            saveCustomKeys(ctx, list)
        }
    }

    fun clearCustomKeys(ctx: Context) {
        saveCustomKeys(ctx, mutableListOf())
    }

    private fun saveCustomKeys(ctx: Context, list: List<String>) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CUSTOM_KEYS, list.joinToString("|")).apply()
    }
}
