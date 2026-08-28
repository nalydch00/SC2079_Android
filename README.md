# SC2079 MDP — Android Remote Controller

Android tablet remote controller for the SC2079 / CE-CZ3004 Multi-disciplinary
Design Project. The app is the team's wireless console: it drives the robot over
a Bluetooth serial link, draws the arena, and visualises what the robot reports
back.

Written in Kotlin with Android Views. Open the repository root in **Android
Studio** (`File → Open`), let it sync, and run the `app` configuration on a
tablet.

The app is two screens, kept deliberately separate for a focused interface at
each step:

1. **Connect** (`ui/ConnectionActivity.kt`, launcher) — a plain screen whose only
   job is getting a Bluetooth link up: status, Connect, Reconnect, nothing else.
   It hands off automatically the moment the link connects. Has its own
   landscape layout (side-by-side instead of stacked) so nothing gets clipped
   on a wide, short tablet screen.
2. **Control** (`ui/ControlActivity.kt`) — the arena map plus a tabbed control
   panel, reached only once connected. Designed for landscape (how the tablet
   is normally mounted during a run), though portrait still works. A link drop
   here shows "Reconnecting…" in the toolbar without leaving the screen —
   `BluetoothController` keeps retrying in the background (checklist C.8) — and
   **Disconnect** in the overflow menu is the explicit way back to the Connect
   screen.

   The panel is three fixed tabs instead of one long scrolling list, so nothing
   needs to be scrolled to reach:
   - **Control** — robot status plus the movement D-pad; what you touch while
     actually driving.
   - **Obstacles** — target-face annotation plus map-wide actions (Send all,
     Clear map, …); used while setting the arena up.
   - **Log** — free-text send plus the raw traffic log; for proving connectivity
     with the AMD tool.

   The arena map itself is not part of the panel and stays visible regardless
   of which tab is selected.

| | |
|---|---|
| Language | Kotlin 1.9.24 |
| Build | Android Gradle Plugin 8.5.2, Gradle 8.7 (wrapper included) |
| minSdk / targetSdk | 26 / 34 |
| Package | `com.sc2079.mdp` |

## Deliverable checklist coverage

| Item | Requirement | Where it lives |
|---|---|---|
| **C.1** | Transmit and receive text over the Bluetooth serial link | `bluetooth/BluetoothController.kt`; the *Serial* box in the control panel sends free text, and everything received appears in the *Raw traffic* log |
| **C.2** | GUI scanning, selection and connection | The **Connect** screen (`ui/ConnectionActivity.kt`) → `bluetooth/DeviceListDialogFragment.kt` (paired devices listed immediately, **Scan** appends discovered ones) |
| **C.3** | Interactive control of robot movement | A TV-remote style arrow D-pad on the Control screen: Forward/Stop/Reverse down the centre, Turn left/right and Reverse left/right filling the full height on either side (no dead space) - six directions including diagonals, plus Stop |
| **C.4** | Remote update & status messages | The bold **Robot status** box. It shows only recognised status/robot/target events; unrecognised traffic goes to the separate raw log |
| **C.5** | 2D arena display with numbered obstacles and the robot | `ui/ArenaView.kt` — 20 × 20 grid with axis labels, obstacle numbers in small white text, robot drawn over its 3 × 3 footprint with a direction arrow |
| **C.6** | Interactive placement and movement of obstacles | Tap an empty cell to add; drag to move; drag off the arena to delete. `ADD` / `SUB` transmitted when the finger lifts |
| **C.7** | Annotate the obstacle face carrying the target | Tap an edge of an obstacle to set that face (middle clears it); or press and hold it to light up four N/E/S/W zones around it, then slide onto one without lifting and release to pick it - a bigger, friendlier target than the edge itself for small blocks; or use the N/E/S/W buttons arranged in a compass cross under *Selected obstacle*. `FACE` transmitted each time |
| **C.8** | Robust connectivity, automatic re-establishment | `BluetoothController` runs a retrying client loop **and** an RFCOMM server socket at the same time, so the link comes back whether the tablet or the robot re-initiates |
| **C.9** | Display image target ID on obstacle blocks | `TARGET,...` repaints the block green with the target ID in large white text, plus a thick red bar on the target face |
| **C.10** | Update robot position and facing direction | `ROBOT,<x>,<y>,<dir>` moves and rotates the robot icon |

## Message protocol

Coordinates use a bottom-left origin: `(0,0)` is the bottom-left cell, `x` grows
east, `y` grows north. Every transmitted line is terminated with `\n`.

### Received from the robot

| Message | Effect |
|---|---|
| `ROBOT,<x>,<y>,<dir>` | Move the robot to `(x,y)` facing `dir` (`N`/`S`/`E`/`W`) |
| `TARGET,<obstacle>,<targetId>` | Show `targetId` on that obstacle block |
| `TARGET,<obstacle>,<targetId>,<face>` | …and mark `face` as the target face |
| `STATUS,<text>` / `MSG,[<text>]` | Show `text` in the status box |

The obstacle number is accepted both bare (`2`) and prefixed (`B2`), headings
are case-insensitive, and surrounding whitespace or brackets are ignored.
Anything else is kept in the raw log and never reaches the status box, which is
what C.4 asks for.

### Transmitted by the tablet

| Message | Sent when |
|---|---|
| `ADD,B<n>,(<x>,<y>)` | Obstacle `n` is placed, or a drag finishes |
| `SUB,B<n>` | Obstacle `n` is dragged off the arena |
| `FACE,B<n>,<face>` | A target face is annotated (`FACE,B<n>,NONE` clears it) |
| Movement tokens | A movement button is pressed |

Movement tokens default to `f`, `r`, `tl`, `tr`, `rl`, `rr`, `s`, plus `START`
and `FASTEST`. Every one of them is editable at runtime from the overflow menu
(**Movement commands**), so the app can match whatever the RPi team settles on
without a rebuild.

## Using the map

| Gesture | Result |
|---|---|
| Tap an empty cell | Add the next numbered obstacle there |
| Drag an obstacle | Move it; drop it outside the arena to delete it |
| Tap an obstacle's edge | Set that face as the target face |
| Tap an obstacle's middle | Clear its target face |
| Press and hold an obstacle, then slide onto a lit zone and release | Set the target face to whichever zone (N/E/S/W) you released on |
| Drag the robot | Reposition the robot |
| Long-press an empty cell | Drop the robot there |

Obstacle numbers are reused after a deletion, so the labels stay short during a
run.

## Testing against the AMD tool

1. Pair the tablet with the machine running the Android Module Tool.
2. Tap **Connect** and pick the device (or tap **Reconnect** to reuse the last
   one). The state banner turns green on success.
3. Send a line from the tool — `MSG,[Ready to start]`, `ROBOT,7,2,N`,
   `TARGET,B2,11,N` — and watch the status box and map update.
4. Press the movement buttons and watch the tool's command log.
5. For C.8, hit **Disconnect** in the AMD tool. The banner turns orange
   (`RECONNECTING…`) and the app stays responsive; connect again from the tool
   and the link comes back on its own.

## Project layout

```
app/src/main/java/com/sc2079/mdp/
├── bluetooth/    RFCOMM link, connection state, device picker
├── model/        Arena, Obstacle, Robot, Direction — pure Kotlin, unit tested
├── protocol/     Message parsing and formatting — pure Kotlin, unit tested
├── ui/           ConnectionActivity, ControlActivity, MainViewModel, ArenaView, dialogs
└── util/         Runtime permissions, SharedPreferences
```

The `model` and `protocol` packages deliberately avoid Android types so the
placement rules and the wire format are covered by plain JVM tests:

```
./gradlew test
```

## Permissions

Android 12 and above ask for `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` at
runtime; Android 11 and below use `BLUETOOTH`, `BLUETOOTH_ADMIN` and
`ACCESS_FINE_LOCATION` (discovery counted as a location request back then). The
app requests whichever set applies the first time you tap **Connect**.
