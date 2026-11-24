package com.privateime.core.text

/**
 * Splits committed text into the units that may be learned.
 *
 * The tokenizer sits on the privacy boundary, so its rules matter as much as the
 * detector's:
 *
 *  * A pasted paragraph must never become dictionary entries at all. A run of
 *    characters longer than [MAX_TOKEN] is **dropped whole**, not split: chunking it
 *    would turn a pasted document — or a pasted secret — into several smaller
 *    entries that each still leak a fragment.
 *  * Ordinary CJK sentences are short (people type a phrase at a time), but a
 *    long unspaced run that is still within [MAX_TOKEN] is fanned out at
 *    [MAX_CJK_RUN] so that one sentence does not become one oversized entry.
 *  * Punctuation terminates a token and is never part of it: `密码是Abc123!` yields
 *    `密码是Abc123`, and the `!` is dropped rather than learned.
 *  * Zero-width characters are *not* delimiters. A card number pasted from a web
 *    page often carries them, and splitting on them would defeat Luhn detection.
 */
object WordTokenizer {

    /** Longest piece a long-but-legal run is fanned out into. */
    const val MAX_CJK_RUN = 24

    /**
     * Longest run that may be learned at all. Anything longer is treated as pasted
     * text rather than a typed word.
     */
    const val MAX_TOKEN = 64

    /** Punctuation and whitespace that terminate a token. */
    private val DELIMITERS: Set<Char> = buildSet {
        for (ch in " \t\n\r 　") add(ch)
        // Full-width CJK punctuation is listed explicitly: it is not a Unicode
        // connector, and without it "今天，天气很好" becomes one token.
        for (ch in "，。！？、；：（）【】《》「」『』〔〕｛｝…—·～") add(ch)
        for (ch in "\"'“”‘’") add(ch)
        for (ch in ",.;:!?\"'()[]{}<>|/\\@#$%^&*+=~`^_") add(ch)
    }

    /** Formatting marks that are ignored rather than treated as separators. */
    private val IGNORED: Set<Char> = setOf('​', '‌', '‍', '﻿', '­')

    /**
     * @return tokens in order of appearance. Never returns blanks, never returns a
     *   token longer than [MAX_TOKEN], and never returns fragments of an over-long
     *   run.
     */
    fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>(4)
        val run = StringBuilder()
        var overlong = false
        for (ch in text) {
            when {
                ch in IGNORED -> Unit // drop the character, keep the run intact
                ch in DELIMITERS -> {
                    flushRun(run, overlong, out)
                    overlong = false
                }
                else -> {
                    if (run.length < MAX_TOKEN + 1) run.append(ch)
                    if (run.length > MAX_TOKEN) overlong = true
                }
            }
        }
        flushRun(run, overlong, out)
        return out
    }

    private fun flushRun(run: StringBuilder, overlong: Boolean, out: MutableList<String>) {
        if (run.isEmpty()) return
        val token = run.toString()
        run.setLength(0)
        if (overlong) return // pasted text, not a word
        if (token.length > MAX_TOKEN) return
        if (token.none { it.isLetterOrDigit() }) return
        if (token.length <= MAX_CJK_RUN) {
            out.add(token)
            return
        }
        // Long-but-legal run (a CJK sentence): fan out so one sentence does not
        // become a single oversized entry.
        var start = 0
        while (start < token.length) {
            val end = minOf(start + MAX_CJK_RUN, token.length)
            val piece = token.substring(start, end)
            if (piece.any { it.isLetterOrDigit() }) out.add(piece)
            start = end
        }
    }
}
