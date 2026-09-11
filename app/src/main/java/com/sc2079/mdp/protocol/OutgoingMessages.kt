package com.sc2079.mdp.protocol

import com.sc2079.mdp.model.Direction
import com.sc2079.mdp.model.Obstacle
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builders for the JSON envelope the RPi expects over the Bluetooth link:
 *
 * ```
 * {"cat":"obstacles","value":{"obstacles":[{"x":5,"y":10,"id":1,"d":2}],"mode":"0"}}
 * {"cat":"control","value":"start"}
 * {"cat":"manual","value":"FW01"}
 * ```
 *
 * "obstacles" always carries the *complete* current map, not a diff - so
 * placing, moving, removing or annotating a single obstacle re-sends the whole
 * array plus the current task [MODE_IMAGE_RECOGNITION]/[MODE_FASTEST_PATH].
 * "control" carries fixed trigger words the RPi recognises (currently just
 * `"start"`). "manual" wraps whatever raw STM command string the movement
 * buttons are configured to send (checklist C.3) - those strings stay
 * user-editable from Settings; this just supplies the envelope around them.
 *
 * Uses `org.json` (built into the Android platform) rather than a JSON
 * library dependency, since nothing here needs more than building a couple of
 * small, fixed-shape objects.
 */
object OutgoingMessages {

    const val MODE_IMAGE_RECOGNITION = "0"
    const val MODE_FASTEST_PATH = "1"

    /** Sent as the `d` field for an obstacle with no target face annotated yet. */
    const val NO_FACE_CODE = -1

    fun obstaclesMessage(obstacles: List<Obstacle>, mode: String): String {
        val obstaclesArray = JSONArray()
        obstacles.sortedBy { it.id }.forEach { obstacle ->
            obstaclesArray.put(
                JSONObject()
                    .put("x", obstacle.x)
                    .put("y", obstacle.y)
                    .put("id", obstacle.id)
                    .put("d", obstacle.targetFace?.let(::directionCode) ?: NO_FACE_CODE),
            )
        }
        val value = JSONObject()
            .put("obstacles", obstaclesArray)
            .put("mode", mode)
        return JSONObject()
            .put("cat", "obstacles")
            .put("value", value)
            .toString()
    }

    fun controlMessage(command: String): String =
        JSONObject().put("cat", "control").put("value", command).toString()

    fun manualMessage(command: String): String =
        JSONObject().put("cat", "manual").put("value", command).toString()

    /** N=0, E=2, S=4, W=6 - the team's direction-code convention for the `d` field. */
    fun directionCode(direction: Direction): Int = when (direction) {
        Direction.NORTH -> 0
        Direction.EAST -> 2
        Direction.SOUTH -> 4
        Direction.WEST -> 6
    }
}
