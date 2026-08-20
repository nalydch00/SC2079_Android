package com.sc2079.mdp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaTest {

    @Test
    fun `obstacles are numbered from one`() {
        val (arena, first) = Arena().addObstacle(2, 3)!!
        assertEquals(1, first.id)
        val (_, second) = arena.addObstacle(4, 5)!!
        assertEquals(2, second.id)
    }

    @Test
    fun `numbers are reused after a removal`() {
        var arena = Arena()
        arena = arena.addObstacle(1, 1)!!.first
        arena = arena.addObstacle(2, 2)!!.first
        arena = arena.removeObstacle(1)
        val (_, reused) = arena.addObstacle(3, 3)!!
        assertEquals(1, reused.id)
    }

    @Test
    fun `cannot stack two obstacles on one cell`() {
        val arena = Arena().addObstacle(1, 1)!!.first
        assertNull(arena.addObstacle(1, 1))
    }

    @Test
    fun `cannot place an obstacle outside the arena`() {
        val arena = Arena(columns = 20, rows = 20)
        assertNull(arena.addObstacle(20, 5))
        assertNull(arena.addObstacle(-1, 5))
        assertNotNull(arena.addObstacle(19, 19))
    }

    @Test
    fun `moving onto an occupied cell is rejected`() {
        var arena = Arena().addObstacle(1, 1)!!.first
        arena = arena.addObstacle(2, 2)!!.first
        val unchanged = arena.moveObstacle(1, 2, 2)
        assertEquals(1, unchanged.obstacleById(1)!!.x)
        assertEquals(1, unchanged.obstacleById(1)!!.y)
    }

    @Test
    fun `moving outside the arena is rejected`() {
        val arena = Arena(columns = 20, rows = 20).addObstacle(1, 1)!!.first
        val unchanged = arena.moveObstacle(1, 25, 1)
        assertEquals(1, unchanged.obstacleById(1)!!.x)
    }

    @Test
    fun `moving to a free cell succeeds`() {
        val arena = Arena().addObstacle(1, 1)!!.first.moveObstacle(1, 8, 9)
        assertEquals(8, arena.obstacleById(1)!!.x)
        assertEquals(9, arena.obstacleById(1)!!.y)
    }

    @Test
    fun `target id update keeps a face the user already annotated`() {
        var arena = Arena().addObstacle(3, 3)!!.first
        arena = arena.setTargetFace(1, Direction.EAST)
        arena = arena.setTargetId(1, "11", face = null)
        assertEquals("11", arena.obstacleById(1)!!.targetId)
        assertEquals(Direction.EAST, arena.obstacleById(1)!!.targetFace)
    }

    @Test
    fun `target id update overrides the face when one is supplied`() {
        var arena = Arena().addObstacle(3, 3)!!.first
        arena = arena.setTargetFace(1, Direction.EAST)
        arena = arena.setTargetId(1, "11", face = Direction.SOUTH)
        assertEquals(Direction.SOUTH, arena.obstacleById(1)!!.targetFace)
    }

    @Test
    fun `updates for an unknown obstacle are ignored`() {
        val arena = Arena()
        assertTrue(arena.setTargetId(9, "4").obstacles.isEmpty())
        assertTrue(arena.setTargetFace(9, Direction.NORTH).obstacles.isEmpty())
    }

    @Test
    fun `robot position is clamped to the grid`() {
        val arena = Arena(columns = 20, rows = 20).withRobot(99, -4, Direction.SOUTH)
        assertEquals(19, arena.robot.x)
        assertEquals(0, arena.robot.y)
        assertEquals(Direction.SOUTH, arena.robot.facing)
    }
}
