package com.sc2079.mdp.protocol

import com.sc2079.mdp.model.Direction
import com.sc2079.mdp.model.Obstacle
import com.sc2079.mdp.model.Robot

/**
 * Builders for the strings the tablet transmits, using the formats shown in the
 * ARCM briefing slides so the AMD tool's command log reads correctly.
 *
 * ```
 * ADD,B<n>,(<x>,<y>)   obstacle n placed / moved   (C.6)
 * SUB,B<n>             obstacle n removed          (C.6)
 * FACE,B<n>,<face>     target face annotated       (C.7)
 * ROBOT,<x>,<y>,<dir>  robot pose set on the map
 * ```
 */
object OutgoingMessages {

    fun addObstacle(obstacle: Obstacle): String =
        "ADD,B${obstacle.id},(${obstacle.x},${obstacle.y})"

    fun removeObstacle(obstacleId: Int): String = "SUB,B$obstacleId"

    fun targetFace(obstacleId: Int, face: Direction?): String =
        "FACE,B$obstacleId,${face?.code ?: "NONE"}"

    fun robotPose(robot: Robot): String =
        "ROBOT,${robot.x},${robot.y},${robot.facing.code}"

    /** One `ADD` line per obstacle, for re-sending the whole map in one go. */
    fun fullMap(obstacles: List<Obstacle>): List<String> = buildList {
        obstacles.sortedBy { it.id }.forEach { obstacle ->
            add(addObstacle(obstacle))
            obstacle.targetFace?.let { add(targetFace(obstacle.id, it)) }
        }
    }
}
