package com.sc2079.mdp.model

/**
 * Immutable snapshot of everything the map draws: the arena size, the obstacles
 * that have been placed, and the robot pose.
 *
 * The class is deliberately free of Android types so the placement rules can be
 * covered by plain JVM unit tests.
 */
data class Arena(
    val columns: Int = DEFAULT_SIZE,
    val rows: Int = DEFAULT_SIZE,
    val obstacles: List<Obstacle> = emptyList(),
    val robot: Robot = START_ROBOT,
) {

    fun isInside(x: Int, y: Int): Boolean = x in 0 until columns && y in 0 until rows

    fun obstacleAt(x: Int, y: Int): Obstacle? = obstacles.firstOrNull { it.x == x && it.y == y }

    fun obstacleById(id: Int): Obstacle? = obstacles.firstOrNull { it.id == id }

    /**
     * The number handed to the next obstacle. Numbers are reused once an obstacle
     * has been dragged off the map so the labels stay short during a run.
     */
    fun nextObstacleId(): Int {
        val used = obstacles.map { it.id }.toSet()
        var candidate = 1
        while (candidate in used) candidate++
        return candidate
    }

    /**
     * Adds an obstacle at ([x], [y]) and returns the new arena together with the
     * obstacle that was created, or `null` when the cell is outside the arena or
     * already occupied.
     */
    fun addObstacle(x: Int, y: Int): Pair<Arena, Obstacle>? {
        if (!isInside(x, y) || obstacleAt(x, y) != null) return null
        val obstacle = Obstacle(id = nextObstacleId(), x = x, y = y)
        return copy(obstacles = obstacles + obstacle) to obstacle
    }

    /**
     * Moves obstacle [id] to ([x], [y]). Returns the unchanged arena when the
     * destination is outside the arena or taken by a different obstacle, so a
     * clumsy drag never silently destroys a placement.
     */
    fun moveObstacle(id: Int, x: Int, y: Int): Arena {
        val existing = obstacleById(id) ?: return this
        if (!isInside(x, y)) return this
        val occupant = obstacleAt(x, y)
        if (occupant != null && occupant.id != id) return this
        return replace(existing.copy(x = x, y = y))
    }

    fun removeObstacle(id: Int): Arena = copy(obstacles = obstacles.filterNot { it.id == id })

    fun setTargetFace(id: Int, face: Direction?): Arena {
        val existing = obstacleById(id) ?: return this
        return replace(existing.copy(targetFace = face))
    }

    /**
     * Applies an image-recognition result from the RPi. A `null` [face] keeps whatever
     * face the user already annotated (checklist C.9 allows the face to be omitted).
     */
    fun setTargetId(id: Int, targetId: String?, face: Direction? = null): Arena {
        val existing = obstacleById(id) ?: return this
        return replace(existing.copy(targetId = targetId, targetFace = face ?: existing.targetFace))
    }

    fun withRobot(x: Int, y: Int, facing: Direction): Arena =
        copy(robot = Robot(x.coerceIn(0, columns - 1), y.coerceIn(0, rows - 1), facing))

    /** Empties the map and puts the robot back at [START_ROBOT]. */
    fun cleared(): Arena = copy(obstacles = emptyList(), robot = START_ROBOT)

    /**
     * Readies the same layout for another run: obstacles keep their positions
     * and annotated faces, but every image result is forgotten and the robot
     * goes back to [START_ROBOT].
     */
    fun resetForNewRun(): Arena =
        copy(obstacles = obstacles.map { it.copy(targetId = null) }, robot = START_ROBOT)

    private fun replace(obstacle: Obstacle): Arena =
        copy(obstacles = obstacles.map { if (it.id == obstacle.id) obstacle else it })

    companion object {
        /** The SC2079 competition arena is a 20 x 20 grid of 10 cm cells. */
        const val DEFAULT_SIZE = 20

        /** Bottom-left corner facing north: the 3x3 footprint covers cells 0-2 on both axes. */
        val START_ROBOT = Robot(1, 1, Direction.NORTH)
    }
}
