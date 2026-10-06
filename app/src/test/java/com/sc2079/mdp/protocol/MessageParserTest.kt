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
    fun `an image-rec result carries the image label, not the class id`() {
        val message = MessageParser.parse(
            """{"cat":"image-rec","value":{"obstacle_id":"20","conf":0.92,"xyxy":[1.9,1050.6,409.4,1743.3],"image_id":"A"}}""",
        )
        assertTrue(message is IncomingMessage.ImageRecognised)
        assertEquals("A", (message as IncomingMessage.ImageRecognised).targetId)
    }

    @Test
    fun `rejects an image-rec missing the image label`() {
        assertTrue(
            MessageParser.parse("""{"cat":"image-rec","value":{"obstacle_id":"20"}}""") is IncomingMessage.Unknown,
        )
    }

    @Test
    fun `a capturing announcement carries the obstacle number`() {
        val message = MessageParser.parse("""{"cat":"info","value":"Capturing image for obstacle id: 3"}""")
        message as IncomingMessage.Status
        assertEquals("Capturing image for obstacle id: 3", message.text)
        assertEquals(3, message.capturingObstacleId)
    }

    @Test
    fun `an ordinary status message carries no obstacle number`() {
        val message = MessageParser.parse("""{"cat":"info","value":"Moving"}""")
        assertNull((message as IncomingMessage.Status).capturingObstacleId)
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
}
