package com.sc2079.mdp.model

import kotlin.math.abs

/**
 * Maps a touch inside an obstacle block onto the face the user meant.
 *
 * [fx] and [fy] are the touch position within the block, normalised to 0..1,
 * with `fy = 1` at the north edge so the maths matches the drawn arena.
 * Touching the middle of the block returns `null`, which the GUI treats as
 * "clear the annotation".
 */
fun faceForOffset(fx: Float, fy: Float, deadZone: Float = 0.25f): Direction? {
    val dx = fx - 0.5f
    val dy = fy - 0.5f
    if (abs(dx) < deadZone && abs(dy) < deadZone) return null
    return if (abs(dx) > abs(dy)) {
        if (dx > 0f) Direction.EAST else Direction.WEST
    } else {
        if (dy > 0f) Direction.NORTH else Direction.SOUTH
    }
}
