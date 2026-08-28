package com.sc2079.mdp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.sc2079.mdp.model.Arena
import com.sc2079.mdp.model.Direction
import com.sc2079.mdp.model.Obstacle
import com.sc2079.mdp.model.Robot
import com.sc2079.mdp.model.faceForOffset
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * The 2D arena map (checklist C.5) and every touch interaction on it.
 *
 * Interactions:
 *  - tap an empty cell to add the next numbered obstacle (C.6)
 *  - drag an obstacle to move it, or drag it off the arena to delete it (C.6)
 *  - tap one of the four edges of an obstacle to annotate the target face, and
 *    the middle of the block to clear it (C.7)
 *  - press and hold an obstacle to light up four N/E/S/W zones around it, then
 *    slide onto one and lift to pick that face - without lifting, so it reads
 *    as one continuous gesture, useful when the block itself is too small to
 *    touch an edge of reliably (C.7's own suggested fallback)
 *  - drag the robot to reposition it, long press an empty cell to drop it there
 *
 * The view never mutates the arena itself. It reports intent through
 * [listener] and re-draws whatever [arena] it is given, so the activity stays
 * the single source of truth for what gets transmitted over Bluetooth.
 */
class ArenaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Callbacks for everything the user does on the map. */
    interface Listener {
        fun onObstacleAddRequested(x: Int, y: Int)
        fun onObstacleMoved(id: Int, x: Int, y: Int)
        fun onObstacleRemoved(id: Int)
        fun onTargetFaceChanged(id: Int, face: Direction?)
        fun onObstacleSelected(obstacle: Obstacle?)
        fun onRobotMoved(x: Int, y: Int)
    }

    var listener: Listener? = null

    var arena: Arena = Arena()
        set(value) {
            field = value
            if (selectedObstacleId != null && value.obstacleById(selectedObstacleId!!) == null) {
                selectedObstacleId = null
            }
            recomputeGeometry()
            invalidate()
        }

    var selectedObstacleId: Int? = null
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density

    private val arenaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_ARENA
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_GRID
        strokeWidth = 1f * density
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_BORDER
        strokeWidth = 2f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_LABEL
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val obstaclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val obstacleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_SELECTION
        strokeWidth = 3f * density
    }
    private val robotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_ROBOT
    }
    private val robotHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_ROBOT_HEAD
    }
    private val facePickerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val facePickerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private var cellSize = 0f
    private var gutter = 0f
    private var gridLeft = 0f
    private var gridTop = 0f

    private val cellRect = RectF()
    private val robotPath = Path()

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragObstacleId: Int? = null
    private var dragRobot = false
    private var dragging = false
    private var gestureConsumed = false
    private var dragCellX = 0
    private var dragCellY = 0
    private var dragOutside = false

    /**
     * Set once a long press on an obstacle fires, and cleared on lift/cancel.
     * While non-null, [handleMove] tracks which of the four lit zones the
     * finger is over instead of dragging, and [handleUp] commits whichever
     * zone (if any) it last landed on.
     */
    private var facePickObstacleId: Int? = null
    private var facePickHoverDirection: Direction? = null
    private var facePickCenterX = 0f
    private var facePickCenterY = 0f

    private val longPressRunnable = Runnable { fireLongPress() }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeGeometry()
    }

    /**
     * One extra cell of width and height is reserved for the axis labels, so the
     * grid always keeps square cells whatever aspect ratio the view is given.
     */
    private fun recomputeGeometry() {
        val availableWidth = (width - paddingLeft - paddingRight).toFloat()
        val availableHeight = (height - paddingTop - paddingBottom).toFloat()
        if (availableWidth <= 0f || availableHeight <= 0f) return
        cellSize = min(
            availableWidth / (arena.columns + 1),
            availableHeight / (arena.rows + 1),
        )
        gutter = cellSize
        val gridWidth = cellSize * arena.columns
        val gridHeight = cellSize * arena.rows
        gridLeft = paddingLeft + gutter + (availableWidth - gutter - gridWidth) / 2f
        gridTop = paddingTop + (availableHeight - gutter - gridHeight) / 2f
        labelPaint.textSize = cellSize * 0.42f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cellSize <= 0f) return

        val gridRight = gridLeft + cellSize * arena.columns
        val gridBottom = gridTop + cellSize * arena.rows

        canvas.drawRect(gridLeft, gridTop, gridRight, gridBottom, arenaPaint)
        drawGrid(canvas, gridRight, gridBottom)
        drawAxisLabels(canvas, gridBottom)
        drawRobot(canvas)
        arena.obstacles.forEach { obstacle ->
            if (obstacle.id == dragObstacleId && dragging) return@forEach
            drawObstacle(canvas, obstacle, obstacle.x, obstacle.y, ghost = false)
        }
        drawDragPreview(canvas)
        canvas.drawRect(gridLeft, gridTop, gridRight, gridBottom, borderPaint)
        if (facePickObstacleId != null) drawFacePicker(canvas)
    }

    private fun drawGrid(canvas: Canvas, gridRight: Float, gridBottom: Float) {
        for (column in 0..arena.columns) {
            val x = gridLeft + column * cellSize
            canvas.drawLine(x, gridTop, x, gridBottom, gridPaint)
        }
        for (row in 0..arena.rows) {
            val y = gridTop + row * cellSize
            canvas.drawLine(gridLeft, y, gridRight, y, gridPaint)
        }
    }

    private fun drawAxisLabels(canvas: Canvas, gridBottom: Float) {
        val baselineOffset = labelPaint.textSize * 0.36f
        for (y in 0 until arena.rows) {
            val centerY = gridTop + (arena.rows - 1 - y) * cellSize + cellSize / 2f
            canvas.drawText(y.toString(), gridLeft - gutter / 2f, centerY + baselineOffset, labelPaint)
        }
        for (x in 0 until arena.columns) {
            val centerX = gridLeft + x * cellSize + cellSize / 2f
            canvas.drawText(x.toString(), centerX, gridBottom + gutter * 0.62f, labelPaint)
        }
    }

    private fun drawRobot(canvas: Canvas) {
        val robot = arena.robot
        val half = Robot.FOOTPRINT / 2
        val left = gridLeft + (robot.x - half) * cellSize
        val top = gridTop + (arena.rows - 1 - (robot.y + half)) * cellSize
        val size = Robot.FOOTPRINT * cellSize
        cellRect.set(left, top, left + size, top + size)
        val corner = cellSize * 0.25f
        canvas.drawRoundRect(cellRect, corner, corner, robotPaint)

        // A triangle that points where the robot is facing, so N/S/E/W can be
        // read off the icon at a glance (checklist C.5 and C.10).
        val centerX = cellRect.centerX()
        val centerY = cellRect.centerY()
        val reach = size * 0.36f
        val dirX = robot.facing.dx.toFloat()
        val dirY = -robot.facing.dy.toFloat()
        val tipX = centerX + dirX * reach
        val tipY = centerY + dirY * reach
        val baseX = centerX - dirX * reach * 0.6f
        val baseY = centerY - dirY * reach * 0.6f
        val perpX = -dirY * reach * 0.7f
        val perpY = dirX * reach * 0.7f
        robotPath.reset()
        robotPath.moveTo(tipX, tipY)
        robotPath.lineTo(baseX + perpX, baseY + perpY)
        robotPath.lineTo(baseX - perpX, baseY - perpY)
        robotPath.close()
        canvas.drawPath(robotPath, robotHeadPaint)
    }

    private fun drawDragPreview(canvas: Canvas) {
        val id = dragObstacleId ?: return
        if (!dragging) return
        val obstacle = arena.obstacleById(id) ?: return
        drawObstacle(canvas, obstacle, dragCellX, dragCellY, ghost = dragOutside)
    }

    private fun drawObstacle(canvas: Canvas, obstacle: Obstacle, x: Int, y: Int, ghost: Boolean) {
        val left = gridLeft + x * cellSize
        val top = gridTop + (arena.rows - 1 - y) * cellSize
        cellRect.set(left, top, left + cellSize, top + cellSize)

        obstaclePaint.color = when {
            ghost -> COLOR_OBSTACLE_DELETE
            obstacle.targetId != null -> COLOR_OBSTACLE_FOUND
            else -> COLOR_OBSTACLE
        }
        canvas.drawRect(cellRect, obstaclePaint)

        obstacle.targetFace?.let { face -> drawTargetFace(canvas, face) }

        val targetId = obstacle.targetId
        if (targetId != null) {
            // Target id in large white font, per checklist C.9.
            obstacleTextPaint.textSize = cellSize * 0.66f
            canvas.drawText(
                targetId,
                cellRect.centerX(),
                cellRect.centerY() + obstacleTextPaint.textSize * 0.35f,
                obstacleTextPaint,
            )
        } else {
            // Obstacle number in small white font, per checklist C.5.
            obstacleTextPaint.textSize = cellSize * 0.42f
            canvas.drawText(
                obstacle.id.toString(),
                cellRect.centerX(),
                cellRect.centerY() + obstacleTextPaint.textSize * 0.36f,
                obstacleTextPaint,
            )
        }

        if (obstacle.id == selectedObstacleId && !ghost) {
            canvas.drawRect(cellRect, selectionPaint)
        }
    }

    /** Thick coloured bar along the annotated face (checklist C.7 and C.9). */
    private fun drawTargetFace(canvas: Canvas, face: Direction) {
        facePaint.color = COLOR_TARGET_FACE
        val thickness = cellSize * 0.22f
        when (face) {
            Direction.NORTH -> canvas.drawRect(
                cellRect.left, cellRect.top, cellRect.right, cellRect.top + thickness, facePaint,
            )
            Direction.SOUTH -> canvas.drawRect(
                cellRect.left, cellRect.bottom - thickness, cellRect.right, cellRect.bottom, facePaint,
            )
            Direction.EAST -> canvas.drawRect(
                cellRect.right - thickness, cellRect.top, cellRect.right, cellRect.bottom, facePaint,
            )
            Direction.WEST -> canvas.drawRect(
                cellRect.left, cellRect.top, cellRect.left + thickness, cellRect.bottom, facePaint,
            )
        }
    }

    /**
     * The four lit N/E/S/W zones shown while [facePickObstacleId] is set - a
     * finger held down after a long press can slide onto one of these without
     * lifting, previewed with [facePickHoverDirection] before it is committed.
     */
    private fun drawFacePicker(canvas: Canvas) {
        val near = cellSize * PICKER_NEAR_FRACTION
        val far = cellSize * PICKER_FAR_FRACTION
        val half = cellSize * PICKER_WIDTH_FRACTION / 2f
        val cx = facePickCenterX
        val cy = facePickCenterY
        val corner = 8f * density

        Direction.entries.forEach { direction ->
            val hovered = direction == facePickHoverDirection
            facePickerPaint.color = if (hovered) COLOR_PICKER_HOVER else COLOR_PICKER_IDLE
            when (direction) {
                Direction.NORTH -> cellRect.set(cx - half, cy - far, cx + half, cy - near)
                Direction.SOUTH -> cellRect.set(cx - half, cy + near, cx + half, cy + far)
                Direction.EAST -> cellRect.set(cx + near, cy - half, cx + far, cy + half)
                Direction.WEST -> cellRect.set(cx - far, cy - half, cx - near, cy + half)
            }
            canvas.drawRoundRect(cellRect, corner, corner, facePickerPaint)
            facePickerTextPaint.textSize = half * 0.9f
            canvas.drawText(
                direction.code,
                cellRect.centerX(),
                cellRect.centerY() + facePickerTextPaint.textSize * 0.35f,
                facePickerTextPaint,
            )
        }
    }

    /** Dominant-axis hit test for [drawFacePicker]'s zones, in raw pixels. */
    private fun hoverDirectionFor(dx: Float, dy: Float): Direction? {
        val deadZone = cellSize * PICKER_NEAR_FRACTION
        if (abs(dx) < deadZone && abs(dy) < deadZone) return null
        return if (abs(dx) > abs(dy)) {
            if (dx > 0f) Direction.EAST else Direction.WEST
        } else {
            if (dy > 0f) Direction.SOUTH else Direction.NORTH
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (cellSize <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleDown(event)
            MotionEvent.ACTION_MOVE -> handleMove(event)
            MotionEvent.ACTION_UP -> handleUp(event)
            MotionEvent.ACTION_CANCEL -> resetGesture()
        }
        return true
    }

    private fun handleDown(event: MotionEvent) {
        parent?.requestDisallowInterceptTouchEvent(true)
        downX = event.x
        downY = event.y
        dragging = false
        gestureConsumed = false
        dragOutside = false
        dragObstacleId = null
        dragRobot = false
        facePickObstacleId = null
        facePickHoverDirection = null

        val x = columnAt(event.x)
        val y = rowAt(event.y)
        if (!arena.isInside(x, y)) return

        val obstacle = arena.obstacleAt(x, y)
        when {
            obstacle != null -> {
                dragObstacleId = obstacle.id
                dragCellX = obstacle.x
                dragCellY = obstacle.y
            }
            isRobotCell(x, y) -> dragRobot = true
        }
        postDelayed(longPressRunnable, LONG_PRESS_MS)
    }

    private fun handleMove(event: MotionEvent) {
        if (facePickObstacleId != null) {
            facePickHoverDirection = hoverDirectionFor(event.x - facePickCenterX, event.y - facePickCenterY)
            invalidate()
            return
        }
        if (gestureConsumed) return
        if (!dragging && abs(event.x - downX) < touchSlop && abs(event.y - downY) < touchSlop) return

        if (!dragging) {
            removeCallbacks(longPressRunnable)
            dragging = true
        }
        val x = columnAt(event.x)
        val y = rowAt(event.y)
        when {
            dragObstacleId != null -> {
                dragOutside = !arena.isInside(x, y)
                if (!dragOutside) {
                    dragCellX = x
                    dragCellY = y
                }
                invalidate()
            }
            dragRobot && arena.isInside(x, y) -> {
                listener?.onRobotMoved(x, y)
            }
        }
    }

    private fun handleUp(event: MotionEvent) {
        removeCallbacks(longPressRunnable)
        val pickedObstacleId = facePickObstacleId
        if (pickedObstacleId != null) {
            facePickHoverDirection?.let { listener?.onTargetFaceChanged(pickedObstacleId, it) }
            resetGesture()
            return
        }
        if (gestureConsumed) {
            resetGesture()
            return
        }
        val x = columnAt(event.x)
        val y = rowAt(event.y)
        val id = dragObstacleId

        when {
            dragging && id != null && dragOutside -> listener?.onObstacleRemoved(id)
            dragging && id != null -> listener?.onObstacleMoved(id, dragCellX, dragCellY)
            dragging -> Unit
            id != null -> handleObstacleTap(id, event.x, event.y)
            arena.isInside(x, y) && !isRobotCell(x, y) -> {
                listener?.onObstacleSelected(null)
                listener?.onObstacleAddRequested(x, y)
            }
        }
        resetGesture()
    }

    /** A short tap on an obstacle picks the face nearest the touch (checklist C.7). */
    private fun handleObstacleTap(id: Int, px: Float, py: Float) {
        val obstacle = arena.obstacleById(id) ?: return
        listener?.onObstacleSelected(obstacle)
        val left = gridLeft + obstacle.x * cellSize
        val top = gridTop + (arena.rows - 1 - obstacle.y) * cellSize
        val fx = ((px - left) / cellSize).coerceIn(0f, 1f)
        val fy = 1f - ((py - top) / cellSize).coerceIn(0f, 1f)
        listener?.onTargetFaceChanged(id, faceForOffset(fx, fy))
    }

    private fun fireLongPress() {
        gestureConsumed = true
        val id = dragObstacleId
        val obstacle = id?.let { arena.obstacleById(it) }
        if (id != null && obstacle != null) {
            listener?.onObstacleSelected(obstacle)
            facePickObstacleId = id
            facePickHoverDirection = null
            facePickCenterX = gridLeft + obstacle.x * cellSize + cellSize / 2f
            facePickCenterY = gridTop + (arena.rows - 1 - obstacle.y) * cellSize + cellSize / 2f
            invalidate()
            return
        }
        val x = columnAt(downX)
        val y = rowAt(downY)
        if (arena.isInside(x, y)) listener?.onRobotMoved(x, y)
    }

    private fun resetGesture() {
        removeCallbacks(longPressRunnable)
        dragObstacleId = null
        dragRobot = false
        dragging = false
        dragOutside = false
        gestureConsumed = false
        facePickObstacleId = null
        facePickHoverDirection = null
        invalidate()
    }

    private fun isRobotCell(x: Int, y: Int): Boolean {
        val half = Robot.FOOTPRINT / 2
        return abs(x - arena.robot.x) <= half && abs(y - arena.robot.y) <= half
    }

    private fun columnAt(px: Float): Int = floor((px - gridLeft) / cellSize).toInt()

    private fun rowAt(py: Float): Int =
        arena.rows - 1 - floor((py - gridTop) / cellSize).toInt()

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val LONG_PRESS_MS = 450L

        /** Where the lit N/E/S/W zones sit, as a fraction of one cell's size. */
        const val PICKER_NEAR_FRACTION = 0.65f
        const val PICKER_FAR_FRACTION = 1.35f
        const val PICKER_WIDTH_FRACTION = 0.8f

        val COLOR_ARENA = Color.parseColor("#BFE7F7")
        val COLOR_GRID = Color.parseColor("#FFFFFF")
        val COLOR_BORDER = Color.parseColor("#37474F")
        val COLOR_LABEL = Color.parseColor("#37474F")
        val COLOR_OBSTACLE = Color.parseColor("#3F6FB5")
        val COLOR_OBSTACLE_FOUND = Color.parseColor("#2E7D32")
        val COLOR_OBSTACLE_DELETE = Color.parseColor("#B0BEC5")
        val COLOR_TARGET_FACE = Color.parseColor("#E53935")
        val COLOR_SELECTION = Color.parseColor("#FFC107")
        val COLOR_ROBOT = Color.parseColor("#F0A44C")
        val COLOR_ROBOT_HEAD = Color.parseColor("#37687F")
        val COLOR_PICKER_IDLE = Color.parseColor("#99F0A44C")
        val COLOR_PICKER_HOVER = Color.parseColor("#FFFFC107")
    }
}
