package com.jeffarese.rexmobile.terminal

/**
 * Whether scrolling a program by wheel events waits long on the network, from the round trips of the
 * last [window] batches: from sending a batch to its answer. A program in fullscreen, such as Claude
 * Code, redraws only once a batch reaches it, so on a slow link the screen trails the finger by that
 * much and moves in steps. Slow once at least [enough] batches have gone and their median takes
 * [slowMs] or more, so one late answer doesn't count. Main thread.
 */
class ScrollLag(
    private val window: Int = 5,
    private val enough: Int = 4,
    private val slowMs: Long = 150,
) {
    private val samples = ArrayDeque<Long>()

    /** Note a batch that took [ms] there and back. Returns [slow]. */
    fun add(ms: Long): Boolean {
        samples.addLast(ms)
        while (samples.size > window) samples.removeFirst()
        return slow
    }

    val slow: Boolean get() = samples.size >= enough && samples.sorted()[samples.size / 2] >= slowMs
}
