package com.sc2079.mdp.model

/**
 * Robot pose on the arena map.
 *
 * [x] and [y] are the grid coordinates of the centre cell of the robot's 3x3
 * footprint, matching the `ROBOT,<x>,<y>,<direction>` message in checklist C.10.
 */
data class Robot(
    val x: Int,
    val y: Int,
    val facing: Direction,
) {
    /** Size of the robot footprint in grid cells (the robot covers [FOOTPRINT] x [FOOTPRINT]). */
    companion object {
        const val FOOTPRINT = 3
    }
}
