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
| **C.4** | Remote update & status messages | The bold **Robot status** box. Only an `info`/`error`/`status` message can ever change it - `location`, `image-rec`, `mode`, and anything unrecognised all update the map (or nothing) and land in the raw log, but never touch the status box |
| **C.5** | 2D arena display with numbered obstacles and the robot | `ui/ArenaView.kt` — 20 × 20 grid with axis labels, obstacle numbers in small white text, robot drawn over its 3 × 3 footprint with a direction arrow |
| **C.6** | Interactive placement and movement of obstacles | Tap an empty cell to add; drag to move; drag off the arena to delete. An `obstacles` message (the full current map) is transmitted when the finger lifts |
| **C.7** | Annotate the obstacle face carrying the target | Tap an edge of an obstacle to set that face (middle clears it); or press and hold it to light up four N/E/S/W zones around it, then slide onto one without lifting and release to pick it - a bigger, friendlier target than the edge itself for small blocks; or use the N/E/S/W buttons arranged in a compass cross under *Selected obstacle*. An `obstacles` message is transmitted each time, carrying the updated face in that obstacle's `d` field |
| **C.8** | Robust connectivity, automatic re-establishment | `BluetoothController` runs a retrying client loop **and** an RFCOMM server socket at the same time, so the link comes back whether the tablet or the robot re-initiates |
| **C.9** | Display image target ID on obstacle blocks | An `image-rec` message repaints the block green with the target ID in large white text, plus a thick red bar on the target face |
| **C.10** | Update robot position and facing direction | A `location` message moves and rotates the robot icon |

## Message protocol

Coordinates use a bottom-left origin: `(0,0)` is the bottom-left cell, `x` grows
east, `y` grows north. Every line, in both directions, is a single JSON object
with a fixed `cat`/`value` envelope, UTF-8, terminated with `\n` - the RPi
forwards STM32 tokens unchanged and otherwise speaks only this envelope, so
the app never emits or expects the older ARCM plain-text lines
(`ADD,B1,(x,y)`, `ROBOT,...`, `STATUS,"..."`) from earlier drafts of this
protocol.

### Transmitted by the tablet (JSON)

| `cat` | `value` | Sent when |
|---|---|---|
| `obstacles` | `{"obstacles":[{"x","y","id","d"}, …],"mode"}` | Any obstacle is added, moved, removed, or its face (re-)annotated - always the **complete** current map, not a diff; also on **Send all** and before a task starts |
| `control` | `"start"` | **Image rec.** or **Fastest path** is pressed (right after an `obstacles` message carrying the corresponding `mode`) |
| `manual` | an STM command string | A D-pad movement button is pressed |

```json
{"cat":"obstacles","value":{"obstacles":[{"x":5,"y":10,"id":1,"d":2}],"mode":"0"}}
{"cat":"control","value":"start"}
{"cat":"manual","value":"FW010"}
```

Built with `OutgoingMessages.kt` (`org.json`, part of the Android platform - no
extra dependency for the app itself; local unit tests pull in the real
`org.json:json` library, since the stub `android.jar` used for JVM unit tests
throws on real `org.json` calls).

Per-obstacle fields: `x`/`y` are its grid coordinates, `id` its assigned
number, and `d` its annotated target face as **N=0, E=2, S=4, W=6**, or **-1**
when no face has been annotated yet (`OutgoingMessages.NO_FACE_CODE`). `mode`
is `"0"` for Image recognition and `"1"` for Fastest path
(`OutgoingMessages.MODE_IMAGE_RECOGNITION` / `MODE_FASTEST_PATH`), tracked in
`MainViewModel` and set by whichever of the two Start buttons was last pressed.

Movement tokens (the `manual` value) default to the STM32 wire tokens
`FW010`/`BW010`/`TL090`/`TR090`/`BL090`/`BR090`/`STOP` and are editable at
runtime from the overflow menu (**Movement commands**), so the app can match
whatever command strings the STM32 firmware actually expects without a
rebuild - the JSON envelope around them is fixed, only the token content is
configurable. Of these, `FW010`/`BW010`/`TL090`/`TR090`/`STOP` come directly
from the RPi integration brief; `BL090`/`BR090` (reverse-left/reverse-right,
this app's two diagonal-reverse D-pad buttons) aren't covered by that brief
and are this app's own naming, kept consistent with the others - confirm
these two with the STM32/RPi team rather than assuming them. The free-text
**Serial** box (Log tab) sends whatever you type verbatim, unwrapped, for
testing raw connectivity (checklist C.1) against something like the AMD tool.

### Received from the robot (JSON)

| `cat` | `value` | Effect |
|---|---|---|
| `info` / `error` / `status` | `"<text>"` | Show `text` in the status box |
| `location` | `{"x":<int>,"y":<int>,"d":<heading>}` | Move the robot to `(x,y)` facing `d` |
| `image-rec` | `{"obstacle_id":<int>,"image_id":<id>}` | Show `image_id` on that obstacle block |
| `mode` | `"<mode>"` | Recognised, currently no UI effect (optional per the brief) |

```json
{"cat":"status","value":"Ready to start"}
{"cat":"location","value":{"x":7,"y":2,"d":0}}
{"cat":"image-rec","value":{"obstacle_id":2,"image_id":"11"}}
```

`d` (and `image-rec`'s optional `face`) accepts a heading letter (`N`/`E`/`S`/
`W`), this app's own outgoing obstacle-face code (`N=0,E=2,S=4,W=6`), or
degrees (`N=0,E=90,S=180,W=270`). `image-rec`'s field names
(`obstacle_id`/`image_id`) are **this app's assumption**, not something the
brief pinned down precisely - `obstacleId`/`id` and
`imageId`/`target_id`/`targetId` are also accepted, but confirm the RPi's
actual field names with the team and adjust `MessageParser.parseImageRec()`
if they differ. The obstacle number is accepted both bare (`2`) and prefixed
(`B2`).

**Only `info`/`error`/`status` are the messages that can ever change the
status box** - this is enforced in exactly one place,
`MainViewModel.applyIncoming()`, rather than left to whoever reads the parsed
result to remember. `location`, `image-rec`, and `mode` update the map (or
nothing) only; anything that isn't valid JSON, or whose `cat` isn't one of
the above, updates nothing and is kept in the raw log - both are exactly what
checklist C.4's "selective information" asks for. Every value is a real JSON
string, so JSON's own quoting is the terminator: there's no risk of stray
text before or after a status message leaking into the box, the way there
would be with a hand-rolled delimiter.

## Bluetooth pairing

Pair the tablet with the RPi (advertised as **MDP-Group6-RPi**) like any other
Bluetooth device, then pick it from the **Connect** screen's device list.
`BluetoothController`'s outgoing connection attempts try the RPi's own
advertised SPP service UUID (`94f39d29-7d6d-437d-973b-fba39e49d4ee`) before
falling back to the classic Serial Port Profile UUID
(`00001101-0000-1000-8000-00805F9B34FB`) and then a hidden channel-1 socket,
so a device whose SDP record is only registered under the RPi's own UUID
still resolves. STM32 ACK/`INV` traffic is handled entirely on the RPi side;
the tablet only ever sees the RPi's `info`/`error`/`status`/`location`/
`image-rec`/`mode` JSON.

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
3. Send a line from the tool — `{"cat":"status","value":"Ready to start"}`
   updates the status box; `{"cat":"location","value":{"x":7,"y":2,"d":0}}` and
   `{"cat":"image-rec","value":{"obstacle_id":2,"image_id":"11"}}` update the
   map only and leave the status box untouched, by design.
4. Press the movement buttons and watch the tool's command log - each shows up
   as `{"cat":"manual","value":"<token>"}`, not the bare token by itself.
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
placement rules and the wire format are covered by plain JVM tests.
`MainViewModel` (in `ui/`) is tested too, since it's the one place that
decides what may reach the status box - it only depends on `androidx.lifecycle.ViewModel`
and coroutines' `Flow`, neither of which needs an Android runtime to test:

```
./gradlew test
```

## Permissions

Android 12 and above ask for `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` at
runtime; Android 11 and below use `BLUETOOTH`, `BLUETOOTH_ADMIN` and
`ACCESS_FINE_LOCATION` (discovery counted as a location request back then). The
app requests whichever set applies the first time you tap **Connect**.
