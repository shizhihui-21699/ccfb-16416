package com.privateime.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.UserDictionary
import com.privateime.core.lexicon.FieldMemory
import com.privateime.core.lexicon.LearnedWord
import com.privateime.core.lexicon.LearningStore
import com.privateime.core.model.Category
import java.io.Closeable

/**
 * The real store: our own SQLite database for words and field memory, plus
 * best-effort synchronisation with the platform user dictionary.
 *
 * ## Two dictionaries, one leak
 *
 * There are actually two places a typed word can end up:
 *
 *  1. **Our own store** — what this keyboard learned, used for its suggestions.
 *  2. **`android.provider.UserDictionary`** — the *system* dictionary, which any
 *     IME may read. A word written there is visible to every keyboard the user
 *     installs, and shows up in contacts-style suggestions across apps. This is
 *     what made the reported incident so visible.
 *
 * So learning writes to both (so that other keyboards do not break), and cleanup
 * deletes from both. Deletion from the system dictionary requires API 23+ write
 * access to the provider or `WRITE_USER_DICTIONARY`; the app holds that
 * permission and also offers the "open system settings" fallback when the
 * provider refuses (as it does for some OEM builds).
 *
 * ## Why not put everything in the system dictionary
 *
 * Because we need provenance, per-sink purge and risk metadata, none of which
 * the system provider has columns for.
 */
class SqliteLearningStore(context: Context) : LearningStore, Closeable {

    private val helper = DbHelper(context.applicationContext)
    private val resolver: ContentResolver? = try {
        context.applicationContext.contentResolver
    } catch (_: Exception) {
        null
    }

    override fun allWords(): List<LearnedWord> {
        val db = helper.readableDatabase
        val out = ArrayList<LearnedWord>(64)
        db.query(TABLE_WORDS, null, null, null, null, null, "last_seen DESC").use { c ->
            while (c.moveToNext()) {
                out.add(
                    LearnedWord(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        text = c.getString(c.getColumnIndexOrThrow("text")),
                        category = runCatching {
                            Category.valueOf(c.getString(c.getColumnIndexOrThrow("category")))
                        }.getOrDefault(Category.NONE),
                        packageName = c.getStringOrNull("package"),
                        occurrences = c.getInt(c.getColumnIndexOrThrow("occurrences")),
                        firstSeenMillis = c.getLong(c.getColumnIndexOrThrow("first_seen")),
                        lastSeenMillis = c.getLong(c.getColumnIndexOrThrow("last_seen")),
                        origin = runCatching {
                            LearnedWord.Origin.valueOf(c.getString(c.getColumnIndexOrThrow("origin")))
                        }.getOrDefault(LearnedWord.Origin.LOCAL_DICTIONARY),
                        riskScore = c.getDouble(c.getColumnIndexOrThrow("risk_score")),
                        riskCodes = c.getString(c.getColumnIndexOrThrow("risk_codes"))
                            ?.split(',')?.filter { it.isNotEmpty() } ?: emptyList(),
                    ),
                )
            }
        }
        return out
    }

    override fun findWord(text: String): LearnedWord? {
        // A direct indexed lookup. The previous `allWords().firstOrNull { ... }`
        // loaded and mapped the entire dictionary on every call, and the purge path
        // calls this once per token — an O(n·m) blowup with a large dictionary.
        val db = helper.readableDatabase
        db.query(
            TABLE_WORDS, null, "text = ?", arrayOf(text), null, null, "last_seen DESC", "1",
        ).use { c ->
            if (!c.moveToFirst()) return null
            return LearnedWord(
                id = c.getString(c.getColumnIndexOrThrow("id")),
                text = c.getString(c.getColumnIndexOrThrow("text")),
                category = runCatching {
                    Category.valueOf(c.getString(c.getColumnIndexOrThrow("category")))
                }.getOrDefault(Category.NONE),
                packageName = c.getStringOrNull("package"),
                occurrences = c.getInt(c.getColumnIndexOrThrow("occurrences")),
                firstSeenMillis = c.getLong(c.getColumnIndexOrThrow("first_seen")),
                lastSeenMillis = c.getLong(c.getColumnIndexOrThrow("last_seen")),
                origin = runCatching {
                    LearnedWord.Origin.valueOf(c.getString(c.getColumnIndexOrThrow("origin")))
                }.getOrDefault(LearnedWord.Origin.LOCAL_DICTIONARY),
                riskScore = c.getDouble(c.getColumnIndexOrThrow("risk_score")),
                riskCodes = c.getString(c.getColumnIndexOrThrow("risk_codes"))
                    ?.split(',')?.filter { it.isNotEmpty() } ?: emptyList(),
            )
        }
    }

    override fun remember(
        text: String,
        category: Category,
        packageName: String?,
        origin: LearnedWord.Origin,
        nowMillis: Long,
    ): Boolean {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val existing = db.query(
                TABLE_WORDS, arrayOf("id", "occurrences", "first_seen"),
                "text = ? AND origin = ?", arrayOf(text, origin.name), null, null, null,
            ).use { c -> if (c.moveToFirst()) Triple(c.getString(0), c.getInt(1), c.getLong(2)) else null }

            if (existing == null) {
                val values = ContentValues().apply {
                    put("id", java.util.UUID.randomUUID().toString())
                    put("text", text)
                    put("category", category.name)
                    put("package", packageName)
                    put("occurrences", 1)
                    put("first_seen", nowMillis)
                    put("last_seen", nowMillis)
                    put("origin", origin.name)
                    put("risk_score", 0.0)
                    put("risk_codes", "")
                }
                db.insertOrThrow(TABLE_WORDS, null, values)
            } else {
                val values = ContentValues().apply {
                    put("occurrences", existing.second + 1)
                    put("last_seen", nowMillis)
                    if (category != Category.NONE) put("category", category.name)
                    // Keep the first-seen package: that is the app that leaked it.
                    if (packageName != null) put("package_first_seen", packageName)
                }
                db.update(TABLE_WORDS, values, "id = ?", arrayOf(existing.first))
            }
            db.setTransactionSuccessful()
        } catch (_: Exception) {
            return false
        } finally {
            db.endTransaction()
        }

        // Mirror to the platform dictionary only for *ordinary* words. Credentials
        // never reach here (the gate refuses them), and PII is deliberately kept
        // in the private store only so that other keyboards cannot read an e-mail
        // address this one learned.
        if (category == Category.NONE && origin == LearnedWord.Origin.LOCAL_DICTIONARY) {
            mirrorToSystemDictionary(text)
        }
        return true
    }

    override fun forget(text: String): Boolean = forgetAll(listOf(text)) > 0

    override fun forgetAll(texts: Collection<String>): Int {
        if (texts.isEmpty()) return 0
        val db = helper.writableDatabase
        var removed = 0
        db.beginTransaction()
        try {
            for (text in texts) {
                removed += db.delete(TABLE_WORDS, "text = ?", arrayOf(text))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        // The system dictionary is the one other keyboards see; deleting only our
        // row would leave the leak visible in every other app.
        for (text in texts) removeFromSystemDictionary(text)
        return removed
    }

    override fun purgeSink(origin: LearnedWord.Origin): Int {
        val db = helper.writableDatabase
        val texts = ArrayList<String>(16)
        db.query(TABLE_WORDS, arrayOf("text"), "origin = ?", arrayOf(origin.name), null, null, null)
            .use { c -> while (c.moveToNext()) texts.add(c.getString(0)) }
        val removed = db.delete(TABLE_WORDS, "origin = ?", arrayOf(origin.name))
        if (origin == LearnedWord.Origin.LOCAL_DICTIONARY) {
            texts.forEach { removeFromSystemDictionary(it) }
        }
        return removed
    }

    // ---------------------------------------------------------------- fields

    override fun fieldMemory(signatureKey: String): FieldMemory? {
        val db = helper.readableDatabase
        db.query(TABLE_FIELDS, null, "signature = ?", arrayOf(signatureKey), null, null, null).use { c ->
            if (!c.moveToFirst()) return null
            return FieldMemory(
                signatureKey = signatureKey,
                packageName = c.getString(c.getColumnIndexOrThrow("package")),
                category = runCatching {
                    Category.valueOf(c.getString(c.getColumnIndexOrThrow("category")))
                }.getOrDefault(Category.NONE),
                confidence = c.getString(c.getColumnIndexOrThrow("confidence")) ?: "NONE",
                sticky = c.getInt(c.getColumnIndexOrThrow("sticky")) != 0,
                learnedAtMillis = c.getLong(c.getColumnIndexOrThrow("learned_at")),
                lastSeenMillis = c.getLong(c.getColumnIndexOrThrow("last_seen")),
                timesSeen = c.getInt(c.getColumnIndexOrThrow("times_seen")),
                safeLabel = c.getStringOrNull("safe_label"),
            )
        }
    }

    override fun rememberField(memory: FieldMemory) {
        val values = ContentValues().apply {
            put("signature", memory.signatureKey)
            put("package", memory.packageName)
            put("category", memory.category.name)
            put("confidence", memory.confidence)
            put("sticky", if (memory.sticky) 1 else 0)
            put("learned_at", memory.learnedAtMillis)
            put("last_seen", memory.lastSeenMillis)
            put("times_seen", memory.timesSeen)
            put("safe_label", memory.safeLabel)
        }
        helper.writableDatabase.insertWithOnConflict(
            TABLE_FIELDS, null, values, SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun forgetField(signatureKey: String) {
        helper.writableDatabase.delete(TABLE_FIELDS, "signature = ?", arrayOf(signatureKey))
    }

    override fun allFieldMemories(): List<FieldMemory> {
        val db = helper.readableDatabase
        val out = ArrayList<FieldMemory>(16)
        db.query(TABLE_FIELDS, null, null, null, null, null, "last_seen DESC").use { c ->
            while (c.moveToNext()) {
                out.add(
                    FieldMemory(
                        signatureKey = c.getString(c.getColumnIndexOrThrow("signature")),
                        packageName = c.getString(c.getColumnIndexOrThrow("package")) ?: "",
                        category = runCatching {
                            Category.valueOf(c.getString(c.getColumnIndexOrThrow("category")))
                        }.getOrDefault(Category.NONE),
                        confidence = c.getString(c.getColumnIndexOrThrow("confidence")) ?: "NONE",
                        sticky = c.getInt(c.getColumnIndexOrThrow("sticky")) != 0,
                        learnedAtMillis = c.getLong(c.getColumnIndexOrThrow("learned_at")),
                        lastSeenMillis = c.getLong(c.getColumnIndexOrThrow("last_seen")),
                        timesSeen = c.getInt(c.getColumnIndexOrThrow("times_seen")),
                        safeLabel = c.getStringOrNull("safe_label"),
                    ),
                )
            }
        }
        return out
    }

    /** Records the risk metadata the scanner computed so the next scan is cheap. */
    fun saveRisk(texts: Map<String, Pair<Double, List<String>>>) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for ((text, risk) in texts) {
                val values = ContentValues().apply {
                    put("risk_score", risk.first)
                    put("risk_codes", risk.second.joinToString(","))
                }
                db.update(TABLE_WORDS, values, "text = ?", arrayOf(text))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ------------------------------------------------- system dictionary mirror

    private fun mirrorToSystemDictionary(text: String) {
        val resolver = resolver ?: return
        try {
            val locales = android.os.LocaleList.getDefault()
            val locale = if (locales.isEmpty) java.util.Locale.getDefault() else locales[0]
            val values = ContentValues().apply {
                put(UserDictionary.Words.WORD, text)
                put(UserDictionary.Words.LOCALE, locale.toString())
                put(UserDictionary.Words.FREQUENCY, 100)
                put(UserDictionary.Words.APP_ID, "")
            }
            resolver.insert(UserDictionary.Words.CONTENT_URI, values)
        } catch (_: Exception) {
            // Provider absent or read-only on this ROM; our own store still works.
        }
    }

    private fun removeFromSystemDictionary(text: String) {
        val resolver = resolver ?: return
        try {
            resolver.delete(
                UserDictionary.Words.CONTENT_URI,
                "${UserDictionary.Words.WORD} = ?",
                arrayOf(text),
            )
        } catch (_: Exception) {
            // Ignored: cleanup still removed the private copy; the UI tells the
            // user how to finish manually if the provider refused.
        }
    }

    override fun close() {
        helper.close()
    }

    private class DbHelper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE_WORDS (
                    id TEXT PRIMARY KEY,
                    text TEXT NOT NULL,
                    category TEXT NOT NULL,
                    package TEXT,
                    package_first_seen TEXT,
                    occurrences INTEGER NOT NULL DEFAULT 1,
                    first_seen INTEGER NOT NULL,
                    last_seen INTEGER NOT NULL,
                    origin TEXT NOT NULL,
                    risk_score REAL NOT NULL DEFAULT 0,
                    risk_codes TEXT
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE UNIQUE INDEX idx_words_text_origin ON $TABLE_WORDS(text, origin)")
            db.execSQL("CREATE INDEX idx_words_last_seen ON $TABLE_WORDS(last_seen)")
            db.execSQL(
                """
                CREATE TABLE $TABLE_FIELDS (
                    signature TEXT PRIMARY KEY,
                    package TEXT NOT NULL,
                    category TEXT NOT NULL,
                    confidence TEXT NOT NULL,
                    sticky INTEGER NOT NULL DEFAULT 0,
                    learned_at INTEGER NOT NULL,
                    last_seen INTEGER NOT NULL,
                    times_seen INTEGER NOT NULL DEFAULT 1,
                    safe_label TEXT
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Destructive upgrade is acceptable here and is the safer default:
            // the only data is learned words, and keeping stale schema around
            // risks losing the provenance that cleanup depends on.
            db.execSQL("DROP TABLE IF EXISTS $TABLE_WORDS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_FIELDS")
            onCreate(db)
        }
    }

    private companion object {
        const val DB_NAME = "private_ime_learning.db"
        const val DB_VERSION = 1
        const val TABLE_WORDS = "learned_words"
        const val TABLE_FIELDS = "field_memory"
    }
}

private fun android.database.Cursor.getStringOrNull(column: String): String? {
    val idx = getColumnIndexOrThrow(column)
    return if (isNull(idx)) null else getString(idx)
}
