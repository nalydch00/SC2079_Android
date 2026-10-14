#!/usr/bin/env bash
# Stand in for the robot from a laptop: the tablet (debug build, app open)
# must be plugged in over USB with USB debugging on.
#
#   ./sim.sh connect [name]       pretend the RPi link came up (jumps to the map)
#   ./sim.sh move X Y DIR         robot moved; DIR is N/E/S/W or 0/2/4/6
#   ./sim.sh see OBSTACLE LABEL   robot read LABEL (1-9, A-Z, up/down/left/right/stop) on OBSTACLE
#   ./sim.sh status "text"        message for the status box
#   ./sim.sh send 'JSON'          any raw line, exactly as the RPi would send it
#   ./sim.sh replay FILE          send each line of FILE (blank and # lines skipped)
#   ./sim.sh run                  connect, then replay sample_run.jsonl
#   ./sim.sh drop                 pretend the link dropped mid-run
#   ./sim.sh disconnect           end the simulated link
#
# What the app sends back is printed by:  adb logcat -s MdpSimulator
# SIM_DELAY sets the pause between replayed lines (seconds, default 0.7).

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
COMPONENT="com.sc2079.mdp/.debug.SimulatorReceiver"
DELAY="${SIM_DELAY:-0.7}"
DEFAULT_NAME="MDP-Group6-RPi"

find_adb() {
    if [[ -n "${ADB:-}" ]]; then echo "$ADB"; return; fi
    if command -v adb >/dev/null 2>&1; then command -v adb; return; fi
    for candidate in "$HOME/Library/Android/sdk/platform-tools/adb" "$HOME/Android/Sdk/platform-tools/adb"; do
        if [[ -x "$candidate" ]]; then echo "$candidate"; return; fi
    done
    echo "adb not found - install Android Studio's platform-tools or set ADB=/path/to/adb" >&2
    exit 1
}
ADB_BIN="$(find_adb)"

broadcast() {
    local out
    out="$("$ADB_BIN" shell am broadcast -n "$COMPONENT" "$@" 2>&1)" || { echo "$out" >&2; exit 1; }
    if [[ "$out" != *"Broadcast completed"* ]]; then
        echo "$out" >&2
        exit 1
    fi
}

connect() {
    # adb shell re-splits arguments on spaces, so quote the name for the tablet's shell.
    broadcast --es cmd connect --es name "'${1//\'/}'"
    echo "-- connected to $1 (simulated)"
}

receive() {
    local b64
    b64="$(printf '%s' "$1" | base64 | tr -d '\n')"
    broadcast --es cmd receive --es data "$b64"
    echo "<< $1"
}

heading() {
    case "$(echo "$1" | tr '[:lower:]' '[:upper:]')" in
        N|0) echo 0 ;; E|2) echo 2 ;; S|4) echo 4 ;; W|6) echo 6 ;;
        *) echo "DIR must be N, E, S or W (or 0, 2, 4, 6)" >&2; exit 1 ;;
    esac
}

# The RPi's image-rec class id for a label; the app shows image_id and ignores this.
class_id() {
    case "$(echo "$1" | tr '[:lower:]' '[:upper:]')" in
        [1-9]) echo $((10 + $1)) ;;
        A) echo 20 ;; B) echo 21 ;; C) echo 22 ;; D) echo 23 ;; E) echo 24 ;;
        F) echo 25 ;; G) echo 26 ;; H) echo 27 ;; S) echo 28 ;; T) echo 29 ;;
        U) echo 30 ;; V) echo 31 ;; W) echo 32 ;; X) echo 33 ;; Y) echo 34 ;;
        Z) echo 35 ;; UP) echo 36 ;; DOWN) echo 37 ;; RIGHT) echo 38 ;;
        LEFT) echo 39 ;; STOP) echo 40 ;; *) echo 0 ;;
    esac
}

json_string() {
    local s="${1//\\/\\\\}"
    printf '"%s"' "${s//\"/\\\"}"
}

replay() {
    local file="$1" line
    [[ -f "$file" ]] || { echo "No such file: $file" >&2; exit 1; }
    while IFS= read -r line || [[ -n "$line" ]]; do
        line="${line#"${line%%[![:space:]]*}"}"
        [[ -z "$line" || "$line" == \#* ]] && continue
        receive "$line"
        sleep "$DELAY"
    done < "$file"
}

usage() {
    sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
}

cmd="${1:-}"
[[ $# -gt 0 ]] && shift
case "$cmd" in
    connect)
        connect "${1:-$DEFAULT_NAME}"
        ;;
    drop)
        broadcast --es cmd drop
        echo "-- link dropped (simulated)"
        ;;
    disconnect)
        broadcast --es cmd disconnect
        echo "-- disconnected"
        ;;
    move)
        [[ $# -eq 3 ]] || usage
        [[ "$1$2" =~ ^[0-9]+$ ]] || { echo "X and Y must be whole numbers" >&2; exit 1; }
        d="$(heading "$3")"
        receive "{\"cat\":\"location\",\"value\":{\"x\":$1,\"y\":$2,\"d\":$d}}"
        ;;
    see)
        [[ $# -eq 2 ]] || usage
        receive "{\"cat\":\"info\",\"value\":\"Capturing image for obstacle id: $1\"}"
        sleep "$DELAY"
        receive "{\"cat\":\"image-rec\",\"value\":{\"obstacle_id\":\"$(class_id "$2")\",\"conf\":0.91,\"xyxy\":[120,80,260,240],\"image_id\":$(json_string "$2")}}"
        ;;
    status)
        [[ $# -ge 1 ]] || usage
        receive "{\"cat\":\"info\",\"value\":$(json_string "$*")}"
        ;;
    send)
        [[ $# -ge 1 ]] || usage
        receive "$*"
        ;;
    replay)
        [[ $# -eq 1 ]] || usage
        replay "$1"
        ;;
    run)
        connect "$DEFAULT_NAME"
        sleep 2
        replay "$HERE/sample_run.jsonl"
        ;;
    *)
        usage
        ;;
esac
