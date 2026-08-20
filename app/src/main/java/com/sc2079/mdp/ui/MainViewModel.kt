package com.sc2079.mdp.ui

import androidx.lifecycle.ViewModel
import com.sc2079.mdp.model.Arena
import com.sc2079.mdp.model.Direction
import com.sc2079.mdp.model.Obstacle
import com.sc2079.mdp.protocol.IncomingMessage
import com.sc2079.mdp.protocol.MessageParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the arena, the status line and the traffic log across configuration
 * changes, and applies incoming Bluetooth messages to the map.
 */
class MainViewModel : ViewModel() {

    private val _arena = MutableStateFlow(Arena())
    val arena: StateFlow<Arena> = _arena.asStateFlow()

    private val _status = MutableStateFlow(DEFAULT_STATUS)

    /** The selective status text shown in the message box (checklist C.4). */
    val status: StateFlow<String> = _status.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())

    /** Full raw traffic, kept separate from the status box. */
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _selectedObstacleId = MutableStateFlow<Int?>(null)
    val selectedObstacleId: StateFlow<Int?> = _selectedObstacleId.asStateFlow()

    fun select(id: Int?) {
        _selectedObstacleId.value = id
    }

    fun selectedObstacle(): Obstacle? =
        _selectedObstacleId.value?.let { _arena.value.obstacleById(it) }

    fun addObstacle(x: Int, y: Int): Obstacle? {
        val (updated, obstacle) = _arena.value.addObstacle(x, y) ?: return null
        _arena.value = updated
        _selectedObstacleId.value = obstacle.id
        return obstacle
    }

    /** Returns the obstacle at its new home, or `null` when the move was rejected. */
    fun moveObstacle(id: Int, x: Int, y: Int): Obstacle? {
        val updated = _arena.value.moveObstacle(id, x, y)
        _arena.value = updated
        return updated.obstacleById(id)?.takeIf { it.x == x && it.y == y }
    }

    fun removeObstacle(id: Int) {
        _arena.value = _arena.value.removeObstacle(id)
        if (_selectedObstacleId.value == id) _selectedObstacleId.value = null
    }

    fun setTargetFace(id: Int, face: Direction?) {
        _arena.value = _arena.value.setTargetFace(id, face)
    }

    fun moveRobot(x: Int, y: Int) {
        _arena.value = _arena.value.withRobot(x, y, _arena.value.robot.facing)
    }

    fun turnRobot(clockwise: Boolean) {
        val robot = _arena.value.robot
        val facing = if (clockwise) robot.facing.turnRight() else robot.facing.turnLeft()
        _arena.value = _arena.value.withRobot(robot.x, robot.y, facing)
    }

    fun clearArena() {
        _arena.value = _arena.value.cleared()
        _selectedObstacleId.value = null
        setStatus(DEFAULT_STATUS)
    }

    fun resizeArena(columns: Int, rows: Int) {
        _arena.value = Arena(columns = columns, rows = rows)
        _selectedObstacleId.value = null
    }

    fun setStatus(text: String) {
        _status.value = text
    }

    fun logLine(prefix: String, text: String) {
        val entry = "$prefix $text"
        _log.value = (_log.value + entry).takeLast(MAX_LOG_LINES)
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    /**
     * Applies one received line to the map and returns what it was recognised as,
     * so the activity can decide what belongs in the status box.
     */
    fun applyIncoming(line: String): IncomingMessage {
        val message = MessageParser.parse(line)
        when (message) {
            is IncomingMessage.RobotUpdate ->
                _arena.value = _arena.value.withRobot(message.x, message.y, message.facing)

            is IncomingMessage.TargetUpdate ->
                _arena.value = _arena.value.setTargetId(message.obstacleId, message.targetId, message.face)

            is IncomingMessage.Status -> Unit
            is IncomingMessage.Unknown -> Unit
        }
        return message
    }

    private companion object {
        const val DEFAULT_STATUS = "Ready"
        const val MAX_LOG_LINES = 250
    }
}
