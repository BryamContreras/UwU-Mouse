package dev.uwumouse.app.backend.recovery

/** Package, UID, coordinate policy and display can change only after the old stream is empty. */
object DestinationChangePolicy {
    fun maySelect(snapshot: RecoverySnapshot, recoveryScheduled: Boolean): Boolean =
        snapshot.possibleContacts == 0 && snapshot.state != RecoveryInjectionState.RECOVERY_NEEDED && !recoveryScheduled
}
