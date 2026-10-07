package com.sc2079.mdp.ui

import com.sc2079.mdp.model.Arena
import com.sc2079.mdp.model.Direction
import com.sc2079.mdp.model.Robot
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceReportTest {

    @Test
    fun `lists obstacles by id with their face and what was read, then the path in order`() {
        val (withFirst, _) = Arena().addObstacle(12, 7)!!
        val (withBoth, second) = withFirst.addObstacle(5, 10)!!
        val arena = withBoth
            .setTargetFace(1, Direction.EAST)
            .setTargetFace(second.id, Direction.NORTH)
            .setTargetId(second.id, "A")
        val trace = listOf(Robot(1, 1, Direction.NORTH), Robot(1, 5, Direction.NORTH), Robot(4, 5, Direction.EAST))

        val report = TraceReport.format(arena, trace)

        assertTrue(report.startsWith("MDP robot trace\nArena: 20 x 20 cells."))
        assertTrue(
            report.contains(
                "Obstacles (id: x, y, face, read):\n" +
                    "  1: (12, 7)  face E  read -\n" +
                    "  2: (5, 10)  face N  read A\n",
            ),
        )
        assertTrue(
            report.endsWith(
                "Robot path (step: x, y, facing):\n" +
                    "  1: (1, 1) N\n" +
                    "  2: (1, 5) N\n" +
                    "  3: (4, 5) E",
            ),
        )
    }

    @Test
    fun `says so when there are no obstacles or moves yet`() {
        val report = TraceReport.format(Arena(), emptyList())
        assertTrue(report.contains("Obstacles (id: x, y, face, read):\n  none placed\n"))
        assertTrue(report.endsWith("Robot path (step: x, y, facing):\n  no moves yet"))
    }
}
