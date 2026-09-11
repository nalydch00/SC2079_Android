package com.sc2079.mdp.protocol

import com.sc2079.mdp.model.Direction
import com.sc2079.mdp.model.Obstacle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class OutgoingMessagesTest {

    @Test
    fun `obstacles message matches the team's cat-value schema`() {
        val json = JSONObject(OutgoingMessages.obstaclesMessage(listOf(Obstacle(1, 5, 10)), "0"))
        assertEquals("obstacles", json.getString("cat"))
        val value = json.getJSONObject("value")
        assertEquals("0", value.getString("mode"))
        val obstacle = value.getJSONArray("obstacles").getJSONObject(0)
        assertEquals(5, obstacle.getInt("x"))
        assertEquals(10, obstacle.getInt("y"))
        assertEquals(1, obstacle.getInt("id"))
        assertEquals(OutgoingMessages.NO_FACE_CODE, obstacle.getInt("d"))
    }

    @Test
    fun `obstacles message sorts by id and carries every obstacle`() {
        val obstacles = listOf(Obstacle(2, 3, 4, targetFace = Direction.SOUTH), Obstacle(1, 10, 6))
        val value = JSONObject(OutgoingMessages.obstaclesMessage(obstacles, "1")).getJSONObject("value")
        val array = value.getJSONArray("obstacles")
        assertEquals(2, array.length())
        assertEquals(1, array.getJSONObject(0).getInt("id"))
        assertEquals(2, array.getJSONObject(1).getInt("id"))
        assertEquals("1", value.getString("mode"))
    }

    @Test
    fun `direction codes follow the N=0 E=2 S=4 W=6 convention`() {
        assertEquals(0, OutgoingMessages.directionCode(Direction.NORTH))
        assertEquals(2, OutgoingMessages.directionCode(Direction.EAST))
        assertEquals(4, OutgoingMessages.directionCode(Direction.SOUTH))
        assertEquals(6, OutgoingMessages.directionCode(Direction.WEST))
    }

    @Test
    fun `an annotated face is carried as the d field`() {
        val value = JSONObject(
            OutgoingMessages.obstaclesMessage(listOf(Obstacle(1, 0, 0, targetFace = Direction.EAST)), "0"),
        ).getJSONObject("value")
        assertEquals(2, value.getJSONArray("obstacles").getJSONObject(0).getInt("d"))
    }

    @Test
    fun `an empty obstacle list still sends a valid message`() {
        val value = JSONObject(OutgoingMessages.obstaclesMessage(emptyList(), "0")).getJSONObject("value")
        assertEquals(0, value.getJSONArray("obstacles").length())
    }

    @Test
    fun `control message wraps the trigger word`() {
        val json = JSONObject(OutgoingMessages.controlMessage("start"))
        assertEquals("control", json.getString("cat"))
        assertEquals("start", json.getString("value"))
    }

    @Test
    fun `manual message wraps the configured STM command string`() {
        val json = JSONObject(OutgoingMessages.manualMessage("FW01"))
        assertEquals("manual", json.getString("cat"))
        assertEquals("FW01", json.getString("value"))
    }
}
