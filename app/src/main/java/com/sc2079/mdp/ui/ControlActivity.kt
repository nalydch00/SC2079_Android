package com.sc2079.mdp.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sc2079.mdp.R
import com.sc2079.mdp.bluetooth.BluetoothController
import com.sc2079.mdp.bluetooth.ConnectionState
import com.sc2079.mdp.databinding.ActivityControlBinding
import com.sc2079.mdp.model.Arena
import com.sc2079.mdp.model.Direction
import com.sc2079.mdp.model.Obstacle
import com.sc2079.mdp.protocol.IncomingMessage
import com.sc2079.mdp.protocol.OutgoingMessages
import com.sc2079.mdp.util.Prefs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The robot control screen: arena map plus the D-pad, obstacle and status
 * controls. Only reached once [ConnectionActivity] has a live link - this
 * screen assumes Bluetooth is already connected and never drives the connect
 * flow itself.
 *
 * Every checklist item is reachable from here - see the README for the mapping.
 */
class ControlActivity : AppCompatActivity(), ArenaView.Listener {

    private lateinit var binding: ActivityControlBinding
    private lateinit var bluetooth: BluetoothController
    private lateinit var prefs: Prefs

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityControlBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        bluetooth = BluetoothController.getInstance(this)
        prefs = Prefs(this)

        binding.arenaView.listener = this
        wireMovementButtons()
        wireObstaclePanel()
        wireArenaActions()
        wireSerialPanel()
        observeState()
    }

    // ---------------------------------------------------------------- wiring

    /** Checklist C.3: a D-pad of arrow buttons, each transmitting over Bluetooth. */
    private fun wireMovementButtons() = with(binding.controls) {
        btnForward.setOnClickListener { sendCommand(Prefs.CommandAction.FORWARD) }
        btnReverse.setOnClickListener { sendCommand(Prefs.CommandAction.REVERSE) }
        btnTurnLeft.setOnClickListener { sendCommand(Prefs.CommandAction.TURN_LEFT) }
        btnTurnRight.setOnClickListener { sendCommand(Prefs.CommandAction.TURN_RIGHT) }
        btnReverseLeft.setOnClickListener { sendCommand(Prefs.CommandAction.REVERSE_LEFT) }
        btnReverseRight.setOnClickListener { sendCommand(Prefs.CommandAction.REVERSE_RIGHT) }
        btnStop.setOnClickListener { sendCommand(Prefs.CommandAction.STOP) }
    }

    /** Checklist C.7: the panel mirror of the tap-an-edge gesture on the map. */
    private fun wireObstaclePanel() = with(binding.controls) {
        faceN.setOnClickListener { applyFaceToSelection(Direction.NORTH) }
        faceE.setOnClickListener { applyFaceToSelection(Direction.EAST) }
        faceS.setOnClickListener { applyFaceToSelection(Direction.SOUTH) }
        faceW.setOnClickListener { applyFaceToSelection(Direction.WEST) }
        faceClear.setOnClickListener { applyFaceToSelection(null) }
    }

    private fun wireArenaActions() = with(binding.controls) {
        btnSendMap.setOnClickListener {
            val obstacles = viewModel.arena.value.obstacles
            if (obstacles.isEmpty()) {
                toast("No obstacles on the map")
                return@setOnClickListener
            }
            OutgoingMessages.fullMap(obstacles).forEach(::transmit)
        }
        btnClearMap.setOnClickListener {
            viewModel.arena.value.obstacles.forEach { transmit(OutgoingMessages.removeObstacle(it.id)) }
            viewModel.clearArena()
        }
        btnStartImage.setOnClickListener { sendCommand(Prefs.CommandAction.START_IMAGE) }
        btnStartFastest.setOnClickListener { sendCommand(Prefs.CommandAction.START_FASTEST) }
    }

    private fun wireSerialPanel() = with(binding.controls) {
        btnSendText.setOnClickListener { sendTypedMessage() }
        inputMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTypedMessage()
                true
            } else {
                false
            }
        }
        inputMessage.doAfterTextChanged { btnSendText.isEnabled = !it.isNullOrBlank() }
        btnSendText.isEnabled = false
        btnClearLog.setOnClickListener { viewModel.clearLog() }
    }

    private fun sendTypedMessage() {
        val text = binding.controls.inputMessage.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        transmit(text)
        binding.controls.inputMessage.setText("")
    }

    // ------------------------------------------------------------ observers

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.arena.collectLatest(::renderArena) }
                launch { viewModel.status.collectLatest { binding.controls.statusText.text = it } }
                launch { viewModel.log.collectLatest(::renderLog) }
                launch { viewModel.selectedObstacleId.collectLatest { renderSelection() } }
                launch { bluetooth.state.collectLatest(::renderToolbarSubtitle) }
                launch { bluetooth.remoteName.collectLatest { renderToolbarSubtitle(bluetooth.state.value) } }
                launch { bluetooth.incoming.collect(::onIncomingLine) }
                launch { bluetooth.events.collect(::onLinkEvent) }
            }
        }
    }

    private fun renderArena(arena: Arena) {
        binding.arenaView.arena = arena
        binding.controls.robotPose.text = getString(
            R.string.robot_pose,
            arena.robot.x,
            arena.robot.y,
            arena.robot.facing.code,
        )
        renderSelection()
    }

    private fun renderSelection() {
        val obstacle = viewModel.selectedObstacle()
        binding.arenaView.selectedObstacleId = obstacle?.id
        binding.controls.selectedObstacle.text = if (obstacle == null) {
            getString(R.string.no_obstacle_selected)
        } else {
            getString(
                R.string.obstacle_summary,
                obstacle.id,
                obstacle.x,
                obstacle.y,
                obstacle.targetFace?.code ?: getString(R.string.face_unset),
            )
        }
    }

    private fun renderLog(lines: List<String>) {
        binding.controls.logText.text =
            if (lines.isEmpty()) getString(R.string.log_empty) else lines.takeLast(LOG_LINES_SHOWN).joinToString("\n")
    }

    /**
     * This screen assumes the link is already up, so its only job re: connection
     * state is a small toolbar subtitle - full connect/reconnect UI lives on
     * [ConnectionActivity]. A drop mid-run shows "Reconnecting..." here without
     * kicking the user off the map (checklist C.8): [BluetoothController] keeps
     * retrying in the background on its own.
     */
    private fun renderToolbarSubtitle(state: ConnectionState) {
        binding.toolbar.subtitle = when (state) {
            ConnectionState.CONNECTED ->
                getString(R.string.toolbar_subtitle_connected, bluetooth.remoteName.value ?: getString(R.string.no_device))
            ConnectionState.RECONNECTING, ConnectionState.CONNECTING ->
                getString(R.string.toolbar_subtitle_reconnecting)
            ConnectionState.DISCONNECTED -> null
        }
    }

    /** Checklist C.1, C.4, C.9, C.10: one received line, applied to the GUI. */
    private fun onIncomingLine(line: String) {
        viewModel.logLine("<<", line)
        when (val message = viewModel.applyIncoming(line)) {
            is IncomingMessage.Status -> viewModel.setStatus(message.text)
            is IncomingMessage.RobotUpdate -> viewModel.setStatus(
                "Robot at (${message.x}, ${message.y}) facing ${message.facing.code}",
            )
            is IncomingMessage.TargetUpdate -> viewModel.setStatus(
                "Obstacle ${message.obstacleId}: target ${message.targetId}" +
                    (message.face?.let { " on ${it.code}" } ?: ""),
            )
            // Unrecognised traffic stays in the raw log only, so the status box
            // keeps showing selective information (checklist C.4).
            is IncomingMessage.Unknown -> Unit
        }
    }

    private fun onLinkEvent(message: String) {
        viewModel.logLine("--", message)
        toast(message)
    }

    // ---------------------------------------------------------- map callbacks

    override fun onObstacleAddRequested(x: Int, y: Int) {
        val obstacle = viewModel.addObstacle(x, y)
        if (obstacle == null) {
            toast(getString(R.string.cell_occupied))
            return
        }
        transmit(OutgoingMessages.addObstacle(obstacle))
    }

    override fun onObstacleMoved(id: Int, x: Int, y: Int) {
        val moved = viewModel.moveObstacle(id, x, y)
        if (moved == null) {
            toast(getString(R.string.cell_occupied))
            return
        }
        transmit(OutgoingMessages.addObstacle(moved))
    }

    override fun onObstacleRemoved(id: Int) {
        viewModel.removeObstacle(id)
        transmit(OutgoingMessages.removeObstacle(id))
    }

    override fun onTargetFaceChanged(id: Int, face: Direction?) {
        viewModel.setTargetFace(id, face)
        transmit(OutgoingMessages.targetFace(id, face))
    }

    override fun onObstacleSelected(obstacle: Obstacle?) {
        viewModel.select(obstacle?.id)
    }

    override fun onObstacleLongPressed(obstacle: Obstacle) {
        viewModel.select(obstacle.id)
        FacePickerDialog.show(this, obstacle) { face -> onTargetFaceChanged(obstacle.id, face) }
    }

    override fun onRobotMoved(x: Int, y: Int) {
        viewModel.moveRobot(x, y)
    }

    // ------------------------------------------------------------------ misc

    private fun sendCommand(action: Prefs.CommandAction) {
        transmit(prefs.command(action))
    }

    private fun applyFaceToSelection(face: Direction?) {
        val obstacle = viewModel.selectedObstacle()
        if (obstacle == null) {
            toast(getString(R.string.no_obstacle_selected))
            return
        }
        onTargetFaceChanged(obstacle.id, face)
    }

    /** Sends [text] and mirrors it into the raw log. */
    private fun transmit(text: String) {
        if (bluetooth.send(text)) viewModel.logLine(">>", text)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_disconnect -> {
            bluetooth.disconnect()
            startActivity(Intent(this, ConnectionActivity::class.java))
            finish()
            true
        }

        R.id.action_commands -> {
            CommandSettingsDialog.show(this, prefs) { toast("Movement commands updated") }
            true
        }

        R.id.action_arena -> {
            val arena = viewModel.arena.value
            ArenaSizeDialog.show(this, arena.columns, arena.rows) { columns, rows ->
                viewModel.resizeArena(columns, rows)
            }
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private companion object {
        const val LOG_LINES_SHOWN = 12
    }
}
