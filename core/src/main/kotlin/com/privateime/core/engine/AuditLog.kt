package com.privateime.core.engine

import com.privateime.core.model.Category
import com.privateime.core.privacy.Tier

/**
 * An append-only, content-free record of privacy decisions.
 *
 * Its job is to answer the two questions a user (or a reviewer) always asks after
 * an incident: *which* apps did we ever learn from, and did the shield actually
 * engage? It deliberately cannot answer "what did I type" — there is no field for
 * it, which is the point.
 *
 * The IME persists a bounded ring (see the Android `AuditRepository`); this
 * interface keeps the core module free of I/O.
 */
interface AuditLog {
    data class Entry(
        val atMillis: Long,
        val packageName: String,
        /** `shapeDescription()` of the field: ids, class, input type, max length. */
        val fieldShape: String,
        val tier: Tier,
        val category: Category,
        val codes: List<String>,
        /** `focus` | `escalate` | `blur` | `override` | `cleanup` */
        val transition: String,
    )

    fun record(entry: Entry)

    fun recent(limit: Int = 200): List<Entry>

    fun clear()
}

/** No-op implementation for tests and for the core-only build. */
object NoopAuditLog : AuditLog {
    override fun record(entry: AuditLog.Entry) = Unit
    override fun recent(limit: Int): List<AuditLog.Entry> = emptyList()
    override fun clear() = Unit
}

/** In-memory ring buffer, used by unit tests and as a fallback if storage fails. */
class InMemoryAuditLog(private val capacity: Int = 200) : AuditLog {
    private val entries = ArrayDeque<AuditLog.Entry>(capacity)

    override fun record(entry: AuditLog.Entry) {
        if (entries.size >= capacity) entries.removeFirst()
        entries.addLast(entry)
    }

    override fun recent(limit: Int): List<AuditLog.Entry> =
        entries.takeLast(limit.coerceAtLeast(0))

    override fun clear() = entries.clear()
}
