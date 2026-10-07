package com.sc2079.mdp.ui

import com.sc2079.mdp.model.Arena
import com.sc2079.mdp.model.Robot

/**
 * The Trace tab's text, built to be copied as-is into a chatbot to check the
 * path-planning algorithm: the coordinate conventions first, then the
 * obstacles the robot had to visit, then every pose it reported, in order.
 */
object TraceReport {

    fun format(arena: Arena, trace: List<Robot>): String = buildString {
        appendLine("MDP robot trace")
        appendLine("Arena: ${arena.columns} x ${arena.rows} cells. (0,0) is the bottom-left cell; x grows east, y grows north.")
        appendLine("The robot covers 3x3 cells; its position is the centre cell. Each obstacle covers 1 cell.")
        appendLine("Facing / face: N, E, S, W. \"face\" = the side of the obstacle the image is on; \"read\" = the image the robot recognised (- = none).")
        appendLine()
        appendLine("Obstacles (id: x, y, face, read):")
        if (arena.obstacles.isEmpty()) appendLine("  none placed")
        arena.obstacles.sortedBy { it.id }.forEach { obstacle ->
            appendLine(
                "  ${obstacle.id}: (${obstacle.x}, ${obstacle.y})  face ${obstacle.targetFace?.code ?: "-"}" +
                    "  read ${obstacle.targetId ?: "-"}",
            )
        }
        appendLine()
        appendLine("Robot path (step: x, y, facing):")
        if (trace.isEmpty()) append("  no moves yet")
        trace.forEachIndexed { index, pose ->
            if (index > 0) appendLine()
            append("  ${index + 1}: (${pose.x}, ${pose.y}) ${pose.facing.code}")
        }
    }
}
