package dev.mobimapper.mobimapper.engine

/**
 * One-shot tap sequences on the same worker/composer as ordinary controls. Configuration is
 * prepared before capture. Only step boundaries schedule work; mouse motion never reads a profile.
 * The scheduler must enqueue (not inline) callbacks on that worker and return a cancellation handle.
 */
class MacroRunner(
    macros: List<PreparedMacro>,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val begin: (String, Float, Float) -> Boolean,
    private val end: (String) -> Boolean,
    private val changed: () -> Unit = {},
) {
    private val keys = macros.filter { it.triggerKind == "key" }.associateBy { it.code }
    private val buttons = macros.filter { it.triggerKind == "mouse" }.associateBy { it.code }
    private val held = HashSet<String>()
    private var current: PreparedMacro? = null
    private var pendingCancel: (() -> Unit)? = null
    private var epoch = 0L
    private var contactDown = false
    var stepIndex: Int = -1; private set
    var completed: Long = 0; private set
    var rejected: Long = 0; private set
    var lastError: String? = null; private set
    val activeId: String? get() = current?.id
    val pending: Boolean get() = pendingCancel != null

    /** A held trigger never loops. An UP is accepted even when cursor mode forbids starting. */
    fun input(kind: String, code: Int, down: Boolean, canStart: Boolean = true): Boolean {
        val macro = (if (kind == "key") keys else buttons)[code] ?: return false
        if (!down) { held.remove(macro.id); return true }
        if (!held.add(macro.id)) return true
        if (!canStart || current != null) { rejected++; return true }
        current = macro
        lastError = null
        stepIndex = 0
        epoch++
        press(epoch)
        return true
    }

    /** Global session cancellation supplies its own exact CANCEL; pointer-mode changes release UP. */
    fun cancel(releaseContact: Boolean = false) {
        epoch++
        pendingCancel?.invoke()
        pendingCancel = null
        val owner = current?.owner
        current = null
        stepIndex = -1
        if (contactDown && releaseContact && owner != null && !end(owner)) lastError = "MACRO_RELEASE_FAILED"
        contactDown = false
        // Keep trigger latches until physical UP: pause/cursor/edit must not replay a held macro.
    }

    private fun press(token: Long) {
        val macro = current ?: return
        val step = macro.steps[stepIndex]
        if (!begin(macro.owner, step.x, step.y)) { fail("MACRO_CONTACT_UNAVAILABLE"); return }
        contactDown = true
        changed()
        if (token != epoch || current == null) return
        later(MACRO_TAP_MS.toLong(), token) {
            contactDown = false
            if (!end(macro.owner)) { fail("MACRO_RELEASE_FAILED"); return@later }
            changed()
            if (token != epoch || current == null) return@later
            later(step.waitMs.toLong(), token) {
                if (++stepIndex < macro.steps.size) press(token)
                else { current = null; stepIndex = -1; completed++; changed() }
            }
        }
    }

    private fun later(delayMs: Long, token: Long, action: () -> Unit) {
        pendingCancel = schedule(delayMs) {
            if (token == epoch && current != null) { pendingCancel = null; action() }
        }
    }

    private fun fail(reason: String) {
        lastError = reason
        rejected++
        cancel()
        changed()
    }
}
