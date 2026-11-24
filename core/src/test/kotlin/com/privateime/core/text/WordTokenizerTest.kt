package com.privateime.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tokenizer decides what *may be learned at all*, so its behaviour is a
 * privacy property, not a convenience. Each test documents a rule and the failure
 * mode it prevents.
 */
class WordTokenizerTest {

    @Test
    fun `latin words split on spaces and punctuation`() {
        assertEquals(
            listOf("hello", "world", "foo"),
            WordTokenizer.tokenize("hello, world! foo."),
        )
    }

    @Test
    fun `chinese punctuation splits tokens`() {
        assertEquals(
            listOf("今天", "天气很好"),
            WordTokenizer.tokenize("今天，天气很好。"),
        )
    }

    @Test
    fun `a pasted paragraph is dropped whole, never split into entries`() {
        // The failure this prevents: a whole document (or a pasted secret) becoming
        // several smaller dictionary entries that each still leak a fragment.
        val paragraph = "这是一段很长的文字".repeat(20)
        assertTrue(
            "an over-long run must be dropped, not chunked",
            WordTokenizer.tokenize(paragraph).isEmpty(),
        )
    }

    @Test
    fun `a long but legal cjk sentence fans out into bounded pieces`() {
        // Under MAX_TOKEN but over MAX_CJK_RUN: split so one sentence is not one entry.
        val sentence = "字".repeat(WordTokenizer.MAX_CJK_RUN * 2)
        val tokens = WordTokenizer.tokenize(sentence)
        assertEquals(2, tokens.size)
        assertEquals(WordTokenizer.MAX_CJK_RUN, tokens[0].length)
    }

    @Test
    fun `a token longer than the cap is dropped not truncated`() {
        // Truncating would keep a fragment of a credential; dropping is safer.
        val long = "a".repeat(WordTokenizer.MAX_TOKEN + 1)
        val tokens = WordTokenizer.tokenize(long)
        assertTrue("over-long tokens are dropped entirely", tokens.isEmpty())
    }

    @Test
    fun `urlish strings are split at their separators`() {
        val tokens = WordTokenizer.tokenize("https://example.com/path?q=1")
        assertFalse("no token contains a slash", tokens.any { it.contains('/') })
        assertFalse("no token contains a colon", tokens.any { it.contains(':') })
    }

    @Test
    fun `digit runs survive as single tokens`() {
        // The scanner needs the whole run to run Luhn; splitting digits would make
        // card detection impossible.
        assertEquals(listOf("4111111111111111"), WordTokenizer.tokenize("4111111111111111"))
        assertEquals(listOf("4111", "1111"), WordTokenizer.tokenize("4111 1111"))
    }

    @Test
    fun `emoji and symbols alone are not tokens`() {
        assertTrue(WordTokenizer.tokenize("🎉🎉 🎉").isEmpty())
        assertTrue(WordTokenizer.tokenize("!!! ??? ...").isEmpty())
    }

    @Test
    fun `blank input yields nothing`() {
        assertTrue(WordTokenizer.tokenize("").isEmpty())
        assertTrue(WordTokenizer.tokenize("   \n\t  ").isEmpty())
        assertTrue(WordTokenizer.tokenize("　　").isEmpty())
    }

    @Test
    fun `mixed scripts stay in one token when unspaced`() {
        assertEquals(listOf("密码是Abc123"), WordTokenizer.tokenize("密码是Abc123!"))
    }

    @Test
    fun `zero width characters are not treated as delimiters`() {
        // A zero-width space pasted from a web page must not silently split a card
        // number into pieces that each fail Luhn.
        val tokens = WordTokenizer.tokenize("4111​1111​1111​1111")
        assertEquals(1, tokens.size)
        assertTrue(tokens[0].contains("4111"))
    }
}
