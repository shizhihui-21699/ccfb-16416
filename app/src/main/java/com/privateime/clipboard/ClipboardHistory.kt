package com.privateime.clipboard

import android.content.Context
import android.content.SharedPreferences
import com.privateime.core.model.Category
import com.privateime.core.text.TextNormalizer
import android.util.Log

/**
 * A bounded, persisted clipboard history.
 *
 * Deliberately small (the last [CAPACITY] entries) and short-lived ([TTL_MILLIS]),
 * because a clipboard history is a liability: every entry is something the user
 * copied, which historically includes one-time codes they were about to paste into
 * a login screen.
 *
 * **At rest** the payload is AES-GCM encrypted with a non-exportable Android
 * Keystore key (see [KeystoreCrypto]). When the Keystore is unavailable the
 * history *disables itself* rather than falling back to cleartext — a keyboard
 * that cannot encrypt what it captured must not capture it.
 */
class ClipboardHistory(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** False when encryption is unavailable; callers must then skip capture entirely. */
    val available: Boolean
        get() = KeystoreCrypto.encrypt("probe") != null

    /** Diagnostics counter: how often capture was refused, and why. */
    fun recordSuppressed(reason: String) {
        val count = prefs.getInt(KEY_SUPPRESSED_COUNT, 0) + 1
        prefs.edit()
            .putInt(KEY_SUPPRESSED_COUNT, count)
            .putString(KEY_LAST_REASON, reason.take(120))
            .apply()
    }

    fun append(text: String) {
        val entry = text.trim()
        if (entry.isEmpty() || entry.length > MAX_ENTRY) return
        val encrypted = KeystoreCrypto.encrypt(entry) ?: return
        val now = System.currentTimeMillis()
        val current = readRaw()
        // Drop duplicates so re-copying a phrase does not accumulate.
        val deduped = current.filterNot { it.text == entry }
        val updated = (listOf(StoredEntry(now, encrypted)) + deduped).take(CAPACITY)
        writeRaw(updated)
    }

    /** Entries, newest first. Unreadable entries are silently dropped. */
    fun entries(): List<String> = readRaw().mapNotNull { KeystoreCrypto.decrypt(it.blob) }

    /**
     * Removes every entry matching [predicate].
     * @return number removed
     */
    fun scrub(predicate: (String) -> Boolean): Int {
        val current = readRaw()
        val kept = ArrayList<StoredEntry>(current.size)
        var removed = 0
        for (entry in current) {
            val plain = KeystoreCrypto.decrypt(entry.blob)
            if (plain != null && predicate(plain)) {
                removed++
            } else {
                kept.add(entry)
            }
        }
        if (removed > 0) writeRaw(kept)
        return removed
    }

    fun clear() {
        prefs.edit().remove(KEY_ENTRIES).apply()
        // Dropping the key makes any blob that survived in a backup unreadable.
        KeystoreCrypto.destroyKey()
    }

    fun suppressedCount(): Int = prefs.getInt(KEY_SUPPRESSED_COUNT, 0)

    fun lastSuppressedReason(): String? = prefs.getString(KEY_LAST_REASON, null)

    /** Drops entries older than the TTL; call on app start and before offering. */
    fun pruneExpired(nowMillis: Long = System.currentTimeMillis()): Int {
        val current = readRaw()
        val kept = current.filter { nowMillis - it.timestamp <= TTL_MILLIS }
        val removed = current.size - kept.size
        if (removed > 0) writeRaw(kept)
        return removed
    }

    /** History suggestions must never include something credential-shaped. */
    fun offerable(canOffer: Boolean): List<String> {
        if (!canOffer) return emptyList()
        return entries().filterNot { looksSensitive(it) }
    }

    private fun looksSensitive(text: String): Boolean {
        val digits = TextNormalizer.digitsOnly(text)
        return digits.length in 4..19 &&
            text.count { !it.isDigit() && !it.isWhitespace() } == 0
    }

    private data class StoredEntry(val timestamp: Long, val blob: String) {
        val text: String get() = KeystoreCrypto.decrypt(blob) ?: ""
    }

    private fun readRaw(): List<StoredEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        val out = ArrayList<StoredEntry>(CAPACITY)
        for (line in raw.split('\u001E')) {
            val sep = line.indexOf('\u001F')
            if (sep <= 0) continue
            val ts = line.substring(0, sep).toLongOrNull() ?: continue
            out.add(StoredEntry(ts, line.substring(sep + 1)))
        }
        return out
    }

    private fun writeRaw(entries: List<StoredEntry>) {
        val encoded = entries.joinToString("\u001E") { it.timestamp.toString() + "\u001F" + it.blob }
        try {
            prefs.edit().putString(KEY_ENTRIES, encoded).apply()
        } catch (e: Exception) {
            // A full disk or a blocked preference write must not crash the IME.
            Log.w(TAG, "clipboard history write failed", e)
        }
    }

    private companion object {
        const val TAG = "ClipboardHistory"
        const val FILE = "clipboard_history"
        const val KEY_ENTRIES = "entries"
        const val KEY_SUPPRESSED_COUNT = "suppressed_count"
        const val KEY_LAST_REASON = "last_reason"
        const val CAPACITY = 20
        const val MAX_ENTRY = 2_000
        const val TTL_MILLIS = 24L * 60 * 60 * 1000

        /** Exposed for tests and the audit view. */
        fun categoryOf(text: String): Category =
            com.privateime.core.detect.ContentClassifier.residueEvidence(text)
                .find { it.category.isSecret }?.category ?: Category.NONE
    }
}
