package com.privateime.core.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The IME re-reads settings on every focus change and rebuilds its engine only when
 * the value differs ([com.privateime.core.privacy.PrivacySettings] equality). If
 * equality were identity-based, every focus change would rebuild the engine (cheap
 * but noisy); if it were too loose, a real change would be missed and a privacy
 * toggle would silently not apply. Both directions are asserted here.
 */
class SettingsEqualityTest {

    @Test
    fun `defaults are equal to a freshly constructed copy`() {
        assertEquals(PrivacySettings(), PrivacySettings())
    }

    @Test
    fun `a changed boolean is detected`() {
        assertNotEquals(PrivacySettings(), PrivacySettings(cloudSuggestionsEnabled = false))
        assertNotEquals(PrivacySettings(), PrivacySettings(clipboardSyncEnabled = false))
        assertNotEquals(PrivacySettings(), PrivacySettings(learnPiiLocally = true))
    }

    @Test
    fun `a changed map is detected by content`() {
        val a = PrivacySettings(appOverrides = mapOf("com.a" to Tier.LOCKED))
        val b = PrivacySettings(appOverrides = mapOf("com.a" to Tier.LOCKED))
        val c = PrivacySettings(appOverrides = mapOf("com.a" to Tier.OPEN))
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `a changed set is detected by content`() {
        val a = PrivacySettings(trustedSignatures = setOf("x", "y"))
        val b = PrivacySettings(trustedSignatures = setOf("y", "x"))
        val c = PrivacySettings(trustedSignatures = setOf("x"))
        assertEquals("set order must not matter", a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `hashCode is consistent with equals`() {
        val a = PrivacySettings(appOverrides = mapOf("p" to Tier.GUARDED), learnPiiLocally = true)
        val b = PrivacySettings(appOverrides = mapOf("p" to Tier.GUARDED), learnPiiLocally = true)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
