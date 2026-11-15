package dev.uwumouse.app.backend.recovery

import dev.uwumouse.app.engine.TouchComposer
import dev.uwumouse.app.engine.TouchFrame
import dev.uwumouse.app.engine.TouchCoordinatePolicy
import org.junit.Assert.*
import org.junit.Test

class ActiveOperationOwnershipTest {
    @Test fun ownershipRejectsChangedMetadataPendingAttemptsAndRecovery() {
        val journal=RecoveryJournal()
        assertTrue(journal.begin(1,1080,2316,10498))
        val original=journal.snapshot()
        assertTrue(journal.ownsActiveOperation(original))
        for(changed in listOf(original.copy(sessionId=2),original.copy(width=1081),
            original.copy(height=2315),original.copy(targetUid=10499),original.copy(displayId=2),
            original.copy(source=8194),original.copy(deviceId=1),
            original.copy(coordinatePolicy=TouchCoordinatePolicy.CONTINUOUS_CAMERA_9))) {
            assertFalse(journal.ownsActiveOperation(changed))
        }
        val down=TouchFrame(TouchComposer.DOWN,100,100,intArrayOf(0),floatArrayOf(20f),floatArrayOf(30f))
        assertTrue(journal.prepare(1,down))
        assertFalse(journal.ownsActiveOperation(original))
        assertTrue(journal.abortUnsent(1,down))
        assertTrue(journal.ownsActiveOperation(original))
        assertTrue(journal.prepare(1,down));assertTrue(journal.acknowledged(1,down,true))
        assertTrue(journal.ownsActiveOperation(original))
        journal.fail(1)
        assertFalse(journal.ownsActiveOperation(original))
    }
}
