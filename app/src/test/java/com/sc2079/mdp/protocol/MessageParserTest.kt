package com.sc2079.mdp.protocol

import com.sc2079.mdp.model.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageParserTest {

    @Test
    fun `parses robot pose update`() {
        val message = MessageParser.parse("ROBOT,7,2,N")
        assertTrue(message is IncomingMessage.RobotUpdate)
        message as IncomingMessage.RobotUpdate
        assertEquals(7, message.x)
        assertEquals(2, message.y)
        assertEquals(Direction.NORTH, message.facing)
    }

    @Test
    fun `tolerates spacing and lower case in robot updates`() {
        val message = MessageParser.parse(" robot , 12 , 3 , w ")
        assertTrue(message is IncomingMessage.RobotUpdate)
        message as IncomingMessage.RobotUpdate
        assertEquals(12, message.x)
        assertEquals(3, message.y)
        assertEquals(Direction.WEST, message.facing)
    }

    @Test
    fun `rejects robot updates with a bad heading`() {
        assertTrue(MessageParser.parse("ROBOT,1,1,Q") is IncomingMessage.Unknown)
    }

    @Test
    fun `rejects robot updates with missing fields`() {
        assertTrue(MessageParser.parse("ROBOT,1,1") is IncomingMessage.Unknown)
    }

    @Test
    fun `parses target id without a face`() {
        val message = MessageParser.parse("TARGET,B2,11")
        assertTrue(message is IncomingMessage.TargetUpdate)
        message as IncomingMessage.TargetUpdate
        assertEquals(2, message.obstacleId)
        assertEquals("11", message.targetId)
        assertNull(message.face)
    }

    @Test
    fun `parses target id with a face`() {
        val message = MessageParser.parse("TARGET,B2,11,N")
        message as IncomingMessage.TargetUpdate
        assertEquals(2, message.obstacleId)
        assertEquals("11", message.targetId)
        assertEquals(Direction.NORTH, message.face)
    }

    @Test
    fun `accepts a bare obstacle number`() {
        val message = MessageParser.parse("TARGET,3,7")
        message as IncomingMessage.TargetUpdate
        assertEquals(3, message.obstacleId)
        assertEquals("7", message.targetId)
    }

    @Test
    fun `parses bracketed status messages`() {
        val message = MessageParser.parse("MSG,[Moving]")
        assertTrue(message is IncomingMessage.Status)
        assertEquals("Moving", (message as IncomingMessage.Status).text)
    }

    @Test
    fun `keeps commas inside a status body`() {
        val message = MessageParser.parse("STATUS,looking for target 2, standing by")
        assertEquals("looking for target 2, standing by", (message as IncomingMessage.Status).text)
    }

    @Test
    fun `unknown traffic is preserved verbatim`() {
        val message = MessageParser.parse("some robot debug spam")
        assertTrue(message is IncomingMessage.Unknown)
        assertEquals("some robot debug spam", message.raw)
    }

    @Test
    fun `blank lines are not status messages`() {
        assertTrue(MessageParser.parse("STATUS,") is IncomingMessage.Unknown)
        assertTrue(MessageParser.parse("   ") is IncomingMessage.Unknown)
    }

    @Test
    fun `obstacle numbers accept the B prefix`() {
        assertEquals(4, MessageParser.obstacleNumber("B4"))
        assertEquals(4, MessageParser.obstacleNumber("b4"))
        assertEquals(4, MessageParser.obstacleNumber(" 4 "))
        assertNull(MessageParser.obstacleNumber("north"))
    }
}
