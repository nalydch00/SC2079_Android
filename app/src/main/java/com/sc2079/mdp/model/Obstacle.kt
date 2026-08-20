package com.sc2079.mdp.model

/**
 * A numbered obstacle block placed on the arena map.
 *
 * @param id the obstacle number shown in small white text (1, 2, 3, ... n).
 * @param x  grid column, 0 at the left edge of the arena.
 * @param y  grid row, 0 at the bottom edge of the arena.
 * @param targetFace the face annotated by the user as carrying the image target,
 *   or `null` when no face has been chosen yet (checklist C.7).
 * @param targetId the image target id reported by the robot over Bluetooth, or
 *   `null` while the target is still unknown (checklist C.9).
 */
data class Obstacle(
    val id: Int,
    val x: Int,
    val y: Int,
    val targetFace: Direction? = null,
    val targetId: String? = null,
)
