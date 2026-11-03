package ch.snepilatch.app.viewmodel

/** Rows to ask for: [limit] from [offset]. */
internal data class PageWindow(val offset: Int, val limit: Int)

/**
 * The page holding [row] that fetches no row twice, as the web player's does: normally [size] rows from
 * [row], and if something is already loaded within them, the rows that end right before it. Measured on the
 * web player: with 972 onward loaded, scrolling up asks for 922 to 971 and then 872 to 921.
 */
internal fun pageWindow(row: Int, size: Int, isLoaded: (Int) -> Boolean): PageWindow {
    val next = (row + 1 until row + size).firstOrNull(isLoaded) ?: return PageWindow(row, size)
    var start = row
    while (start > 0 && next - start < size && !isLoaded(start - 1)) start--
    return PageWindow(start, next - start)
}
