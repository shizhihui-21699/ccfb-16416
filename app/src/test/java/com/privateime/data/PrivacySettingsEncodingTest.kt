package com.privateime.data

import com.privateime.core.privacy.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for the settings encoding.
 *
 * The app-override map is serialized into a single preference string. A bug here
 * does not announce itself: the user's "this app is fine" choice silently fails to
 * survive a restart and the keyboard starts locking their chat box again. So the
 * encoding is tested directly, including hostile inputs.
 */
class PrivacySettingsEncodingTest {

    @Test
    fun `empty map round trips`() {
        assertEquals(emptyMap<String, Tier>(), PrivacySettingsStore.parseAppOverrides(null))
        assertEquals(emptyMap<String, Tier>(), PrivacySettingsStore.parseAppOverrides(""))
    }

    @Test
    fun `single override round trips`() {
        val map = mapOf("com.example.app" to Tier.LOCKED)
        val encoded = PrivacySettingsStore.encodeAppOverrides(map)
        assertEquals(map, PrivacySettingsStore.parseAppOverrides(encoded))
    }

    @Test
    fun `multiple overrides round trip`() {
        val map = linkedMapOf(
            "com.a" to Tier.OPEN,
            "com.b.c" to Tier.GUARDED,
            "com.d" to Tier.LOCKED,
        )
        val encoded = PrivacySettingsStore.encodeAppOverrides(map)
        assertEquals(map, PrivacySettingsStore.parseAppOverrides(encoded))
    }

    @Test
    fun `package names containing colons survive`() {
        // Not a valid package name, but a hostile or corrupted value must not be
        // able to shift the split point and silently change another app's tier.
        val map = mapOf("weird:name" to Tier.LOCKED)
        val encoded = PrivacySettingsStore.encodeAppOverrides(map)
        assertEquals(map, PrivacySettingsStore.parseAppOverrides(encoded))
    }

    @Test
    fun `malformed entries are skipped not fatal`() {
        val parsed = PrivacySettingsStore.parseAppOverrides(
            "com.a:OPEN;garbage;:LOCKED;com.b:NOT_A_TIER;com.c:GUARDED",
        )
        assertEquals(mapOf("com.a" to Tier.OPEN, "com.c" to Tier.GUARDED), parsed)
    }

    @Test
    fun `duplicate package keeps the last value`() {
        val parsed = PrivacySettingsStore.parseAppOverrides("com.a:OPEN;com.a:LOCKED")
        assertEquals(Tier.LOCKED, parsed["com.a"])
    }

    @Test
    fun `category label key is stable`() {
        assertEquals("category.password", PrivacySettingsStore.categoryLabelKey(com.privateime.core.model.Category.PASSWORD))
        assertEquals("category.none", PrivacySettingsStore.categoryLabelKey(com.privateime.core.model.Category.NONE))
    }
}
