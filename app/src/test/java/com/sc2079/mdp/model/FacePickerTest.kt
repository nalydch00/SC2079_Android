package com.sc2079.mdp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FacePickerTest {

    @Test
    fun `touching an edge picks that face`() {
        assertEquals(Direction.NORTH, faceForOffset(0.5f, 0.95f))
        assertEquals(Direction.SOUTH, faceForOffset(0.5f, 0.05f))
        assertEquals(Direction.EAST, faceForOffset(0.95f, 0.5f))
        assertEquals(Direction.WEST, faceForOffset(0.05f, 0.5f))
    }

    @Test
    fun `touching the middle clears the annotation`() {
        assertNull(faceForOffset(0.5f, 0.5f))
        assertNull(faceForOffset(0.6f, 0.55f))
    }

    @Test
    fun `corners resolve to the dominant axis`() {
        assertEquals(Direction.EAST, faceForOffset(0.95f, 0.85f))
        assertEquals(Direction.NORTH, faceForOffset(0.85f, 0.95f))
    }
}
