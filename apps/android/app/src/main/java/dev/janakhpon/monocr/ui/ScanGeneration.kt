package dev.janakhpon.monocr.ui

/** Main-thread generation token, including imports that have not reached inference yet. */
internal class ScanGeneration {
    private var current = 0L
    fun next(): Long = ++current
    fun isCurrent(generation: Long): Boolean = generation == current

    fun cancelIfCurrent(generation: Long): Boolean {
        if (!isCurrent(generation)) return false
        next()
        return true
    }
}
