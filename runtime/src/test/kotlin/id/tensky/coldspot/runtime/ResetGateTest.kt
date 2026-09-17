package id.tensky.coldspot.runtime

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The fresh-session token: wipes once per token, and a build without one never wipes. */
class ResetGateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a normal build, with no token, never asks for a wipe`() {
        val gate = ResetGate(File(tmp.root, "coldspot/reset-token"))
        assertFalse(gate.shouldReset(null))
        gate.remember("earlier")
        assertFalse(gate.shouldReset(null), "a build without a token must not wipe what a fresh-session build left")
    }

    @Test
    fun `a fresh-session build wipes on its first launch only, and a newer one wipes again`() {
        val gate = ResetGate(File(tmp.root, "coldspot/reset-token"))
        assertTrue(gate.shouldReset("token-1"), "first sight of a token")
        gate.remember("token-1")
        assertFalse(gate.shouldReset("token-1"), "the same build, launched again")
        assertFalse(ResetGate(File(tmp.root, "coldspot/reset-token")).shouldReset("token-1"), "remembered across launches")
        assertTrue(gate.shouldReset("token-2"), "the next fresh-session build")
    }
}
