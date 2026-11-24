package com.privateime.core.lexicon

import com.privateime.core.model.Category

/**
 * In-memory [LearningStore] used by tests and by the "simulate before you purge"
 * preview in the settings screen. Not thread-safe; wrap in the Android module.
 */
class InMemoryLearningStore(
    initial: List<LearnedWord> = emptyList(),
) : LearningStore {

    private val words = LinkedHashMap<String, LearnedWord>()
    private val fields = HashMap<String, FieldMemory>()
    private var nextId = 1

    init {
        initial.forEach { words[it.text] = it }
    }

    override fun allWords(): List<LearnedWord> = words.values.toList()

    override fun findWord(text: String): LearnedWord? = words[text]

    override fun remember(
        text: String,
        category: Category,
        packageName: String?,
        origin: LearnedWord.Origin,
        nowMillis: Long,
    ): Boolean {
        val existing = words[text]
        words[text] = if (existing == null) {
            LearnedWord(
                id = "w" + nextId++,
                text = text,
                category = category,
                packageName = packageName,
                occurrences = 1,
                firstSeenMillis = nowMillis,
                lastSeenMillis = nowMillis,
                origin = origin,
            )
        } else {
            existing.copy(
                occurrences = existing.occurrences + 1,
                lastSeenMillis = nowMillis,
                category = if (category.isSecret) category else existing.category,
                // Provenance is first-seen, not last-seen: the app that
                // introduced the word is the one that leaked it, and that is
                // what the cleanup UI needs to show.
                packageName = existing.packageName ?: packageName,
            )
        }
        return true
    }

    override fun forget(text: String): Boolean = words.remove(text) != null

    override fun forgetAll(texts: Collection<String>): Int {
        var removed = 0
        for (text in texts) if (words.remove(text) != null) removed++
        return removed
    }

    override fun purgeSink(origin: LearnedWord.Origin): Int {
        val before = words.size
        words.entries.removeAll { it.value.origin == origin }
        return before - words.size
    }

    override fun fieldMemory(signatureKey: String): FieldMemory? = fields[signatureKey]

    override fun rememberField(memory: FieldMemory) {
        fields[memory.signatureKey] = memory
    }

    override fun forgetField(signatureKey: String) {
        fields.remove(signatureKey)
    }

    override fun allFieldMemories(): List<FieldMemory> = fields.values.toList()

    // Test conveniences -------------------------------------------------------

    fun seed(vararg words: LearnedWord) {
        for (w in words) this.words[w.text] = w
    }

    fun snapshot(): List<LearnedWord> = words.values.toList()
}
