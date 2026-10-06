package com.sc2079.mdp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    private fun capturing(obstacleId: Int) =
        """{"cat":"info","value":"Capturing image for obstacle id: $obstacleId"}"""

    private fun imageRec(classId: String, label: String) =
        """{"cat":"image-rec","value":{"obstacle_id":"$classId","conf":0.92,"image_id":"$label"}}"""
}
