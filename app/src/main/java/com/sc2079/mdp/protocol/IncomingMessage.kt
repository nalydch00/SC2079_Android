package com.sc2079.mdp.protocol

import com.sc2079.mdp.model.Direction

/**
 * A line received over the Bluetooth serial link after it has been recognised.
 *
 * Every variant keeps the [raw] text so the raw traffic log can show exactly what
 * arrived, while the status box only ever renders the summarised variants.
 */
sealed class IncomingMessage {

    abstract val raw: String

    /** `ROBOT,<x>,<y>,<direction>` - checklist C.10. */
    data class RobotUpdate(
        val x: Int,
        val y: Int,
        val facing: Direction,
        override val raw: String,
    ) : IncomingMessage()

    /** `TARGET,<obstacle>,<targetId>[,<face>]` - checklist C.9. */
    data class TargetUpdate(
        val obstacleId: Int,
        val targetId: String,
        val face: Direction?,
        override val raw: String,
    ) : IncomingMessage()

    /** `STATUS,<text>` or `MSG,[<text>]` - checklist C.4. */
    data class Status(
        val text: String,
        override val raw: String,
    ) : IncomingMessage()

    /** Anything the app does not model. Shown in the raw log only. */
    data class Unknown(override val raw: String) : IncomingMessage()
}
