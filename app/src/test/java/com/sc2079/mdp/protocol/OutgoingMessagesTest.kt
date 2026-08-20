package com.sc2079.mdp.protocol

import com.sc2079.mdp.model.Direction
import com.sc2079.mdp.model.Obstacle
import com.sc2079.mdp.model.Robot
import org.junit.Assert.assertEquals
import org.junit.Test

class OutgoingMessagesTest {

    @Test
    fun `add uses the format from the briefing slides`() {
        assertEquals("ADD,B1,(10,6)", OutgoingMessages.addObstacle(Obstacle(1, 10, 6)))
    }

    @Test
    fun `remove uses the format from the briefing slides`() {
        assertEquals("SUB,B1", OutgoingMessages.removeObstacle(1))
    }

    @Test
    fun `face annotation names the obstacle and the direction`() {
        assertEquals("FACE,B2,N", OutgoingMessages.targetFace(2, Direction.NORTH))
        assertEquals("FACE,B2,NONE", OutgoingMessages.targetFace(2, null))
    }

    @Test
    fun `robot pose round trips through the parser`() {
        val robot = Robot(7, 2, Direction.WEST)
        val encoded = OutgoingMessages.robotPose(robot)
        assertEquals("ROBOT,7,2,W", encoded)
        val decoded = MessageParser.parse(encoded) as IncomingMessage.RobotUpdate
        assertEquals(robot.x, decoded.x)
        assertEquals(robot.y, decoded.y)
        assertEquals(robot.facing, decoded.facing)
    }

    @Test
    fun `full map emits a face line only for annotated obstacles`() {
        val obstacles = listOf(
            Obstacle(2, 3, 4, targetFace = Direction.SOUTH),
            Obstacle(1, 10, 6),
        )
        assertEquals(
            listOf("ADD,B1,(10,6)", "ADD,B2,(3,4)", "FACE,B2,S"),
            OutgoingMessages.fullMap(obstacles),
        )
    }
}
