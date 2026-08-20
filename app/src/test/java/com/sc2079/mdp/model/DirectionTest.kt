package com.sc2079.mdp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectionTest {

    @Test
    fun `parses single letter codes and full names`() {
        assertEquals(Direction.NORTH, Direction.fromCode("N"))
        assertEquals(Direction.SOUTH, Direction.fromCode("s"))
        assertEquals(Direction.EAST, Direction.fromCode(" east "))
        assertEquals(Direction.WEST, Direction.fromCode("WEST"))
    }

    @Test
    fun `two letter headings fall back to the first letter`() {
        assertEquals(Direction.WEST, Direction.fromCode("WN"))
    }

    @Test
    fun `unknown headings return null`() {
        assertNull(Direction.fromCode("Q"))
        assertNull(Direction.fromCode(""))
        assertNull(Direction.fromCode(null))
    }

    @Test
    fun `turning left and right walks the compass`() {
        assertEquals(Direction.WEST, Direction.NORTH.turnLeft())
        assertEquals(Direction.EAST, Direction.NORTH.turnRight())
        assertEquals(Direction.SOUTH, Direction.NORTH.opposite())
    }

    @Test
    fun `steps match a bottom left origin`() {
        assertEquals(1, Direction.NORTH.dy)
        assertEquals(0, Direction.NORTH.dx)
        assertEquals(1, Direction.EAST.dx)
        assertEquals(-1, Direction.SOUTH.dy)
        assertEquals(-1, Direction.WEST.dx)
    }
}
