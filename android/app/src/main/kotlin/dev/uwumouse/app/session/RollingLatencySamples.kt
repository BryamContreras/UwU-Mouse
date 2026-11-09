package dev.uwumouse.app.session

/** Fixed storage; callers serialize recording, reset and snapshot under their metrics lock. */
internal class RollingLatencySamples(capacity: Int) {
    private val values = LongArray(capacity.also { require(it > 0) })
    private var next = 0
    private var size = 0

    fun record(nanos: Long) {
        values[next] = nanos
        if (++next == values.size) next = 0
        if (size < values.size) size++
    }

    fun clear() { next = 0; size = 0 }

    fun snapshot(): LongArray = values.copyOf(size)

    companion object {
        /** Sort only a detached snapshot, so diagnostics never hold up input while sorting. */
        fun percentilesMillis(snapshot: LongArray): List<Double> {
            if (snapshot.isEmpty()) return emptyList()
            snapshot.sort()
            val last = snapshot.lastIndex
            return listOf(snapshot[(last * .50).toInt()] / 1e6,
                snapshot[(last * .95).toInt()] / 1e6, snapshot[(last * .99).toInt()] / 1e6)
        }
    }
}
