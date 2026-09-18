package com.sc2079.mdp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Guards the checklist C.4 exclusivity rule: only a `STATUS`/`MSG` line may
 * ever change the "Robot status" box text, whatever else arrives over the link.
 */
class MainViewModelTest {

    @Test
    fun `a status message updates the status box`() {
        val viewModel = MainViewModel()
        viewModel.applyIncoming("MSG,[Looking for target 2]")
        assertEquals("Looking for target 2", viewModel.status.value)
    }

    @Test
    fun `a robot pose update never touches the status box`() {
        val viewModel = MainViewModel()
        val before = viewModel.status.value
        viewModel.applyIncoming("ROBOT,7,2,N")
        assertEquals(before, viewModel.status.value)
        assertEquals(7, viewModel.arena.value.robot.x)
    }

    @Test
    fun `a target update never touches the status box`() {
        val viewModel = MainViewModel()
        viewModel.addObstacle(3, 3)
        val before = viewModel.status.value
        viewModel.applyIncoming("TARGET,1,11,N")
        assertEquals(before, viewModel.status.value)
        assertEquals("11", viewModel.arena.value.obstacleById(1)?.targetId)
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
        viewModel.applyIncoming("ROBOT,7,2,N")
        val afterRobotUpdate = viewModel.status.value
        viewModel.applyIncoming("STATUS,Ready to start")
        assertNotEquals(afterRobotUpdate, viewModel.status.value)
        assertEquals("Ready to start", viewModel.status.value)
    }
}
