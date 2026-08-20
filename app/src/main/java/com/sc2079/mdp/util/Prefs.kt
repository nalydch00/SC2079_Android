package com.sc2079.mdp.util

import android.content.Context

/**
 * Small wrapper over [android.content.SharedPreferences] for the settings the
 * team is most likely to change during integration: the movement command
 * strings and the last device we were paired with.
 */
class Prefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var lastDeviceAddress: String?
        get() = prefs.getString(KEY_LAST_DEVICE, null)
        set(value) = prefs.edit().putString(KEY_LAST_DEVICE, value).apply()

    fun command(action: CommandAction): String =
        prefs.getString(action.key, action.default) ?: action.default

    fun setCommand(action: CommandAction, value: String) {
        prefs.edit().putString(action.key, value.trim().ifEmpty { action.default }).apply()
    }

    fun resetCommands() {
        val editor = prefs.edit()
        CommandAction.entries.forEach { editor.remove(it.key) }
        editor.apply()
    }

    /**
     * The movement commands sent by the D-pad. Teams wire their robot to
     * different tokens, so every one of them is editable from the settings sheet
     * instead of being baked into the buttons.
     */
    enum class CommandAction(val key: String, val label: String, val default: String) {
        FORWARD("cmd_forward", "Forward", "f"),
        REVERSE("cmd_reverse", "Reverse", "r"),
        TURN_LEFT("cmd_turn_left", "Turn left", "tl"),
        TURN_RIGHT("cmd_turn_right", "Turn right", "tr"),
        REVERSE_LEFT("cmd_reverse_left", "Reverse left", "rl"),
        REVERSE_RIGHT("cmd_reverse_right", "Reverse right", "rr"),
        STOP("cmd_stop", "Stop", "s"),
        START_IMAGE("cmd_start_image", "Start image rec.", "START"),
        START_FASTEST("cmd_start_fastest", "Start fastest path", "FASTEST"),
    }

    private companion object {
        const val NAME = "mdp_prefs"
        const val KEY_LAST_DEVICE = "last_device_address"
    }
}
