package com.privateime.core.text

import java.text.Normalizer
import java.util.Locale

/**
 * Text normalization for matching, never for display or storage.
 *
 * Real hint strings are messy: full-width punctuation, ideographic spaces,
 * Cyrillic/Greek lookalikes, and CJK text without spaces. Matching needs one
 * canonical shape; the UI needs the original.
 */
object TextNormalizer {

    /**
     * Folds text for matching:
     *  * NFKC (full-width → ASCII, ligatures, compatibility forms)
     *  * lowercase (locale-independent to avoid the Turkish i problem)
     *  * strips zero-width and formatting characters
     *  * unifies common separator variants to a single space
     */
    fun fold(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        val nfkc = Normalizer.normalize(input, Normalizer.Form.NFKC)
        val sb = StringBuilder(nfkc.length)
        for (ch in nfkc) {
            when (ch) {
                '​', '‌', '‍', '﻿', '­' -> Unit
                '　', '\t', '\n', '\r', ' ' -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.toString().lowercase(Locale.ROOT)
    }

    /** Folds and removes all whitespace and punctuation. Used for keyword hits. */
    fun compact(input: String?): String {
        val folded = fold(input)
        val sb = StringBuilder(folded.length)
        for (ch in folded) {
            if (ch.isLetterOrDigit()) sb.append(ch)
        }
        return sb.toString()
    }

    /** True when the text is overwhelmingly a digit run, allowing separators. */
    fun digitsOnly(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        val sb = StringBuilder(input.length)
        for (ch in normalizeDigits(input)) {
            if (ch in '0'..'9') sb.append(ch)
        }
        return sb.toString()
    }

    /** Maps Arabic-Indic, Devanagari and full-width digits to ASCII. */
    fun normalizeDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val mapped = when (ch) {
                in '٠'..'٩' -> '0' + (ch - '٠') // Arabic-Indic
                in '۰'..'۹' -> '0' + (ch - '۰') // Extended Arabic-Indic
                in '०'..'९' -> '0' + (ch - '०') // Devanagari
                in '０'..'９' -> '0' + (ch - '０') // Full width
                in 'Ａ'..'Ｚ' -> 'A' + (ch - 'Ａ') // Full-width latin
                in 'ａ'..'ｚ' -> 'a' + (ch - 'ａ')
                else -> ch
            }
            sb.append(mapped)
        }
        return sb.toString()
    }

    /** Collapses whitespace and trims. Used before storing a user-visible label. */
    fun collapseWhitespace(input: String): String =
        input.replace(Regex("\\s+"), " ").trim()

    /**
     * Display-safe truncation that never splits a surrogate pair.
     */
    fun truncate(input: String, maxCodePoints: Int): String {
        if (maxCodePoints <= 0) return ""
        var count = 0
        var index = 0
        while (index < input.length && count < maxCodePoints) {
            index += Character.charCount(input.codePointAt(index))
            count++
        }
        return if (index >= input.length) input else input.substring(0, index)
    }
}
