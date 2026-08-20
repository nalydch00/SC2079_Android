package com.sc2079.mdp.protocol

import com.sc2079.mdp.model.Direction

/**
 * Turns raw Bluetooth lines into [IncomingMessage]s.
 *
 * The grammar follows the ARCM briefing slides:
 *
 * ```
 * ROBOT,<x>,<y>,<direction>          update robot pose            (C.10)
 * TARGET,<obstacle>,<targetId>       show image target id         (C.9)
 * TARGET,<obstacle>,<targetId>,<face>  ... and the face it is on  (C.9)
 * STATUS,<text>                      status/update message        (C.4)
 * MSG,[<text>]                       status/update message        (C.4)
 * ```
 *
 * Obstacle numbers are accepted both bare (`2`) and in the `B2` form used in the
 * slides, and coordinates may be wrapped in brackets - `(10,6)` - because the
 * slides show that spelling for the outgoing `ADD` message.
 */
object MessageParser {

    fun parse(line: String): IncomingMessage {
        val raw = line.trim()
        if (raw.isEmpty()) return IncomingMessage.Unknown(line)

        val fields = split(raw)
        return when (fields.firstOrNull()?.uppercase()) {
            "ROBOT" -> parseRobot(fields, raw)
            "TARGET" -> parseTarget(fields, raw)
            "STATUS", "MSG", "MESSAGE", "INFO" -> parseStatus(raw)
            else -> IncomingMessage.Unknown(raw)
        }
    }

    private fun parseRobot(fields: List<String>, raw: String): IncomingMessage {
        if (fields.size < 4) return IncomingMessage.Unknown(raw)
        val x = fields[1].toIntOrNull() ?: return IncomingMessage.Unknown(raw)
        val y = fields[2].toIntOrNull() ?: return IncomingMessage.Unknown(raw)
        val facing = Direction.fromCode(fields[3]) ?: return IncomingMessage.Unknown(raw)
        return IncomingMessage.RobotUpdate(x, y, facing, raw)
    }

    private fun parseTarget(fields: List<String>, raw: String): IncomingMessage {
        if (fields.size < 3) return IncomingMessage.Unknown(raw)
        val obstacleId = obstacleNumber(fields[1]) ?: return IncomingMessage.Unknown(raw)
        val targetId = fields[2].trim()
        if (targetId.isEmpty()) return IncomingMessage.Unknown(raw)
        val face = if (fields.size >= 4) Direction.fromCode(fields[3]) else null
        return IncomingMessage.TargetUpdate(obstacleId, targetId, face, raw)
    }

    private fun parseStatus(raw: String): IncomingMessage {
        // Keep any commas that belong to the message body itself.
        val body = raw.substringAfter(',', missingDelimiterValue = "").trim()
        val text = body.removeSurrounding("[", "]").trim()
        if (text.isEmpty()) return IncomingMessage.Unknown(raw)
        return IncomingMessage.Status(text, raw)
    }

    /** Accepts `B2`, `b2` and `2`, returning the obstacle number. */
    fun obstacleNumber(field: String): Int? {
        val cleaned = field.trim().removePrefix("B").removePrefix("b")
        return cleaned.toIntOrNull()
    }

    /** Splits on commas, dropping the brackets the slides use around coordinates. */
    private fun split(raw: String): List<String> =
        raw.split(',').map { it.trim().trim('(', ')', '[', ']').trim() }
}
