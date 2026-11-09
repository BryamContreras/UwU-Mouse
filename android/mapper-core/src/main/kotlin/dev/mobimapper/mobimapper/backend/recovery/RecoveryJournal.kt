package dev.mobimapper.mobimapper.backend.recovery

import dev.mobimapper.mobimapper.engine.TouchComposer
import dev.mobimapper.mobimapper.engine.TouchFrame
import dev.mobimapper.mobimapper.engine.TouchCoordinatePolicy

/** Admission by InputManager is separate from observation by the destination Activity. */
enum class RecoveryInjectionState {
    IDLE, ACTIVE, RECOVERY_NEEDED, RECOVERY_ADMITTED, STOPPED,
}

data class RecoverySnapshot(
    val sessionId: Long,
    val width: Int,
    val height: Int,
    val targetUid: Int,
    val displayId: Int,
    val source: Int,
    val deviceId: Int,
    val state: RecoveryInjectionState,
    val lastConfirmed: TouchFrame?,
    val attempted: TouchFrame?,
    val activeFrame: TouchFrame?,
    val cancellationAccepted: Boolean,
    /** This journal has no receiver channel and can never establish this fact. */
    val receiverVerified: Boolean = false,
    /** The old stream was reconciled against an independent dispatcher observation. */
    val terminalReconciled: Boolean = false,
    /** Distinguishes OS closure after a rejected MOVE from the original terminal/orphan cases. */
    val rejectedMoveReconciled: Boolean = false,
    /** A known acknowledged stream ended after verified focus loss and a rejected retry CANCEL. */
    val focusLossReconciled: Boolean = false,
    val coordinatePolicy: TouchCoordinatePolicy = TouchCoordinatePolicy.STRICT,
    val rejectedDownReconciled: Boolean = false,
    /** Original display disappeared; all old producers exited and Android retained no down. */
    val removedDisplayReconciled: Boolean = false,
) {
    /** Last acknowledged post-event state; an unacknowledged DOWN is absent here. */
    val activeContacts: Int get() = activeFrame?.ids?.size ?: 0
    /** Maximum candidate set retained when the worker's last reply is missing. */
    val possibleContacts: Int get() = attempted?.ids?.size ?: activeContacts
}

/**
 * In-memory journal owned by the surviving guardian, not by the injector worker.
 *
 * Call prepare before the worker RPC and acknowledged only after it returns. A lost reply is
 * ambiguous: the attempted event may already have reached InputDispatcher. Recovery therefore
 * keeps its complete pointer set, including a pointer being added or lifted. A successful
 * POINTER_UP instead records the post-event set. No method reconstructs physical held inputs.
 * All returned and stored frames own their arrays; callers cannot mutate the recovery record.
 */
class RecoveryJournal(
    val displayId: Int = 0,
    private val source: Int = 0x1002, // InputDevice.SOURCE_TOUCHSCREEN without Android dependencies.
    private val deviceId: Int = 0,
) {
    private var sessionId = 0L
    private var width = 0
    private var height = 0
    private var targetUid = -1
    private var state = RecoveryInjectionState.IDLE
    private var lastConfirmed: TouchFrame? = null
    private var attempted: TouchFrame? = null
    private var activeFrame: TouchFrame? = null
    private var cancellationAccepted = false
    private var terminalReconciled = false
    private var importedForRecovery = false
    private var explicitRejectedMove = false
    private var explicitRejectedDown = false
    private var rejectedDownCancelTime = -1L
    private var downReplacementGeneration = 0L
    private var rejectedDownReconciled = false
    private var rejectedRecoveryCancel = false
    private var rejectedMoveReconciled = false
    private var focusLossObserved = false
    private var rejectedFocusCancel: TouchFrame? = null
    private var focusReplacementGeneration = 0L
    private var focusLossReconciled = false
    private var removedDisplayReconciled = false
    private var lastEventTime = 0L
    private var coordinatePolicy = TouchCoordinatePolicy.STRICT

    /** A new generation is explicit, monotonic and possible only after the prior stream ended. */
    @Synchronized fun begin(sessionId: Long, width: Int, height: Int, targetUid: Int,
                            coordinatePolicy: TouchCoordinatePolicy = TouchCoordinatePolicy.STRICT): Boolean {
        if (sessionId <= this.sessionId || width <= 0 || height <= 0 || targetUid < 0 ||
            activeFrame != null || attempted != null || state == RecoveryInjectionState.RECOVERY_NEEDED
        ) return false
        this.sessionId = sessionId
        this.width = width
        this.height = height
        this.targetUid = targetUid
        this.coordinatePolicy = coordinatePolicy
        state = RecoveryInjectionState.ACTIVE
        lastConfirmed = null
        cancellationAccepted = false
        terminalReconciled = false
        removedDisplayReconciled = false
        importedForRecovery = false
        explicitRejectedMove = false
        clearRejectedDownProof()
        rejectedRecoveryCancel = false
        rejectedMoveReconciled = false
        clearFocusLossProof()
        return true
    }

    /** A timeout already dequeued by Android must not expire a newer or recovering session. */
    @Synchronized fun canExpire(sessionId: Long): Boolean =
        sessionId == this.sessionId && state == RecoveryInjectionState.ACTIVE

    /**
     * Import only an old CANCEL candidate after the previous guardian was lost completely.
     * The authorized caller supplies its surviving journal copy; no DOWN or receiver receipt is
     * invented. A live/unfinished guardian journal cannot be overwritten through this path.
     * Display, source and device identity remain this guardian's fixed constructor metadata.
     */
    @Synchronized fun adoptForRecovery(
        newSid: Long,
        width: Int,
        height: Int,
        targetUid: Int,
        cancelFrame: TouchFrame,
        coordinatePolicy: TouchCoordinatePolicy = TouchCoordinatePolicy.STRICT,
    ): Boolean {
        if (newSid <= sessionId || width <= 0 || height <= 0 || targetUid < 0 ||
            state == RecoveryInjectionState.ACTIVE || state == RecoveryInjectionState.RECOVERY_NEEDED ||
            activeFrame != null || attempted != null || cancelFrame.action != TouchComposer.CANCEL ||
            !validFrame(cancelFrame, width, height, coordinatePolicy=coordinatePolicy,
                recoveryCancellation=true) ||
            cancelFrame.eventTime < lastEventTime
        ) return false
        sessionId = newSid
        this.width = width
        this.height = height
        this.targetUid = targetUid
        this.coordinatePolicy = coordinatePolicy
        state = RecoveryInjectionState.RECOVERY_NEEDED
        lastConfirmed = null // Imported data is a possible gesture, not a local injection receipt.
        attempted = cancelFrame.detached()
        lastEventTime = cancelFrame.eventTime
        cancellationAccepted = false
        terminalReconciled = false
        removedDisplayReconciled = false
        importedForRecovery = true
        explicitRejectedMove = false
        clearRejectedDownProof()
        rejectedRecoveryCancel = false
        rejectedMoveReconciled = false
        clearFocusLossProof()
        return true
    }

    /** Exactly one synchronous worker call can be in flight. Stale generation work is rejected. */
    @Synchronized fun prepare(sessionId: Long, frame: TouchFrame): Boolean {
        if (sessionId != this.sessionId || state != RecoveryInjectionState.ACTIVE ||
            attempted != null || !validTransition(frame)
        ) return false
        attempted = frame.detached()
        lastEventTime = frame.eventTime
        return true
    }

    /**
     * Roll back preparation only when transport proves worker.send was never entered.
     * A worker exception/negative reply is ambiguous and must never call this method.
     * Keep the timestamp high-water mark so an aborted request cannot admit stale work.
     */
    @Synchronized fun abortUnsent(sessionId:Long,frame:TouchFrame):Boolean {
        if(sessionId!=this.sessionId || state!=RecoveryInjectionState.ACTIVE ||
            attempted?.sameEvent(frame)!=true) return false
        attempted=null
        return true
    }

    /** False or missing worker acknowledgement never proves that an event was not delivered. */
    @Synchronized fun acknowledged(sessionId: Long, frame: TouchFrame, success: Boolean): Boolean {
        if (sessionId != this.sessionId || state != RecoveryInjectionState.ACTIVE ||
            attempted?.sameEvent(frame) != true
        ) return false
        if (!success) {
            val failed = fail(sessionId)
            // A negative reply is distinct from a missing reply after worker/pipe loss.
            // Neither proves non-delivery; it only makes this exact MOVE eligible for
            // the additional failed-CANCEL + independent OS observation below.
            explicitRejectedMove = failed && frame.action == TouchComposer.MOVE &&
                activeFrame?.let { active ->
                    active.downTime == frame.downTime && active.ids.contentEquals(frame.ids)
                } == true && lastConfirmed != null
            explicitRejectedDown = failed && frame.action == TouchComposer.DOWN &&
                frame.ids.size == 1 && activeFrame == null && !importedForRecovery
            return failed
        }
        val admitted = requireNotNull(attempted)
        lastConfirmed = admitted.detached()
        activeFrame = postEvent(admitted)
        attempted = null
        if (admitted.action and 0xff == TouchComposer.CANCEL) {
            state = RecoveryInjectionState.STOPPED
            cancellationAccepted = true
        }
        return true
    }

    /** Freeze the generation. The original gesture's recovery data survives worker death. */
    @Synchronized fun fail(sessionId: Long): Boolean {
        if (sessionId != this.sessionId || state != RecoveryInjectionState.ACTIVE) return false
        state = RecoveryInjectionState.RECOVERY_NEEDED
        cancellationAccepted = false
        terminalReconciled = false
        removedDisplayReconciled = false
        importedForRecovery = false
        explicitRejectedMove = false
        clearRejectedDownProof()
        rejectedRecoveryCancel = false
        rejectedMoveReconciled = false
        clearFocusLossProof()
        return true
    }

    /**
     * The guardian observed a concrete foreign focused task, not an unavailable focus query.
     * Only a fully acknowledged stream can gain this provenance. An uncertain DOWN/MOVE/UP
     * retains its existing recovery policy even when a focus change also happened.
     */
    @Synchronized fun failForFocusLoss(sessionId: Long): Boolean {
        if (sessionId != this.sessionId || state != RecoveryInjectionState.ACTIVE ||
            attempted != null || activeFrame == null || lastConfirmed == null) return false
        if (!fail(sessionId)) return false
        focusLossObserved = true
        return true
    }

    /**
     * A cancellation candidate, never a new DOWN. Use the original metadata from snapshot().
     * A complete uncertain POINTER_UP frame is intentional: its reply might have been lost.
     */
    @Synchronized fun cancellationFrame(nowUptime: Long): TouchFrame? {
        if (state != RecoveryInjectionState.RECOVERY_NEEDED) return null
        val possible = attempted ?: activeFrame ?: return null
        // A later CANCEL requires its own explicit negative acknowledgement.
        rejectedFocusCancel = null
        focusReplacementGeneration = 0L
        rejectedDownCancelTime = -1L
        downReplacementGeneration = 0L
        lastEventTime = maxOf(nowUptime, possible.eventTime, possible.downTime, lastEventTime)
        return possible.detached().copy(
            action = TouchComposer.CANCEL,
            eventTime = lastEventTime,
        )
    }

    /** Admission is recorded without claiming the receiver observed CANCEL. No auto-resume. */
    @Synchronized fun cancelled(sessionId: Long, accepted: Boolean): Boolean {
        if (sessionId != this.sessionId || state != RecoveryInjectionState.RECOVERY_NEEDED) return false
        cancellationAccepted = accepted
        if (accepted) {
            state = RecoveryInjectionState.RECOVERY_ADMITTED
            activeFrame = null
            attempted = null
        }
        return true
    }

    /**
     * A final UP may have reached the dispatcher before its worker reply was lost. Only an
     * independent, globally empty dispatcher observation can reconcile that exact terminal
     * case. This neither acknowledges the missing RPC nor claims a successful late CANCEL or
     * delivery to the receiver. Nonterminal events must still follow the cancellation path.
     */
    @Synchronized fun reconciledTerminal(sessionId: Long, dispatcherGloballyEmpty: Boolean): Boolean {
        val uncertain = attempted
        if (sessionId != this.sessionId || state != RecoveryInjectionState.RECOVERY_NEEDED ||
            !dispatcherGloballyEmpty || uncertain == null ||
            uncertain.action != TouchComposer.UP || uncertain.ids.size != 1
        ) return false
        state = RecoveryInjectionState.RECOVERY_ADMITTED
        activeFrame = null
        attempted = null
        cancellationAccepted = false
        terminalReconciled = true
        return true
    }

    /**
     * After total guardian loss, its worker may have cancelled on parent EOF without preserving
     * the acknowledgement. Only a CANCEL imported through adoptForRecovery can use this path.
     * An independent globally empty dispatcher observation closes that orphan's uncertainty;
     * it does not turn an unsuccessful retry into an admitted CANCEL or a receiver receipt.
     */
    @Synchronized fun reconciledOrphan(sessionId: Long, dispatcherGloballyEmpty: Boolean): Boolean {
        if (sessionId != this.sessionId || state != RecoveryInjectionState.RECOVERY_NEEDED ||
            !dispatcherGloballyEmpty || !importedForRecovery || lastConfirmed != null ||
            attempted?.action != TouchComposer.CANCEL
        ) return false
        state = RecoveryInjectionState.RECOVERY_ADMITTED
        activeFrame = null
        attempted = null
        cancellationAccepted = false
        terminalReconciled = true
        return true
    }

    /**
     * Called only after a live replacement worker explicitly replies false to this exact CANCEL.
     * Exceptions, a dead worker and an unacknowledged MOVE must never call this method.
     * Keeping this provenance separate prevents the terminal/orphan paths from broadening.
     */
    @Synchronized fun rejectedRecoveryCancellation(sessionId: Long, cancelFrame: TouchFrame): Boolean {
        val uncertain = attempted
        if (sessionId != this.sessionId || state != RecoveryInjectionState.RECOVERY_NEEDED ||
            !explicitRejectedMove || uncertain?.action != TouchComposer.MOVE ||
            cancelFrame.action != TouchComposer.CANCEL ||
            cancelFrame.downTime != uncertain.downTime || cancelFrame.eventTime != lastEventTime ||
            cancelFrame.eventTime < uncertain.eventTime ||
            !cancelFrame.ids.contentEquals(uncertain.ids) ||
            !cancelFrame.xs.contentEquals(uncertain.xs) || !cancelFrame.ys.contentEquals(uncertain.ys)
        ) return false
        rejectedRecoveryCancel = true
        return true
    }

    /** Avoid even the exceptional OS query until all local provenance has been established. */
    @Synchronized fun canReconcileRejectedMove(sessionId: Long): Boolean {
        val uncertain = attempted ?: return false
        val active = activeFrame ?: return false
        return sessionId == this.sessionId && state == RecoveryInjectionState.RECOVERY_NEEDED &&
            explicitRejectedMove && rejectedRecoveryCancel && uncertain.action == TouchComposer.MOVE &&
            active.downTime == uncertain.downTime && active.ids.contentEquals(uncertain.ids) &&
            lastConfirmed != null && !importedForRecovery
    }

    /** A rejected initial DOWN needs a terminated old worker and an exact rejected retry. */
    @Synchronized fun rejectedInitialDownCancellation(
        sessionId: Long, cancelFrame: TouchFrame, previousWorkerTerminated: Boolean,
        previousWorkerGeneration: Long, replacementWorkerGeneration: Long,
    ): Boolean {
        val uncertain = attempted ?: return false
        if (sessionId != this.sessionId || state != RecoveryInjectionState.RECOVERY_NEEDED ||
            !explicitRejectedDown || importedForRecovery || activeFrame != null ||
            uncertain.action != TouchComposer.DOWN || uncertain.ids.size != 1 ||
            !previousWorkerTerminated || previousWorkerGeneration <= 0L ||
            replacementWorkerGeneration <= previousWorkerGeneration ||
            cancelFrame.action != TouchComposer.CANCEL || cancelFrame.eventTime != lastEventTime ||
            cancelFrame.eventTime < uncertain.eventTime || cancelFrame.downTime != uncertain.downTime ||
            !cancelFrame.ids.contentEquals(uncertain.ids) || !cancelFrame.xs.contentEquals(uncertain.xs) ||
            !cancelFrame.ys.contentEquals(uncertain.ys)) return false
        rejectedDownCancelTime = cancelFrame.eventTime
        downReplacementGeneration = replacementWorkerGeneration
        return true
    }

    @Synchronized fun canReconcileRejectedDown(sessionId: Long, replacementWorkerGeneration: Long): Boolean =
        sessionId == this.sessionId && state == RecoveryInjectionState.RECOVERY_NEEDED &&
            explicitRejectedDown && !importedForRecovery && activeFrame == null &&
            attempted?.action == TouchComposer.DOWN && attempted?.ids?.size == 1 &&
            rejectedDownCancelTime == lastEventTime && replacementWorkerGeneration > 0L &&
            downReplacementGeneration == replacementWorkerGeneration

    /** Global OS proof closes uncertainty without inventing CANCEL admission or delivery. */
    @Synchronized fun reconciledRejectedDown(
        sessionId: Long, replacementWorkerGeneration: Long, dispatcherGloballyNoDown: Boolean,
    ): Boolean {
        if (!dispatcherGloballyNoDown || !canReconcileRejectedDown(sessionId, replacementWorkerGeneration)) return false
        state = RecoveryInjectionState.RECOVERY_ADMITTED
        attempted = null
        cancellationAccepted = false
        terminalReconciled = true
        rejectedDownReconciled = true
        return true
    }

    private fun clearRejectedDownProof() {
        explicitRejectedDown = false
        rejectedDownCancelTime = -1L
        downReplacementGeneration = 0L
        rejectedDownReconciled = false
    }

    /**
     * Android can close a gesture independently (for example after a device/source switch),
     * then reject both a MOVE and its exact late CANCEL. Only an explicitly rejected MOVE,
     * a rejected replacement-worker CANCEL and an independent global no-down observation
     * close this uncertainty. This is neither cancellation admission nor receiver verification.
     */
    @Synchronized fun reconciledRejectedMove(sessionId: Long, dispatcherGloballyNoDown: Boolean): Boolean {
        if (!dispatcherGloballyNoDown || !canReconcileRejectedMove(sessionId)) return false
        state = RecoveryInjectionState.RECOVERY_ADMITTED
        activeFrame = null
        attempted = null
        cancellationAccepted = false
        terminalReconciled = true
        rejectedMoveReconciled = true
        return true
    }

    /**
     * A retry belongs to the original journal, after the old worker was confirmed terminated.
     * The caller invokes this only for an explicit false ACK from the live replacement worker.
     * A missing ACK, a reused worker, or a different candidate cannot authorize reconciliation.
     */
    @Synchronized fun rejectedFocusLossCancellation(
        sessionId: Long, cancelFrame: TouchFrame, previousWorkerTerminated: Boolean,
        previousWorkerGeneration: Long, replacementWorkerGeneration: Long,
    ): Boolean {
        val active = activeFrame ?: return false
        if (sessionId != this.sessionId || state != RecoveryInjectionState.RECOVERY_NEEDED ||
            !focusLossObserved || attempted != null || lastConfirmed == null || importedForRecovery ||
            !previousWorkerTerminated || previousWorkerGeneration <= 0L ||
            replacementWorkerGeneration <= previousWorkerGeneration ||
            cancelFrame.action != TouchComposer.CANCEL || cancelFrame.eventTime != lastEventTime ||
            cancelFrame.eventTime < active.eventTime || cancelFrame.downTime != active.downTime ||
            !cancelFrame.ids.contentEquals(active.ids) || !cancelFrame.xs.contentEquals(active.xs) ||
            !cancelFrame.ys.contentEquals(active.ys)) return false
        rejectedFocusCancel = cancelFrame.detached()
        focusReplacementGeneration = replacementWorkerGeneration
        return true
    }

    @Synchronized fun canReconcileFocusLoss(sessionId: Long, replacementWorkerGeneration: Long): Boolean =
        sessionId == this.sessionId && state == RecoveryInjectionState.RECOVERY_NEEDED &&
            focusLossObserved && attempted == null && activeFrame != null && lastConfirmed != null &&
            !importedForRecovery && replacementWorkerGeneration > 0L &&
            replacementWorkerGeneration == focusReplacementGeneration &&
            rejectedFocusCancel?.eventTime == lastEventTime

    /** Global no-down proof is distinct from an admitted CANCEL and from a receiver receipt. */
    @Synchronized fun reconciledFocusLoss(
        sessionId: Long, replacementWorkerGeneration: Long, dispatcherGloballyNoDown: Boolean,
    ): Boolean {
        if (!dispatcherGloballyNoDown || !canReconcileFocusLoss(sessionId, replacementWorkerGeneration)) return false
        state = RecoveryInjectionState.RECOVERY_ADMITTED
        activeFrame = null
        attempted = null
        cancellationAccepted = false
        terminalReconciled = true
        focusLossReconciled = true
        return true
    }

    /**
     * Closure only, after the owning guardian has stopped capture, retired every old worker,
     * observed the original display absent and obtained a fresh global no-down snapshot.
     * No injection or replacement destination is involved. The complete original journal
     * must still match; an imported orphan cannot prove its former producers terminated.
     */
    @Synchronized fun reconciledRemovedDisplay(
        original: RecoverySnapshot,
        previousWorkersTerminated: Boolean,
        originalDisplayRemoved: Boolean,
        dispatcherGloballyNoDown: Boolean,
    ): Boolean {
        if (!previousWorkersTerminated || !originalDisplayRemoved || !dispatcherGloballyNoDown ||
            importedForRecovery || state != RecoveryInjectionState.RECOVERY_NEEDED ||
            original.state != RecoveryInjectionState.RECOVERY_NEEDED || original.possibleContacts == 0 ||
            original.sessionId != sessionId || original.width != width || original.height != height ||
            original.targetUid != targetUid || original.displayId != displayId ||
            original.source != source || original.deviceId != deviceId || original.coordinatePolicy != coordinatePolicy ||
            !sameCandidate(lastConfirmed, original.lastConfirmed) ||
            !sameCandidate(activeFrame, original.activeFrame) || !sameCandidate(attempted, original.attempted)) return false
        state = RecoveryInjectionState.RECOVERY_ADMITTED
        activeFrame = null
        attempted = null
        cancellationAccepted = false
        terminalReconciled = true
        removedDisplayReconciled = true
        return true
    }

    private fun sameCandidate(frame: TouchFrame?, other: TouchFrame?): Boolean =
        if (frame == null) other == null else other != null && frame.sameEvent(other)

    private fun clearFocusLossProof() {
        focusLossObserved = false
        rejectedFocusCancel = null
        focusReplacementGeneration = 0L
        focusLossReconciled = false
    }

    @Synchronized fun snapshot(): RecoverySnapshot = RecoverySnapshot(
        sessionId, width, height, targetUid, displayId, source, deviceId, state,
        lastConfirmed?.detached(), attempted?.detached(), activeFrame?.detached(),
        cancellationAccepted,
        terminalReconciled = terminalReconciled,
        rejectedMoveReconciled = rejectedMoveReconciled,
        focusLossReconciled = focusLossReconciled,
        coordinatePolicy = coordinatePolicy,
        rejectedDownReconciled = rejectedDownReconciled,
        removedDisplayReconciled = removedDisplayReconciled,
    )

    private fun validTransition(frame: TouchFrame): Boolean {
        val count = frame.ids.size
        if (!validFrame(frame, width, height, previous=activeFrame) || frame.eventTime < lastEventTime) return false
        val action = frame.action and 0xff
        val index = frame.action ushr 8
        val previous = activeFrame
        if (previous == null) return action == TouchComposer.DOWN && index == 0 && count == 1
        if (frame.downTime != previous.downTime || frame.eventTime < previous.eventTime) return false
        return when (action) {
            TouchComposer.MOVE, TouchComposer.CANCEL -> index == 0 && frame.ids.contentEquals(previous.ids)
            TouchComposer.UP -> index == 0 && count == 1 && frame.ids.contentEquals(previous.ids)
            TouchComposer.POINTER_DOWN -> count == previous.ids.size + 1 && index < count &&
                frame.ids.without(index).contentEquals(previous.ids)
            TouchComposer.POINTER_UP -> count > 1 && index < count &&
                frame.ids.contentEquals(previous.ids)
            else -> false
        }
    }

    private fun validFrame(frame: TouchFrame, width: Int, height: Int,
                           previous:TouchFrame?=null,
                           coordinatePolicy:TouchCoordinatePolicy=this.coordinatePolicy,
                           recoveryCancellation:Boolean=false): Boolean {
        val count = frame.ids.size
        return count in 1..10 && frame.xs.size == count && frame.ys.size == count &&
            frame.ids.all { it in 0..9 } && frame.ids.toSet().size == count &&
            coordinatePolicy.allowsCoordinates(frame,width,height,previous,recoveryCancellation) &&
            frame.downTime >= 0 && frame.eventTime >= frame.downTime
    }

    private fun postEvent(frame: TouchFrame): TouchFrame? = when (frame.action and 0xff) {
        TouchComposer.UP, TouchComposer.CANCEL -> null
        TouchComposer.POINTER_UP -> {
            val index = frame.action ushr 8
            frame.copy(
                action = TouchComposer.MOVE,
                ids = frame.ids.without(index),
                xs = frame.xs.without(index),
                ys = frame.ys.without(index),
            )
        }
        else -> frame.detached().copy(action = TouchComposer.MOVE)
    }

    private fun TouchFrame.detached() = copy(ids = ids.copyOf(), xs = xs.copyOf(), ys = ys.copyOf())
    private fun TouchFrame.sameEvent(other: TouchFrame) =
        action == other.action && downTime == other.downTime && eventTime == other.eventTime &&
            ids.contentEquals(other.ids) && xs.contentEquals(other.xs) && ys.contentEquals(other.ys)
    private fun IntArray.without(index: Int) = IntArray(size - 1) { this[if (it < index) it else it + 1] }
    private fun FloatArray.without(index: Int) = FloatArray(size - 1) { this[if (it < index) it else it + 1] }
}
