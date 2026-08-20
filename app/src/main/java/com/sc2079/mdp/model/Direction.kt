package com.sc2079.mdp.model

/**
 * The four compass headings used throughout the MDP protocol.
 *
 * The arena is drawn with the origin at the bottom-left corner, so [NORTH] is
 * "up the screen" and increasing y, while [EAST] is "right" and increasing x.
 */
enum class Direction(val code: String, val degrees: Float) {
    NORTH("N", 0f),
    EAST("E", 90f),
    SOUTH("S", 180f),
    WEST("W", 270f);

    /** Unit step in grid coordinates when moving one cell towards this heading. */
    val dx: Int
        get() = when (this) {
            EAST -> 1
            WEST -> -1
            else -> 0
        }

    val dy: Int
        get() = when (this) {
            NORTH -> 1
            SOUTH -> -1
            else -> 0
        }

    fun turnLeft(): Direction = entries[(ordinal + entries.size - 1) % entries.size]

    fun turnRight(): Direction = entries[(ordinal + 1) % entries.size]

    fun opposite(): Direction = turnRight().turnRight()

    companion object {
        /**
         * Parses a heading from the wire format. Accepts the single letter codes
         * (`N`, `S`, `E`, `W`), the spelled out names, and the two-letter forms the
         * briefing slides use for diagonal labels such as `WN` (first letter wins).
         *
         * Returns `null` when [value] is not a recognisable heading.
         */
        fun fromCode(value: String?): Direction? {
            val cleaned = value?.trim()?.uppercase() ?: return null
            if (cleaned.isEmpty()) return null
            entries.firstOrNull { it.name == cleaned }?.let { return it }
            return entries.firstOrNull { it.code == cleaned.substring(0, 1) }
        }
    }
}
