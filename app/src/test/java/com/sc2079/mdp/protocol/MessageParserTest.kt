package com.sc2079.mdp.protocol

import com.sc2079.mdp.model.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageParserTest {

    @Test
    fun `parses a location update`() {
        val message = MessageParser.parse("""{"cat":"location","value":{"x":7,"y":2,"d":0}}""")
        assertTrue(message is IncomingMessage.RobotUpdate)
        message as IncomingMessage.RobotUpdate
        assertEquals(7, message.x)
        assertEquals(2, message.y)
        assertEquals(Direction.NORTH, message.facing)
    }

    @Test
    fun `accepts a heading letter for location`() {
        val message = MessageParser.parse("""{"cat":"location","value":{"x":1,"y":1,"d":"W"}}""")
        assertEquals(Direction.WEST, (message as IncomingMessage.RobotUpdate).facing)
    }

    @Test
    fun `accepts degrees for location`() {
        val message = MessageParser.parse("""{"cat":"location","value":{"x":1,"y":1,"d":90}}""")
        assertEquals(Direction.EAST, (message as IncomingMessage.RobotUpdate).facing)
    }

    @Test
    fun `accepts the obstacle-face code for location`() {
        val message = MessageParser.parse("""{"cat":"location","value":{"x":1,"y":1,"d":6}}""")
        assertEquals(Direction.WEST, (message as IncomingMessage.RobotUpdate).facing)
    }

    @Test
    fun `rejects a location missing a field`() {
        assertTrue(MessageParser.parse("""{"cat":"location","value":{"x":1,"y":1}}""") is IncomingMessage.Unknown)
    }

    @Test
    fun `rejects a location with a bad heading`() {
        assertTrue(MessageParser.parse("""{"cat":"location","value":{"x":1,"y":1,"d":"Q"}}""") is IncomingMessage.Unknown)
    }

    @Test
    fun `parses an image-rec update`() {
        val message = MessageParser.parse(
            """{"cat":"image-rec","value":{"obstacle_id":2,"image_id":"11"}}""",
        )
        assertTrue(message is IncomingMessage.TargetUpdate)
        message as IncomingMessage.TargetUpdate
        assertEquals(2, message.obstacleId)
        assertEquals("11", message.targetId)
        assertNull(message.face)
    }

    @Test
    fun `parses an image-rec update with a face`() {
        val message = MessageParser.parse(
            """{"cat":"image-rec","value":{"obstacle_id":2,"image_id":"11","face":"N"}}""",
        )
        message as IncomingMessage.TargetUpdate
        assertEquals(Direction.NORTH, message.face)
    }

    @Test
    fun `accepts alias keys for image-rec`() {
        val message = MessageParser.parse(
            """{"cat":"image-rec","value":{"id":"B3","targetId":"7"}}""",
        )
        message as IncomingMessage.TargetUpdate
        assertEquals(3, message.obstacleId)
        assertEquals("7", message.targetId)
    }

    @Test
    fun `rejects an image-rec missing the obstacle id`() {
        assertTrue(
            MessageParser.parse("""{"cat":"image-rec","value":{"image_id":"11"}}""") is IncomingMessage.Unknown,
        )
    }

    @Test
    fun `parses an info message`() {
        val message = MessageParser.parse("""{"cat":"info","value":"Moving"}""")
        assertTrue(message is IncomingMessage.Status)
        assertEquals("Moving", (message as IncomingMessage.Status).text)
    }

    @Test
    fun `parses an error message`() {
        val message = MessageParser.parse("""{"cat":"error","value":"Obstacle blocked"}""")
        assertEquals("Obstacle blocked", (message as IncomingMessage.Status).text)
    }

    @Test
    fun `parses a status message`() {
        val message = MessageParser.parse("""{"cat":"status","value":"Ready to start"}""")
        assertEquals("Ready to start", (message as IncomingMessage.Status).text)
    }

    @Test
    fun `a status value never needs its own quoting - JSON already provides it`() {
        val message = MessageParser.parse(
            """{"cat":"status","value":"looking for target 2, standing by"}""",
        )
        assertEquals("looking for target 2, standing by", (message as IncomingMessage.Status).text)
    }

    @Test
    fun `rejects a blank status value`() {
        assertTrue(MessageParser.parse("""{"cat":"status","value":""}""") is IncomingMessage.Unknown)
    }

    @Test
    fun `parses a mode update`() {
        val message = MessageParser.parse("""{"cat":"mode","value":"1"}""")
        assertTrue(message is IncomingMessage.ModeUpdate)
        assertEquals("1", (message as IncomingMessage.ModeUpdate).mode)
    }

    @Test
    fun `unrecognised cat is not recognised`() {
        assertTrue(MessageParser.parse("""{"cat":"debug","value":"spam"}""") is IncomingMessage.Unknown)
    }

    @Test
    fun `the old ARCM plain-text format no longer parses`() {
        assertTrue(MessageParser.parse("ROBOT,7,2,N") is IncomingMessage.Unknown)
        assertTrue(MessageParser.parse("STATUS,\"Ready to start\"") is IncomingMessage.Unknown)
    }

    @Test
    fun `invalid json is preserved verbatim as unknown`() {
        val message = MessageParser.parse("some robot debug spam")
        assertTrue(message is IncomingMessage.Unknown)
        assertEquals("some robot debug spam", message.raw)
    }

    @Test
    fun `blank lines are not recognised`() {
        assertTrue(MessageParser.parse("") is IncomingMessage.Unknown)
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
