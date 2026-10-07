package com.sc2079.mdp.ui

import com.sc2079.mdp.model.Direction
import com.sc2079.mdp.model.Robot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the checklist C.4 exclusivity rule: only an `info`/`error`/`status`
 * message from the RPi may ever change the "Robot status" box text, whatever
 * else arrives over the link.
 */
class MainViewModelTest {

    @Test
    fun `a status message updates the status box`() {
        val viewModel = MainViewModel()
        viewModel.applyIncoming("""{"cat":"status","value":"Looking for target 2"}""")
        assertEquals("Looking for target 2", viewModel.status.value)
    }

    @Test
    fun `a robot pose update never touches the status box`() {
        val viewModel = MainViewModel()
        val before = viewModel.status.value
        viewModel.applyIncoming("""{"cat":"location","value":{"x":7,"y":2,"d":0}}""")
        assertEquals(before, viewModel.status.value)
        assertEquals(7, viewModel.arena.value.robot.x)
    }

    @Test
    fun `an image result lands on the obstacle the robot said it was capturing`() {
        val viewModel = MainViewModel()
        viewModel.addObstacle(3, 3)
        viewModel.addObstacle(5, 5)
        viewModel.applyIncoming(capturing(2))
        viewModel.applyIncoming(imageRec(classId = "20", label = "A"))
        assertEquals("A", viewModel.arena.value.obstacleById(2)?.targetId)
        assertNull(viewModel.arena.value.obstacleById(1)?.targetId)
    }

    @Test
    fun `an image result never touches the status box`() {
        val viewModel = MainViewModel()
        viewModel.addObstacle(3, 3)
        viewModel.applyIncoming(capturing(1))
        val before = viewModel.status.value
        viewModel.applyIncoming(imageRec(classId = "20", label = "A"))
        assertEquals(before, viewModel.status.value)
    }

    @Test
    fun `an image result with no capture announcement changes nothing`() {
        val viewModel = MainViewModel()
        viewModel.addObstacle(3, 3)
        viewModel.applyIncoming(imageRec(classId = "20", label = "A"))
        assertNull(viewModel.arena.value.obstacleById(1)?.targetId)
    }

    @Test
    fun `each capture announcement pairs with only one image result`() {
        val viewModel = MainViewModel()
        viewModel.addObstacle(3, 3)
        viewModel.applyIncoming(capturing(1))
        viewModel.applyIncoming(imageRec(classId = "20", label = "A"))
        viewModel.applyIncoming(imageRec(classId = "21", label = "B"))
        assertEquals("A", viewModel.arena.value.obstacleById(1)?.targetId)
    }

    @Test
    fun `a mode update never touches the status box`() {
        val viewModel = MainViewModel()
        val before = viewModel.status.value
        viewModel.applyIncoming("""{"cat":"mode","value":"1"}""")
        assertEquals(before, viewModel.status.value)
    }

    @Test
    fun `unrecognised traffic never touches the status box`() {
        val viewModel = MainViewModel()
        val before = viewModel.status.value
        viewModel.applyIncoming("some robot debug spam")
        assertEquals(before, viewModel.status.value)
    }

    @Test
    fun `a later status message can still change what a robot update could not`() {
        val viewModel = MainViewModel()
        viewModel.applyIncoming("""{"cat":"location","value":{"x":7,"y":2,"d":0}}""")
        val afterRobotUpdate = viewModel.status.value
        viewModel.applyIncoming("""{"cat":"status","value":"Ready to start"}""")
        assertNotEquals(afterRobotUpdate, viewModel.status.value)
        assertEquals("Ready to start", viewModel.status.value)
    }

    @Test
    fun `the trace records each reported move, starting from where the robot stood`() {
        val viewModel = MainViewModel()
        viewModel.applyIncoming(location(1, 5, 0))
        viewModel.applyIncoming(location(4, 5, 2))
        assertEquals(
            listOf(Robot(1, 1, Direction.NORTH), Robot(1, 5, Direction.NORTH), Robot(4, 5, Direction.EAST)),
            viewModel.trace.value,
        )
    }

    @Test
    fun `a repeated pose is recorded once`() {
        val viewModel = MainViewModel()
        viewModel.applyIncoming(location(1, 5, 0))
        viewModel.applyIncoming(location(1, 5, 0))
        assertEquals(2, viewModel.trace.value.size)
    }

    @Test
    fun `clear map resets the robot to the start and wipes the trace`() {
        val viewModel = MainViewModel()
        viewModel.addObstacle(5, 5)
        viewModel.applyIncoming(location(4, 5, 2))
        viewModel.clearArena()
        assertEquals(Robot(1, 1, Direction.NORTH), viewModel.arena.value.robot)
        assertTrue(viewModel.arena.value.obstacles.isEmpty())
        assertTrue(viewModel.trace.value.isEmpty())
    }

    @Test
    fun `reset run keeps obstacles but wipes results, the trace and the robot pose`() {
        val viewModel = MainViewModel()
        viewModel.addObstacle(5, 5)
        viewModel.applyIncoming(capturing(1))
        viewModel.applyIncoming(imageRec(classId = "20", label = "A"))
        viewModel.applyIncoming(location(4, 5, 2))
        viewModel.resetRun()
        val obstacle = viewModel.arena.value.obstacleById(1)
        assertEquals(5, obstacle?.x)
        assertNull(obstacle?.targetId)
        assertEquals(Robot(1, 1, Direction.NORTH), viewModel.arena.value.robot)
        assertTrue(viewModel.trace.value.isEmpty())
    }

    @Test
    fun `a capture announced before a reset doesn't claim a result after it`() {
        val viewModel = MainViewModel()
        viewModel.addObstacle(5, 5)
        viewModel.applyIncoming(capturing(1))
        viewModel.resetRun()
        viewModel.applyIncoming(imageRec(classId = "20", label = "A"))
        assertNull(viewModel.arena.value.obstacleById(1)?.targetId)
    }

    private fun location(x: Int, y: Int, d: Int) =
        """{"cat":"location","value":{"x":$x,"y":$y,"d":$d}}"""

    private fun capturing(obstacleId: Int) =
        """{"cat":"info","value":"Capturing image for obstacle id: $obstacleId"}"""

    private fun imageRec(classId: String, label: String) =
        """{"cat":"image-rec","value":{"obstacle_id":"$classId","conf":0.92,"image_id":"$label"}}"""
}
