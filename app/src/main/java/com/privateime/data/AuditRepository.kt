package com.privateime.data

import android.content.Context
import com.privateime.core.engine.AuditLog
import com.privateime.core.model.Category
import com.privateime.core.privacy.Tier
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Persists the content-free audit trail.
 *
 * ## Why this exists
 *
 * Two requirements collide in every privacy incident report: "prove the shield
 * worked" and "never store what the user typed". The resolution is to record only
 * decisions — *which* app, *when*, *what tier*, *which rule fired* — and nothing
 * derived from the content. There is no text field in [AuditLog.Entry] and this
 * repository does not add one.
 *
 * Bounded to [CAPACITY] entries, newest first, persisted as a JSON array in
 * SharedPreferences. Writes happen at focus transitions, not per keystroke.
 */
class AuditRepository(context: Context) : AuditLog {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val buffer = ConcurrentLinkedDeque<AuditLog.Entry>()

    init {
        load()
    }

    @Synchronized
    override fun record(entry: AuditLog.Entry) {
        buffer.addFirst(entry)
        while (buffer.size > CAPACITY) buffer.removeLast()
        persist()
    }

    override fun recent(limit: Int): List<AuditLog.Entry> =
        buffer.take(limit.coerceAtLeast(0))

    @Synchronized
    override fun clear() {
        buffer.clear()
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    /**
     * Records that a credential-shaped token was refused or revoked.
     *
     * Deliberately takes no token and records no length: even a length is
     * content-derived metadata, and an audit log that accumulates it can narrow
     * down what the user typed. The event itself (a revocation happened, in this
     * app, at this time) is all the audit needs.
     */
    fun recordCredentialRevoked(packageName: String) {
        record(
            AuditLog.Entry(
                atMillis = System.currentTimeMillis(),
                packageName = packageName,
                fieldShape = "", // deliberately empty: no field context available here
                tier = Tier.LOCKED,
                category = Category.UNKNOWN_SECRET,
                codes = listOf("learn.revoked"),
                transition = "revoke",
            ),
        )
    }

    fun recordSessionPurge(count: Int, packageName: String) {
        record(
            AuditLog.Entry(
                atMillis = System.currentTimeMillis(),
                packageName = packageName,
                fieldShape = "",
                tier = Tier.LOCKED,
                category = Category.UNKNOWN_SECRET,
                codes = listOf("learn.sessionPurge", "count=$count"),
                transition = "revoke",
            ),
        )
    }

    /** Records a cleanup run from the settings UI. */
    fun recordCleanup(removed: Int, scanSize: Int, source: String) {
        record(
            AuditLog.Entry(
                atMillis = System.currentTimeMillis(),
                packageName = "",
                fieldShape = "",
                tier = Tier.OPEN,
                category = Category.NONE,
                codes = listOf("cleanup.run", "removed=$removed", "scanned=$scanSize", "source=$source"),
                transition = "cleanup",
            ),
        )
    }

    private fun persist() {
        try {
            val array = JSONArray()
            for (entry in buffer) {
                array.put(
                    JSONObject().apply {
                        put("at", entry.atMillis)
                        put("pkg", entry.packageName)
                        put("shape", entry.fieldShape)
                        put("tier", entry.tier.name)
                        put("cat", entry.category.name)
                        put("codes", JSONArray(entry.codes))
                        put("tx", entry.transition)
                    },
                )
            }
            prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
        } catch (_: Exception) {
            // Audit loss is not worth crashing the IME for; the in-memory buffer
            // still answers until process death.
        }
    }

    private fun load() {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val codes = ArrayList<String>(2)
                val codesArray = obj.optJSONArray("codes") ?: JSONArray()
                for (j in 0 until codesArray.length()) codes.add(codesArray.getString(j))
                buffer.addLast(
                    AuditLog.Entry(
                        atMillis = obj.optLong("at"),
                        packageName = obj.optString("pkg"),
                        fieldShape = obj.optString("shape"),
                        tier = runCatching { Tier.valueOf(obj.optString("tier")) }
                            .getOrDefault(Tier.OPEN),
                        category = runCatching { Category.valueOf(obj.optString("cat")) }
                            .getOrDefault(Category.NONE),
                        codes = codes,
                        transition = obj.optString("tx"),
                    ),
                )
            }
        } catch (_: Exception) {
            buffer.clear()
        }
    }

    private companion object {
        const val FILE = "audit_log"
        const val KEY_ENTRIES = "entries"
        const val CAPACITY = 500
    }
}
