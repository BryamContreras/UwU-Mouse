package dev.mobimapper.inputlab

/**
 * Receive-side state validation, deliberately independent of Android classes.
 * Invalid callbacks are reported before resynchronizing to the state they describe.
 */
internal class TouchSequenceValidator {
    private var activeBits = 0L
    private var gestureDownTime = 0L

    val pendingIds: Set<Int>
        get() = (0..31).filterTo(linkedSetOf()) { activeBits and (1L shl it) != 0L }

    fun accept(action: Int, actionIndex: Int, downTime: Long, ids: IntArray): List<String> {
        var failures: MutableList<String>? = null
        fun error(reason: String) {
            val result = failures ?: mutableListOf<String>().also { failures = it }
            result.add(reason)
        }
        var nextBits = 0L
        for (id in ids) {
            if (id !in 0..31) {
                error("ID fuera de 0..31")
                continue
            }
            val bit = 1L shl id
            if (nextBits and bit != 0L) error("IDs duplicados")
            nextBits = nextBits or bit
        }
        if (ids.isEmpty() && action != CANCEL) error("Evento sin punteros")
        if (action != DOWN && activeBits != 0L && gestureDownTime != downTime) {
            error("downTime cambió durante el gesto")
        }
        when (action) {
            DOWN -> {
                if (activeBits != 0L) error("DOWN con contactos pendientes")
                if (ids.size != 1 || actionIndex != 0) error("DOWN requiere un puntero e índice cero")
                gestureDownTime = downTime
            }
            POINTER_DOWN -> {
                if (activeBits == 0L || ids.size != java.lang.Long.bitCount(activeBits) + 1 ||
                    nextBits and activeBits != activeBits
                ) {
                    error("POINTER_DOWN incoherente")
                }
                if (actionIndex !in ids.indices) {
                    error("Índice de pulsación inválido")
                } else if (ids[actionIndex] in 0..31 && activeBits and (1L shl ids[actionIndex]) != 0L) {
                    error("POINTER_DOWN apunta a un ID ya activo")
                }
            }
            POINTER_UP -> {
                if (ids.size < 2) error("POINTER_UP requiere al menos dos punteros")
                if (nextBits != activeBits) error("Conjunto de punteros cambió sin transición")
                if (actionIndex !in ids.indices) error("Índice de liberación inválido")
            }
            UP -> {
                if (ids.size != 1 || actionIndex != 0) error("UP requiere un puntero e índice cero")
                if (nextBits != activeBits) error("Conjunto de punteros cambió sin transición")
            }
            MOVE -> {
                if (nextBits != activeBits) error("Conjunto de punteros cambió sin transición")
            }
            CANCEL -> {
                if (activeBits != 0L && nextBits != activeBits) error("CANCEL no incluye todos los punteros")
            }
            else -> {
                error("Acción táctil desconocida: $action")
                return failures ?: emptyList()
            }
        }
        activeBits = when (action) {
            UP, CANCEL -> 0L
            POINTER_UP -> {
                if (actionIndex in ids.indices && ids[actionIndex] in 0..31) {
                    nextBits and (1L shl ids[actionIndex]).inv()
                } else nextBits
            }
            else -> nextBits
        }
        return failures ?: emptyList()
    }

    companion object {
        const val DOWN = 0
        const val UP = 1
        const val MOVE = 2
        const val CANCEL = 3
        const val POINTER_DOWN = 5
        const val POINTER_UP = 6
    }
}
