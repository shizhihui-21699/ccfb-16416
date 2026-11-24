package com.privateime.core.lexicon

import com.privateime.core.model.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Display masking is a privacy surface: the cleanup screen shows leaked credentials,
 * and any other app can screenshot it. These tests pin the guarantees.
 */
class MaskingTest {

    private fun word(text: String, category: Category = Category.PASSWORD) =
        LearnedWord(
            id = "w",
            text = text,
            category = category,
            firstSeenMillis = 1,
            lastSeenMillis = 1,
        )

    @Test
    fun `short credentials reveal nothing of the value`() {
        for (pin in listOf("1234", "12345", "123456", "Abc123!")) {
            val masked = word(pin).displayText()
            // No character of the original appears before the tag.
            val body = masked.substringBefore(" #")
            assertTrue("$pin leaked: $masked", body.all { it == '•' })
        }
    }

    @Test
    fun `long credentials reveal only head and tail`() {
        val masked = word("4111111111111111").displayText()
        assertTrue(masked.startsWith("41"))
        assertTrue(masked.contains("11"))
        assertFalse("the middle must be hidden", masked.contains("1111111111"))
    }

    @Test
    fun `two similar cards are distinguishable`() {
        val a = word("4111111111111111").displayText()
        val b = word("4111111111112222").displayText()
        assertNotEquals("masked entries must be distinguishable in the UI", a, b)
    }

    @Test
    fun `reveal shows the real value only when explicitly asked`() {
        assertEquals("hunter2pass", word("hunter2pass").displayText(reveal = true))
    }

    @Test
    fun `ordinary words are shown in full`() {
        // Non-secret entries are shown so the user can recognise them.
        assertEquals("明天", word("明天", Category.NONE).displayText())
        assertEquals("someone@example.com", word("someone@example.com", Category.PII).displayText())
    }

    @Test
    fun `mask is stable across calls`() {
        assertEquals(word("4111111111111111").displayText(), word("4111111111111111").displayText())
    }
}
