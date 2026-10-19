package com.example.open_script

/** UTF-16 offsets and pixel bounds of an actual laid-out text line, including empty lines. */
internal data class ReadingLine(val start: Int, val end: Int, val top: Float, val bottom: Float) {
    val center: Float get() = (top + bottom) / 2f
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
}

internal data class ReadingTextGeometry(val height: Float, val lines: List<ReadingLine>)

/** Find the line crossing the guide; do not estimate from character counts or nominal font size. */
internal fun activeReadingLine(lines: List<ReadingLine>, scrollOffset: Float): Int? {
    if (lines.isEmpty()) return null
    val position = lines.first().center + scrollOffset.coerceAtLeast(0f)
    var low = 0
    var high = lines.size
    while (low < high) {
        val middle = (low + high) / 2
        if (position < lines[middle].bottom) high = middle else low = middle + 1
    }
    return low.coerceAtMost(lines.lastIndex)
}

/** Keep the old end space, extending it only when the final line could not reach the guide. */
internal fun readingTrailingSpace(
    viewportHeight: Float,
    leadingSpace: Float,
    verticalPadding: Float,
    geometry: ReadingTextGeometry?,
): Float {
    val original = viewportHeight * .65f
    if (geometry == null || geometry.lines.isEmpty()) return original
    val first = geometry.lines.first()
    val last = geometry.lines.last()
    val needed = viewportHeight - leadingSpace - verticalPadding * 2f - first.center - (geometry.height - last.center)
    return maxOf(original, needed, 0f)
}
