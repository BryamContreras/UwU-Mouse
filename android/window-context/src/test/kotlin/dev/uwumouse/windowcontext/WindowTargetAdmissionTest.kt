package dev.uwumouse.windowcontext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowTargetAdmissionTest {
    private val game = WindowMetadata(1, 1, 1, "dev.game", 10101, 2, 4,
        0, 0, 900, 600, focused = true)
    private val overlay = game.copy(id = 2, type = 3, layoutType = 2038,
        packageName = "dev.overlay", ownerUid = 10102, layer = 5, focused = false)

    private fun ParsedWindowSnapshot.admits(resolveUid: () -> Int? = { game.ownerUid }): Boolean =
        admitsTargetWindow(game.packageName, game.displayId, "dev.uwu", 10103, resolveUid)

    private fun withWindows(vararg windows: WindowMetadata): ParsedWindowSnapshot =
        ParsedWindowSnapshot(true, game.packageName, game.displayId, windows.toList())

    @Test fun requiresValidFocusOnTheTargetDisplayBeforeResolvingItsUid() {
        val snapshot = withWindows(game)
        var lookups = 0
        val resolve = { lookups++; game.ownerUid }

        assertFalse(snapshot.copy(valid = false).admits(resolve))
        assertFalse(snapshot.copy(focusedDisplayId = 0).admits(resolve))
        assertFalse(snapshot.copy(focusedPackage = null).admits(resolve))
        assertFalse(snapshot.copy(focusedPackage = "dev.thirdparty").admits(resolve))
        assertEquals(0, lookups)

        assertTrue(snapshot.admits(resolve))
        assertTrue(snapshot.copy(focusedPackage = "dev.uwu").admits(resolve))
        assertEquals(2, lookups)
        assertFalse(snapshot.admits { null })
    }

    @Test fun requiresAWindowWithMatchingPackageUidDisplayAndApplicationType() {
        assertFalse(withWindows().admits())
        assertFalse(withWindows(game.copy(packageName = "dev.other")).admits())
        assertFalse(withWindows(game.copy(ownerUid = 10103)).admits())
        assertFalse(withWindows(game.copy(displayId = 0)).admits())
        assertFalse(withWindows(game.copy(type = 3)).admits())
    }

    @Test fun selectsLargestTargetAndKeepsFirstCandidateOnAnAreaTie() {
        val small = game.copy(id = 3, right = 300, layer = 6)
        assertFalse(withWindows(small, game, overlay).admits())
        val shifted = game.copy(id = 4, left = 900, right = 1800)
        assertFalse(withWindows(game, shifted, overlay).admits())
        assertTrue(withWindows(shifted, game, overlay).admits())
    }

    @Test fun blocksOnlyAFullCoverByAnotherUidOnAHigherLayerOfTheSameDisplay() {
        assertFalse(withWindows(game, overlay).admits())
        assertFalse(withWindows(game, overlay.copy(left = -1, top = -1, right = 901, bottom = 601)).admits())

        assertTrue(withWindows(game, overlay.copy(layer = game.layer)).admits())
        assertTrue(withWindows(game, overlay.copy(displayId = 0)).admits())
        assertTrue(withWindows(game, overlay.copy(ownerUid = game.ownerUid)).admits())
        assertTrue(withWindows(game, overlay.copy(ownerUid = 10103)).admits())
        assertTrue(withWindows(game, overlay.copy(left = 1)).admits())
        assertTrue(withWindows(game, overlay.copy(top = 1)).admits())
        assertTrue(withWindows(game, overlay.copy(right = 899)).admits())
        assertTrue(withWindows(game, overlay.copy(bottom = 599)).admits())
    }
}
