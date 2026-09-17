package id.tensky.coldspot.runtime

/** Distances from the four sides of the window, in pixels: what system bars and cutouts take. */
internal data class Edges(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    /** Side by side, the larger: a cutout inside a system bar takes no more room than the bar. */
    fun union(other: Edges): Edges = Edges(maxOf(left, other.left), maxOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom))

    companion object {
        val NONE = Edges(0, 0, 0, 0)
    }
}

/**
 * Where the bubble rests: against the left or the right edge, [vertical] of the way down the room it has
 * (0 the top, 1 the bottom). Kept as a fraction, so that it means the same place in every activity, in both
 * orientations and on a window of another size.
 */
internal data class BubblePosition(val right: Boolean, val vertical: Float) {
    companion object {
        val DEFAULT = BubblePosition(right = true, vertical = 0.7f)
    }
}

/**
 * The bubble's arithmetic, free of Android types: the room it may use is the window less [insets] (system bars
 * and cutouts, edge-to-edge or not) less a [margin]; it is [size] square; coordinates are its top-left corner.
 */
internal class BubbleGeometry(
    private val width: Int,
    private val height: Int,
    private val insets: Edges,
    private val size: Int,
    private val margin: Int,
) {
    private val minX = insets.left + margin
    private val minY = insets.top + margin

    // Never less than the minimum: on a window too small for the bubble it sits at the top-left of its room.
    private val maxX = maxOf(minX, width - insets.right - margin - size)
    private val maxY = maxOf(minY, height - insets.bottom - margin - size)

    /** The resting place of [position]. */
    fun place(position: BubblePosition): Pair<Int, Int> {
        val x = if (position.right) maxX else minX
        val y = minY + Math.round(position.vertical.coerceIn(0f, 1f) * (maxY - minY))
        return x to y
    }

    /** While dragged: anywhere inside the room, never under a bar or a cutout. */
    fun clamp(x: Float, y: Float): Pair<Float, Float> =
        x.coerceIn(minX.toFloat(), maxX.toFloat()) to y.coerceIn(minY.toFloat(), maxY.toFloat())

    /** Let go at [x], [y]: the nearer edge by the bubble's centre, the height kept. */
    fun snap(x: Float, y: Float): BubblePosition {
        val (_, clampedY) = clamp(x, y)
        val right = x + size / 2f >= width / 2f
        val vertical = if (maxY > minY) (clampedY - minY) / (maxY - minY) else 0f
        return BubblePosition(right, vertical)
    }
}
