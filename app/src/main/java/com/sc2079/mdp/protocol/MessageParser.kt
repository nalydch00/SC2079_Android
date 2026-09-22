package com.sc2079.mdp.protocol

import com.sc2079.mdp.model.Direction
import org.json.JSONException
import org.json.JSONObject

/**
 * Turns raw Bluetooth lines from the RPi into [IncomingMessage]s.
 *
 * The RPi sends one JSON object per line, `{"cat":<category>,"value":<value>}`:
 *
 * ```
 * {"cat":"info","value":"<text>"}                                  status box   (C.4)
 * {"cat":"error","value":"<text>"}                                 status box   (C.4)
 * {"cat":"status","value":"<text>"}                                status box   (C.4)
 * {"cat":"location","value":{"x":<int>,"y":<int>,"d":<heading>}}   robot pose   (C.10)
 * {"cat":"image-rec","value":{"obstacle_id":<int>,"image_id":<id>}} target id   (C.9)
 * {"cat":"mode","value":"<mode>"}                                  task mode (optional UI)
 * ```
 *
 * `d` (and `image-rec`'s optional `face`) accepts a heading letter (`N`/`E`/`S`/
 * `W`), this app's own outgoing obstacle-face code (`N=0,E=2,S=4,W=6`), or
 * degrees (`N=0,E=90,S=180,W=270`, matching [Direction.degrees]) - the RPi
 * side of this convention isn't pinned down to one scheme, so whichever the
 * localisation code actually emits is accepted. `image-rec`'s field names
 * (`obstacle_id`/`image_id`) are this app's assumption pending confirmation
 * from the RPi team; `obstacleId`/`id` and `imageId`/`target_id`/`targetId`
 * are accepted as aliases so a naming difference on their end doesn't quietly
 * turn every result into [IncomingMessage.Unknown].
 *
 * A line that isn't valid JSON, or whose `cat` isn't one of the above, is never
 * partially parsed - it becomes [IncomingMessage.Unknown] and only shows up in
 * the raw traffic log, exactly what checklist C.4's "selective information"
 * asks for.
 */
object MessageParser {

    fun parse(line: String): IncomingMessage {
        val raw = line.trim()
        if (raw.isEmpty()) return IncomingMessage.Unknown(line)

        val json = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return IncomingMessage.Unknown(raw)
        }

        return when (json.optString("cat").trim().lowercase()) {
            "info", "error", "status" -> parseStatus(json, raw)
            "location" -> parseLocation(json, raw)
            "image-rec" -> parseImageRec(json, raw)
            "mode" -> parseMode(json, raw)
            else -> IncomingMessage.Unknown(raw)
        }
    }

    private fun parseStatus(json: JSONObject, raw: String): IncomingMessage {
        val text = json.optString("value").trim()
        if (text.isEmpty()) return IncomingMessage.Unknown(raw)
        return IncomingMessage.Status(text, raw)
    }

    private fun parseLocation(json: JSONObject, raw: String): IncomingMessage {
        val value = json.optJSONObject("value") ?: return IncomingMessage.Unknown(raw)
        if (!value.has("x") || !value.has("y") || !value.has("d")) return IncomingMessage.Unknown(raw)
        val x = value.optInt("x", MISSING_INT)
        val y = value.optInt("y", MISSING_INT)
        if (x == MISSING_INT || y == MISSING_INT) return IncomingMessage.Unknown(raw)
        val facing = headingFrom(value.opt("d")) ?: return IncomingMessage.Unknown(raw)
        return IncomingMessage.RobotUpdate(x, y, facing, raw)
    }

    private fun parseImageRec(json: JSONObject, raw: String): IncomingMessage {
        val value = json.optJSONObject("value") ?: return IncomingMessage.Unknown(raw)
        val obstacleId = firstInt(value, "obstacle_id", "obstacleId", "id") ?: return IncomingMessage.Unknown(raw)
        val targetId = firstString(value, "image_id", "imageId", "target_id", "targetId")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return IncomingMessage.Unknown(raw)
        val face = value.opt("face")?.let(::headingFrom) ?: value.opt("d")?.let(::headingFrom)
        return IncomingMessage.TargetUpdate(obstacleId, targetId, face, raw)
    }

    private fun parseMode(json: JSONObject, raw: String): IncomingMessage {
        val mode = json.optString("value").trim()
        if (mode.isEmpty()) return IncomingMessage.Unknown(raw)
        return IncomingMessage.ModeUpdate(mode, raw)
    }

    /** Accepts a heading letter, this app's obstacle-face code, or degrees. */
    private fun headingFrom(raw: Any?): Direction? = when (raw) {
        is String -> Direction.fromCode(raw) ?: raw.trim().toIntOrNull()?.let(::headingFromNumber)
        is Number -> headingFromNumber(raw.toInt())
        else -> null
    }

    private fun headingFromNumber(code: Int): Direction? = when (code) {
        0 -> Direction.NORTH
        2, 90 -> Direction.EAST
        4, 180 -> Direction.SOUTH
        6, 270 -> Direction.WEST
        else -> null
    }

    private fun firstInt(value: JSONObject, vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key ->
            if (!value.has(key)) return@firstNotNullOfOrNull null
            when (val field = value.opt(key)) {
                is Number -> field.toInt()
                is String -> obstacleNumber(field)
                else -> null
            }
        }

    private fun firstString(value: JSONObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            if (!value.has(key)) return@firstNotNullOfOrNull null
            when (val field = value.opt(key)) {
                is String -> field
                is Number -> field.toString()
                else -> null
            }
        }

    /** Accepts `B2`, `b2` and `2`, returning the obstacle number. */
    fun obstacleNumber(field: String): Int? {
        val cleaned = field.trim().removePrefix("B").removePrefix("b")
        return cleaned.toIntOrNull()
    }

    private const val MISSING_INT = Int.MIN_VALUE
}
