package com.gfn.touchcontrols

import android.content.Context
import org.json.JSONObject

enum class IconStyle {
    JOYSTICK,
    FIRE,
    AIM,
    JUMP,
    CROUCH,
    PRONE,
    RELOAD,
    GRENADE,
    SPRINT,
    INTERACT,
    MELEE,
    PING,
    SLOT,
    MAP,
    TAB,
    ESC
}

enum class ControlType {
    JOYSTICK_WASD,
    MOUSE_BUTTON,
    KEY_HOLD,
    KEY_TAP
}

enum class ControlId(
    val title: String,
    val badge: String,
    val type: ControlType,
    val code: Int,
    val icon: IconStyle,
    val defX: Float,
    val defY: Float,
    val defSizeDp: Float,
    val defVisible: Boolean = true
) {
    JOYSTICK("Joystick WASD", "WASD", ControlType.JOYSTICK_WASD, 0, IconStyle.JOYSTICK, 0.16f, 0.70f, 138f, true),
    SPRINT("Correr", "SHIFT", ControlType.KEY_HOLD, 0x02, IconStyle.SPRINT, 0.14f, 0.37f, 48f, true),

    FIRE("Disparar", "LMB", ControlType.MOUSE_BUTTON, 0x01, IconStyle.FIRE, 0.84f, 0.70f, 80f, true),
    FIRE_LEFT("Disparo Izq.", "LMB", ControlType.MOUSE_BUTTON, 0x01, IconStyle.FIRE, 0.12f, 0.23f, 64f, false),
    AIM("Apuntar (ADS)", "RMB", ControlType.MOUSE_BUTTON, 0x02, IconStyle.AIM, 0.86f, 0.43f, 66f, true),

    JUMP("Saltar / Trepar", "ESP", ControlType.KEY_HOLD, 0x2C, IconStyle.JUMP, 0.94f, 0.56f, 54f, true),
    CROUCH("Agacharse", "C", ControlType.KEY_TAP, 0x06, IconStyle.CROUCH, 0.75f, 0.85f, 52f, true),
    PRONE("Acostarse", "Z", ControlType.KEY_TAP, 0x1D, IconStyle.PRONE, 0.66f, 0.86f, 50f, true),
    RELOAD("Recargar", "R", ControlType.KEY_TAP, 0x15, IconStyle.RELOAD, 0.74f, 0.62f, 52f, true),
    GRENADE("Granada", "G", ControlType.KEY_TAP, 0x0A, IconStyle.GRENADE, 0.65f, 0.71f, 50f, true),
    MELEE("Cuchillo", "F", ControlType.KEY_TAP, 0x09, IconStyle.MELEE, 0.93f, 0.77f, 48f, true),
    INTERACT("Interactuar / Vehículo", "E", ControlType.KEY_TAP, 0x08, IconStyle.INTERACT, 0.72f, 0.42f, 50f, true),
    PING("Marcar enemigo", "Q", ControlType.KEY_TAP, 0x14, IconStyle.PING, 0.79f, 0.25f, 46f, true),

    SLOT_1("Arma 1", "1", ControlType.KEY_TAP, 0x1E, IconStyle.SLOT, 0.37f, 0.88f, 46f, true),
    SLOT_2("Arma 2", "2", ControlType.KEY_TAP, 0x1F, IconStyle.SLOT, 0.44f, 0.88f, 46f, true),
    SLOT_3("Gadget 1", "3", ControlType.KEY_TAP, 0x20, IconStyle.SLOT, 0.51f, 0.88f, 46f, true),
    SLOT_4("Gadget 2", "4", ControlType.KEY_TAP, 0x21, IconStyle.SLOT, 0.58f, 0.88f, 46f, true),

    MAP("Mapa", "M", ControlType.KEY_TAP, 0x10, IconStyle.MAP, 0.25f, 0.14f, 42f, true),
    SCOREBOARD("Tabla", "TAB", ControlType.KEY_HOLD, 0x2B, IconStyle.TAB, 0.33f, 0.14f, 42f, true),
    ESC("Menú Pausa", "ESC", ControlType.KEY_TAP, 0x29, IconStyle.ESC, 0.94f, 0.13f, 42f, true)
}

data class ControlItemState(
    val id: ControlId,
    var xFrac: Float = id.defX,
    var yFrac: Float = id.defY,
    var sizeDp: Float = id.defSizeDp,
    var visible: Boolean = id.defVisible
)

class ControlConfig(context: Context) {

    private val prefs = context.getSharedPreferences("gfn_controls_prefs", Context.MODE_PRIVATE)

    val items: MutableMap<ControlId, ControlItemState> = LinkedHashMap()

    var touchSensX: Float = 1.35f
    var touchSensY: Float = 1.15f
    var gyroEnabled: Boolean = true
    var gyroOnlyAdsOrFire: Boolean = false
    var gyroSensX: Float = 20.0f
    var gyroSensY: Float = 18.0f
    var gyroDeadzone: Float = 0.02f
    var gyroInvertY: Boolean = false
    var autoSprint: Boolean = true
    var aimToggleMode: Boolean = false
    var hudAlpha: Float = 0.48f
    var tabAlpha: Float = 0.38f
    var tabOnTop: Boolean = false

    init {
        load()
    }

    fun load() {
        items.clear()
        for (id in ControlId.entries) {
            items[id] = ControlItemState(id)
        }
        val raw = prefs.getString("config_json", null) ?: return
        try {
            val root = JSONObject(raw)
            touchSensX = root.optDouble("touchSensX", 1.35).toFloat()
            touchSensY = root.optDouble("touchSensY", 1.15).toFloat()
            gyroEnabled = root.optBoolean("gyroEnabled", true)
            gyroOnlyAdsOrFire = root.optBoolean("gyroOnlyAdsOrFire", false)
            gyroSensX = root.optDouble("gyroSensX", 20.0).toFloat()
            gyroSensY = root.optDouble("gyroSensY", 18.0).toFloat()
            gyroDeadzone = root.optDouble("gyroDeadzone", 0.02).toFloat()
            gyroInvertY = root.optBoolean("gyroInvertY", false)
            autoSprint = root.optBoolean("autoSprint", true)
            aimToggleMode = root.optBoolean("aimToggleMode", false)
            hudAlpha = root.optDouble("hudAlpha", 0.48).toFloat().coerceIn(0.15f, 0.95f)
            tabAlpha = root.optDouble("tabAlpha", 0.38).toFloat().coerceIn(0.15f, 0.85f)
            tabOnTop = root.optBoolean("tabOnTop", false)

            val controlsJson = root.optJSONObject("controls") ?: return
            for (id in ControlId.entries) {
                val obj = controlsJson.optJSONObject(id.name) ?: continue
                items[id]?.apply {
                    xFrac = obj.optDouble("x", id.defX.toDouble()).toFloat().coerceIn(0.03f, 0.97f)
                    yFrac = obj.optDouble("y", id.defY.toDouble()).toFloat().coerceIn(0.05f, 0.95f)
                    sizeDp = obj.optDouble("s", id.defSizeDp.toDouble()).toFloat().coerceIn(30f, 200f)
                    visible = obj.optBoolean("v", id.defVisible)
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun save() {
        val root = JSONObject()
        root.put("touchSensX", touchSensX.toDouble())
        root.put("touchSensY", touchSensY.toDouble())
        root.put("gyroEnabled", gyroEnabled)
        root.put("gyroOnlyAdsOrFire", gyroOnlyAdsOrFire)
        root.put("gyroSensX", gyroSensX.toDouble())
        root.put("gyroSensY", gyroSensY.toDouble())
        root.put("gyroDeadzone", gyroDeadzone.toDouble())
        root.put("gyroInvertY", gyroInvertY)
        root.put("autoSprint", autoSprint)
        root.put("aimToggleMode", aimToggleMode)
        root.put("hudAlpha", hudAlpha.toDouble())
        root.put("tabAlpha", tabAlpha.toDouble())
        root.put("tabOnTop", tabOnTop)

        val controlsJson = JSONObject()
        for ((id, st) in items) {
            val obj = JSONObject()
            obj.put("x", st.xFrac.toDouble())
            obj.put("y", st.yFrac.toDouble())
            obj.put("s", st.sizeDp.toDouble())
            obj.put("v", st.visible)
            controlsJson.put(id.name, obj)
        }
        root.put("controls", controlsJson)
        prefs.edit().putString("config_json", root.toString()).apply()
    }

    fun resetDefaults() {
        prefs.edit().remove("config_json").apply()
        touchSensX = 1.35f
        touchSensY = 1.15f
        gyroEnabled = true
        gyroOnlyAdsOrFire = false
        gyroSensX = 20.0f
        gyroSensY = 18.0f
        gyroDeadzone = 0.02f
        gyroInvertY = false
        autoSprint = true
        aimToggleMode = false
        hudAlpha = 0.48f
        tabAlpha = 0.38f
        tabOnTop = false
        load()
        save()
    }
}
