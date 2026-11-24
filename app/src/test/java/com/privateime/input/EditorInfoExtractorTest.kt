package com.privateime.input

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.privateime.core.model.InputTypeNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the `EditorInfo` → `FieldSignals` mapping.
 *
 * These run on the JVM against the real `android.text.InputType` constants from
 * the SDK stub jar, so a mismatch between the core module's mirrored constants and
 * the platform is caught here rather than on a device.
 */
class EditorInfoExtractorTest {

    @Test
    fun `first focus produces expected defaults`() {
        val info = EditorInfo().apply { packageName = "com.example" }
        val signals = EditorInfoExtractor.extract(info)
        assertEquals("com.example", signals.packageName)
        assertEquals(0, signals.imeOptions)
        assertNull(signals.hintText)
    }

    @Test
    fun `hint text is carried through`() {
        val info = EditorInfo().apply {
            packageName = "com.example"
            hintText = "请输入密码"
        }
        assertEquals("请输入密码", EditorInfoExtractor.extract(info).hintText)
    }

    @Test
    fun `null hint and label are tolerated`() {
        val info = EditorInfo().apply { packageName = "com.example" }
        val signals = EditorInfoExtractor.extract(info)
        assertNull(signals.hintText)
        assertNull(signals.label)
    }

    @Test
    fun `type null means no text entry`() {
        val info = EditorInfo().apply {
            packageName = "com.example"
            inputType = InputType.TYPE_NULL
        }
        assertNull(
            "TYPE_NULL must stay null so callers can distinguish it",
            EditorInfoExtractor.extract(info).inputType,
        )
    }

    @Test
    fun `no personalized learning flag survives extraction`() {
        val info = EditorInfo().apply {
            packageName = "com.example"
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        val signals = EditorInfoExtractor.extract(info)
        assertTrue(signals.noPersonalizedLearning)
    }

    @Test
    fun `field name is picked up`() {
        val info = EditorInfo().apply {
            packageName = "com.example"
            fieldName = "password"
        }
        assertEquals("password", EditorInfoExtractor.extract(info).fieldName)
    }

    @Test
    fun `field name is parsed from private options when absent`() {
        val info = EditorInfo().apply {
            packageName = "com.chrome"
            privateImeOptions = "nm=football,fn=password_field"
        }
        assertEquals("password_field", EditorInfoExtractor.extract(info).fieldName)
    }

    @Test
    fun `chrome style private options without a field name yield null`() {
        val info = EditorInfo().apply {
            packageName = "com.chrome"
            privateImeOptions = "com.google.android.inputmethod.latin.noMicrophoneKey"
        }
        assertNull(EditorInfoExtractor.extract(info).fieldName)
    }

    /**
     * The mirrored constants in `core` must agree with the platform. If they drift,
     * every detection decision silently changes meaning, so the values are asserted
     * against the SDK constants rather than hard-coded.
     */
    @Test
    fun `mirrored input type constants match the platform`() {
        assertEquals(InputType.TYPE_CLASS_TEXT, InputTypeNames.CLASS_TEXT)
        assertEquals(InputType.TYPE_CLASS_NUMBER, InputTypeNames.CLASS_NUMBER)
        assertEquals(InputType.TYPE_CLASS_PHONE, InputTypeNames.CLASS_PHONE)
        assertEquals(InputType.TYPE_MASK_CLASS, InputTypeNames.MASK_CLASS)
        assertEquals(InputType.TYPE_MASK_VARIATION, InputTypeNames.MASK_VARIATION)

        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputTypeNames.TEXT_VARIATION_PASSWORD)
        assertEquals(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputTypeNames.TEXT_VARIATION_VISIBLE_PASSWORD)
        assertEquals(InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputTypeNames.TEXT_VARIATION_WEB_PASSWORD)
        assertEquals(InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT, InputTypeNames.TEXT_VARIATION_WEB_EDIT_TEXT)
        assertEquals(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputTypeNames.TEXT_VARIATION_EMAIL_ADDRESS)
        assertEquals(InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputTypeNames.TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        assertEquals(InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS, InputTypeNames.TEXT_VARIATION_POSTAL_ADDRESS)
        assertEquals(InputType.TYPE_NUMBER_VARIATION_PASSWORD, InputTypeNames.NUMBER_VARIATION_PASSWORD)
        assertEquals(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, InputTypeNames.FLAG_NO_SUGGESTIONS)
    }

    @Test
    fun `mirrored ime flag matches the platform`() {
        assertEquals(
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            com.privateime.core.model.ImeFlags.NO_PERSONALIZED_LEARNING,
        )
    }

    @Test
    fun `describe covers the variations apps actually use`() {
        assertEquals("TEXT/password", InputTypeNames.describe(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertEquals("TEXT/visiblePassword", InputTypeNames.describe(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertEquals("NUMBER/normal", InputTypeNames.describe(InputType.TYPE_CLASS_NUMBER))
        assertEquals("NUMBER/password", InputTypeNames.describe(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
    }

    @Test
    fun `type null is reported as null not zero`() {
        val info = EditorInfo().apply {
            packageName = "com.example"
            inputType = InputType.TYPE_NULL
        }
        assertNull(
            "TYPE_NULL must stay null so callers can distinguish it from plain text",
            EditorInfoExtractor.extract(info).inputType,
        )
    }

    @Test
    fun `a bare password variation without a class is preserved`() {
        // TYPE_NULL is 0, so `0 or TYPE_TEXT_VARIATION_PASSWORD` is exactly 0x80 —
        // a password variation with no class bits. That is a real (if odd) value an
        // app can send, and the detector treats class-less variations as
        // unclassifiable rather than as plain text. The extractor must not strip it.
        val info = EditorInfo().apply {
            packageName = "com.example"
            inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfoExtractor.extract(info).inputType)
    }
}
