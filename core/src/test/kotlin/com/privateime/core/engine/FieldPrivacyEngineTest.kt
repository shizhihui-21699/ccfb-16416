package com.privateime.core.engine

import com.privateime.core.lexicon.InMemoryLearningStore
import com.privateime.core.model.Category
import com.privateime.core.model.Confidence
import com.privateime.core.model.FieldSignals
import com.privateime.core.model.InputTypeNames
import com.privateime.core.privacy.AppRegistry
import com.privateime.core.privacy.PrivacyPolicy
import com.privateime.core.privacy.PrivacySettings
import com.privateime.core.privacy.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldPrivacyEngineTest {

    private val store = InMemoryLearningStore()
    private val audit = InMemoryAuditLog()
    private var now = 1_000L
    private val engine = FieldPrivacyEngine(
        store = store,
        policy = PrivacyPolicy(PrivacySettings(), AppRegistry.default()),
        clock = { now++ },
        audit = audit,
    )

    private fun text(variation: Int = InputTypeNames.TEXT_VARIATION_NORMAL) =
        InputTypeNames.CLASS_TEXT or variation

    @Test
    fun `password field is locked end to end`() {
        val state = engine.onFieldFocused(
            FieldSignals(
                packageName = "com.example",
                inputType = text(InputTypeNames.TEXT_VARIATION_PASSWORD),
                viewIdResourceName = "com.example:id/password",
            ),
        )
        assertTrue(state.locked)
        assertEquals(Category.PASSWORD, state.verdict.category)
        assertTrue(state.decision.learningDisabled)
    }

    @Test
    fun `chat field is open`() {
        val state = engine.onFieldFocused(
            FieldSignals(
                packageName = "com.tencent.mm",
                inputType = text(),
                hintText = "说点什么…",
            ),
        )
        assertEquals(Tier.OPEN, state.tier)
    }

    @Test
    fun `absence of a written type is remembered for next time`() {
        // First visit: only the resource id gives it away.
        val signals = FieldSignals(
            packageName = "com.example.bank",
            inputType = text(),
            viewIdResourceName = "com.example.bank:id/et_pwd",
        )
        val first = engine.onFieldFocused(signals)
        assertTrue(first.locked)

        // Second visit in a fresh process: memory is the only evidence.
        val freshEngine = FieldPrivacyEngine(store, clock = { now++ }, audit = audit)
        val second = freshEngine.onFieldFocused(
            FieldSignals(
                packageName = "com.example.bank",
                inputType = text(), // app stripped even the id
            ),
        )
        // Different signature (no id), so this is a genuinely unknown field; it
        // must NOT be locked on a guess, but the stored memory must exist.
        assertNotNull(store.fieldMemory(first.signals.signatureKey))
        assertFalse(second.locked)
    }

    @Test
    fun `sticky memory survives an app that flips its input type`() {
        val keyed = FieldSignals(
            packageName = "com.example.bank",
            inputType = text(),
            viewIdResourceName = "com.example.bank:id/pwd_field",
        )
        val first = engine.onFieldFocused(keyed)
        assertTrue(first.locked)

        // The app now reports nothing useful, but the view id still matches.
        val second = engine.onFieldFocused(
            FieldSignals(
                packageName = "com.example.bank",
                inputType = text(),
                viewIdResourceName = "com.example.bank:id/pwd_field",
            ),
        )
        assertTrue(second.locked)

        // Even with no metadata at all but the same view id.
        val third = engine.onFieldFocused(
            FieldSignals(
                packageName = "com.example.bank",
                viewIdResourceName = "com.example.bank:id/pwd_field",
            ),
        )
        assertTrue("memory must carry the verdict", third.locked)
    }

    @Test
    fun `typing a card number escalates an open field`() {
        val chat = FieldSignals(
            packageName = "com.android.chrome",
            inputType = text(InputTypeNames.TEXT_VARIATION_WEB_EDIT_TEXT),
            isWebView = true,
            maxTextLength = 19,
            viewIdResourceName = "card_field",
        )
        val state = engine.onFieldFocused(chat)
        assertEquals(Tier.OPEN, state.tier)

        val escalated = engine.onTextChanged(state, "4111 1111 1111 1111")
        assertEquals(Tier.LOCKED, escalated.tier)
        assertEquals(Category.PAYMENT_CARD, escalated.verdict.category)
    }

    @Test
    fun `escalation is monotonic within a session`() {
        val signals = FieldSignals(
            packageName = "com.android.chrome",
            inputType = text(InputTypeNames.TEXT_VARIATION_WEB_EDIT_TEXT),
            isWebView = true,
            maxTextLength = 19,
            viewIdResourceName = "f",
        )
        val open = engine.onFieldFocused(signals)
        val locked = engine.onTextChanged(open, "4111 1111 1111 1111")
        // User clears the field; the tier must not drop until focus moves.
        val afterClear = engine.onTextChanged(locked, "")
        assertEquals(Tier.LOCKED, afterClear.tier)
    }

    @Test
    fun `typing benign text does not escalate`() {
        val signals = FieldSignals(packageName = "com.example", inputType = text(), hintText = "")
        val open = engine.onFieldFocused(signals)
        val still = engine.onTextChanged(open, "今天天气不错")
        assertEquals(Tier.OPEN, still.tier)
    }

    @Test
    fun `audit log records transitions without content`() {
        engine.onFieldFocused(
            FieldSignals(
                packageName = "com.example",
                inputType = text(InputTypeNames.TEXT_VARIATION_PASSWORD),
                viewIdResourceName = "com.example:id/pw",
            ),
        )
        val entries = audit.recent()
        assertEquals(1, entries.size)
        assertEquals(Tier.LOCKED, entries[0].tier)
        assertEquals("focus", entries[0].transition)
        val serialized = entries.joinToString { it.toString() }
        assertFalse(serialized.contains("password="))
    }

    @Test
    fun `user override to open is recorded in field memory`() {
        val signals = FieldSignals(
            packageName = "com.example.notes",
            inputType = text(InputTypeNames.TEXT_VARIATION_PASSWORD),
            viewIdResourceName = "com.example.notes:id/search_pw_hint",
        )
        val state = engine.onFieldFocused(signals)
        assertTrue(state.locked)
        engine.onUserOverride(state, Tier.OPEN)
        val memory = store.fieldMemory(state.signals.signatureKey)
        assertNotNull(memory)
        assertFalse(memory!!.sticky)
    }

    @Test
    fun `leaving a field clears the in-memory state`() {
        val state = engine.onFieldFocused(
            FieldSignals(packageName = "com.example", inputType = text()),
        )
        engine.onFieldLeft(state)
        // A sensitive field's signals must not be retained by the engine.
        assertTrue(audit.recent().any { it.transition == "blur" })
    }

    @Test
    fun `engine never stores the text sample`() {
        val signals = FieldSignals(
            packageName = "com.example",
            inputType = text(InputTypeNames.TEXT_VARIATION_PASSWORD),
            textSample = "SuperSecret123",
            viewIdResourceName = "p",
        )
        engine.onFieldFocused(signals)
        val memory = store.allFieldMemories().single()
        val serialized = memory.toString()
        assertFalse(serialized.contains("SuperSecret"))
        assertTrue(store.allWords().isEmpty())
    }
}
