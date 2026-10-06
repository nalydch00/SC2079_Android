package com.sc2079.mdp.protocol

import com.sc2079.mdp.model.Direction

/**
 * A line received over the Bluetooth serial link after it has been recognised.
 *
 * The RPi sends one JSON object per line - `{"cat":...,"value":...}` - see
 * [MessageParser] for the grammar of each `cat`. Every variant keeps the [raw]
 * text so the raw traffic log can show exactly what arrived. Only [Status] may
 * ever update the "Robot status" box (checklist C.4's selective status/update
 * message) - [RobotUpdate] and [ImageRecognised] update the map silently,
 * [ModeUpdate] carries no required side effect, and [Unknown] updates nothing.
 */
sealed class IncomingMessage {

    abstract val raw: String

    /** `{"cat":"location","value":{"x":..,"y":..,"d":..}}` - checklist C.10. */
    data class RobotUpdate(
        val x: Int,
        val y: Int,
        val facing: Direction,
        override val raw: String,
    ) : IncomingMessage()

    /**
     * `{"cat":"image-rec","value":{"obstacle_id":"20","image_id":"A",...}}` -
     * checklist C.9. [targetId] is the recognised image's label (`image_id`).
     * Despite its name, the RPi's `obstacle_id` holds the image class ID
     * (11-40), not the obstacle's number, so this message alone can't say which
     * obstacle it belongs to - that comes from the preceding
     * [Status.capturingObstacleId].
     */
    data class ImageRecognised(
        val targetId: String,
        override val raw: String,
    ) : IncomingMessage()

    /**
     * `{"cat":"info"|"error"|"status","value":"<text>"}` - checklist C.4. JSON's
     * own string quoting is the terminator here: [text] is exactly the decoded
     * `value` string, so nothing outside it can ever leak into the status box.
     */
    data class Status(
        val text: String,
        override val raw: String,
        /** Set when [text] is the RPi's "Capturing image for obstacle id: N". */
        val capturingObstacleId: Int? = null,
    ) : IncomingMessage()

    /**
     * `{"cat":"mode","value":"<mode>"}` - not required by the checklist (the
     * task description calls it "optional mode UI"), so this is recognised but
     * has no effect today rather than being left to fall through to [Unknown].
     */
    data class ModeUpdate(
        val mode: String,
        override val raw: String,
    ) : IncomingMessage()

    /** Anything the app does not model. Shown in the raw log only. */
    data class Unknown(override val raw: String) : IncomingMessage()
}
