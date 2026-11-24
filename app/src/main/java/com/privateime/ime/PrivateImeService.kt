package com.privateime.ime

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.privateime.clipboard.ClipboardHistory
import com.privateime.clipboard.ClipboardService
import com.privateime.cloud.CloudLexiconClient
import com.privateime.core.engine.FieldPrivacyEngine
import com.privateime.core.engine.FieldPrivacyEngine.State as EngineState
import com.privateime.core.engine.NoopAuditLog
import com.privateime.core.lexicon.LearningGate
import com.privateime.core.lexicon.LearningStore
import com.privateime.core.lexicon.LearnedWord
import com.privateime.core.model.Category
import com.privateime.core.model.FieldSignals
import com.privateime.core.privacy.AppRegistry
import com.privateime.core.privacy.ClipboardGuard
import com.privateime.core.privacy.PrivacyPolicy
import com.privateime.core.privacy.PrivacySettings
import com.privateime.core.privacy.Sink
import com.privateime.core.privacy.Tier
import com.privateime.core.text.WordTokenizer
import com.privateime.data.AuditRepository
import com.privateime.data.PrivacySettingsStore
import com.privateime.data.SqliteLearningStore
import com.privateime.ui.KeyboardUiController

/**
 * The IME service.
 *
 * Its whole job is to (a) collect signals, (b) ask the core engine what to do and
 * (c) *enforce* the answer at every sink. No detection logic lives here; the
 * service is a thin, auditable chokepoint. That is deliberate: the enforcement
 * points are the only place a privacy bug can actually cause damage, so they are
 * kept short and visible.
 *
 * ## Enforcement points
 *
 *  1. **Learning** — every committed word passes through [LearningGate] with the
 *     current [com.privateime.core.privacy.PrivacyDecision].
 *  2. **Cloud** — [CloudLexiconClient] refuses when the decision blocks
 *     [Sink.SUGGEST_CLOUD]; the keyboard additionally hides the cloud row.
 *  3. **Clipboard** — capture is gated per field and per payload.
 *  4. **Candidates** — the UI renders only what the decision's
 *     `CandidateMode` allows.
 */
class PrivateImeService : InputMethodService() {

    // ---- dependencies, created lazily on first use -------------------------
    private val settingsStore: PrivacySettingsStore by lazy { PrivacySettingsStore(this) }
    private val learningStore: LearningStore by lazy { SqliteLearningStore(this) }
    private val auditRepository: AuditRepository by lazy { AuditRepository(this) }
    private val clipboardHistory: ClipboardHistory by lazy { ClipboardHistory(this) }
    private val clipboardService: ClipboardService by lazy {
        ClipboardService(this, clipboardHistory)
    }
    private val signalCollector = SignalCollector()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var settings: PrivacySettings = PrivacySettings()
    private var engine: FieldPrivacyEngine? = null
    private var gate: LearningGate? = null

    private var currentState: EngineState? = null
    private var uiController: KeyboardUiController? = null

    /**
     * The word currently being typed, buffered until a boundary. Never persisted;
     * cleared on every focus change and on process death.
     */
    private val pendingWord = StringBuilder()

    /** Debounce for content re-classification while typing. */
    private var lastContentCheckMillis = 0L
    private var lastSample: String? = null

    /** Registered once in [onCreate]; see [onClipboardChanged]. */
    private var clipboardWatcher: android.content.ClipboardManager.OnPrimaryClipChangedListener? = null

    override fun onCreate() {
        super.onCreate()
        settings = settingsStore.load()
        rebuildEngine()
        syncClipboardWatcher()
        configureCloudTransport()
        // History predates the fix; scrub it before anything can offer it.
        mainHandler.post {
            try {
                clipboardHistory.pruneExpired()
                clipboardService.scrubHistory()
            } catch (_: Exception) {
                // Never let cleanup crash the IME.
            }
        }
    }

    /**
     * Registers or unregisters the clipboard listener to match the current setting.
     *
     * Subscribing only while clipboard sync is enabled matters: a user who turns the
     * feature off should not have the keyboard observing every copy on the device,
     * even though [onClipboardChanged] would ignore them.
     */
    private fun syncClipboardWatcher() {
        val manager = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
        val wantListener = settings.clipboardSyncEnabled && clipboardHistory.available
        if (wantListener && clipboardWatcher == null) {
            val listener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
                onClipboardChanged()
            }
            try {
                manager.addPrimaryClipChangedListener(listener)
                clipboardWatcher = listener
            } catch (_: Exception) {
                // Some ROMs restrict listener registration; capture is optional.
            }
        } else if (!wantListener) {
            clipboardWatcher?.let { listener ->
                try {
                    manager.removePrimaryClipChangedListener(listener)
                } catch (_: Exception) {
                    // Ignore; re-registration is idempotent.
                }
            }
            clipboardWatcher = null
        }
    }

    override fun onDestroy() {
        clipboardWatcher?.let { listener ->
            try {
                (getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                    ?.removePrimaryClipChangedListener(listener)
            } catch (_: Exception) {
                // Ignore; process is going away.
            }
        }
        clipboardWatcher = null
        try {
            (learningStore as? java.io.Closeable)?.close()
        } catch (_: Exception) {
            // Ignore.
        }
        super.onDestroy()
    }

    /**
     * Installs the real transport only when the product is built with an endpoint.
     *
     * The default build has no endpoint and therefore no way to send anything, even
     * with cloud suggestions nominally enabled: `CloudLexiconClient.transport`
     * stays [CloudLexiconClient.NoopTransport]. That is the intended failure mode
     * for a privacy-first keyboard — the network path must be opt-in at build time,
     * not accidentally reachable.
     */
    private fun configureCloudTransport() {
        val endpoint = CLOUD_ENDPOINT
        if (endpoint.isBlank()) return
        CloudLexiconClient.transport = com.privateime.cloud.HttpCloudTransport(endpoint) {
            // Anonymous, stable per install, and unrelated to any device id.
            val prefs = getSharedPreferences("cloud_identity", MODE_PRIVATE)
            prefs.getString("install_id", null) ?: java.util.UUID.randomUUID().toString().also {
                prefs.edit().putString("install_id", it).apply()
            }
        }
    }

    private fun rebuildEngine() {
        val policy = PrivacyPolicy(settings, AppRegistry.default())
        engine = FieldPrivacyEngine(
            store = learningStore,
            policy = policy,
            clock = System::currentTimeMillis,
            audit = auditRepository,
        )
        gate = LearningGate(learningStore, System::currentTimeMillis)
    }

    /**
     * The settings screen runs in a different process from the IME (an IME service
     * is bound by the system, not by our activity), so there is no in-process way
     * to push a change. Instead the IME re-reads the settings on every focus
     * change: the read is a handful of preference lookups and already on the
     * critical path, and a privacy switch that does not take effect is a bug
     * users cannot work around.
     */
    private fun reloadSettingsIfChanged() {
        val fresh = settingsStore.load()
        if (fresh == settings) return
        settings = fresh
        rebuildEngine()
        // A master switch may have just opened or closed the clipboard listener.
        syncClipboardWatcher()
    }

    /** Explicit entry point for callers that can reach the service (tests, bound clients). */
    fun onSettingsChanged() {
        settings = settingsStore.load()
        rebuildEngine()
        syncClipboardWatcher()
    }

    // ------------------------------------------------------------- lifecycle

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        try {
            startField(attribute)
        } catch (e: Exception) {
            // A detection failure must never prevent the keyboard from showing.
            currentState = null
            android.util.Log.w(TAG, "onStartInput failed; failing open", e)
        }
    }

    private fun startField(attribute: EditorInfo) {
        // Settings may have changed since the last focus; apply them before the
        // first decision so a toggle takes effect on the very next field.
        reloadSettingsIfChanged()
        pendingWord.setLength(0)
        val connection = currentInputConnection
        val sample = if (shouldReadSample(attribute)) {
            signalCollector.readSample(connection)
        } else {
            null
        }
        val collected = signalCollector.collect(
            editorInfo = attribute,
            textSample = sample,
            screenTitle = screenTitle(),
            textLength = sample?.length ?: 0,
        )
        lastSample = sample
        val state = engine?.onFieldFocused(collected.signals) ?: return
        currentState = state
        uiController?.render(state, collected.accessibilityUsed)
        // The shield must also be visible *outside* the keyboard, because the
        // whole point is to tell the user this field is private while they type.
        uiController?.updatePrivacyIndicator(state)
    }

    /**
     * Reading the field content costs a round trip to the app's process and is
     * the most privacy-sensitive operation the IME performs. It is skipped for
     * declared password fields (nothing to learn from the sample) and for fields
     * that cannot accept text.
     */
    private fun shouldReadSample(attribute: EditorInfo): Boolean {
        val inputType = attribute.inputType
        if (inputType == InputType.TYPE_NULL) return false
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val declaredSecret = cls == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        return !declaredSecret
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        uiController?.render(currentState, accessibilityUsed = false)
    }

    override fun onFinishInput() {
        try {
            // A word still buffered when focus leaves is a complete word the user
            // finished typing; learn it before the field state is dropped.
            flushPendingWord()
            engine?.onFieldLeft(currentState)
        } finally {
            currentState = null
            pendingWord.setLength(0)
            lastSample = null
            super.onFinishInput()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        uiController?.onKeyboardHidden()
        super.onFinishInputView(finishingInput)
    }

    override fun onCreateInputView(): View {
        val controller = KeyboardUiController(
            context = this,
            callbacks = object : KeyboardUiController.Callbacks {
                override fun onKey(code: Int, text: String?) {
                    if (text != null) {
                        commitText(text)
                    } else {
                        sendKey(code)
                    }
                }

                override fun onDelete() = deleteBackward()

                override fun onEnter() = sendEnter()

                override fun onSuggestionPicked(text: String, fromCloud: Boolean) {
                    commitText(text)
                }

                override fun onPrivacyBadgeTapped() {
                uiController?.toggleExplanation(currentState)
            }

                override fun onOverrideRequested(trusted: Boolean) {
                    val state = currentState ?: return
                    val force = if (trusted) Tier.OPEN else Tier.LOCKED
                    engine?.onUserOverride(state, force)
                    if (trusted) {
                        settingsStore.setTrusted(state.signals.signatureKey, true)
                        settingsStore.setAlwaysPrivate(state.signals.signatureKey, false)
                    } else {
                        settingsStore.setAlwaysPrivate(state.signals.signatureKey, true)
                        settingsStore.setTrusted(state.signals.signatureKey, false)
                    }
                    // Re-evaluate the current field with the new override.
                    onSettingsChanged()
                    currentState = engine?.onFieldFocused(state.signals)
                    uiController?.render(currentState, accessibilityUsed = false)
                }
            },
        )
        uiController = controller
        controller.render(currentState, accessibilityUsed = AccessibilityFieldProbeBridge.isConnected())
        return controller.view
    }

    // ------------------------------------------------------------ keystrokes

    /**
     * Appends to the current word. Single characters are *not* learned on their own:
     * a keyboard learns words, and learning "h", "e", "l" would fill the dictionary
     * with noise and make the cleanup screen unreadable. A word is committed when a
     * delimiter is pressed, the field loses focus, or the buffer reaches its cap.
     */
    private fun commitText(text: String) {
        val connection = currentInputConnection ?: return
        connection.commitText(text, 1)
        for (ch in text) {
            if (ch.isWhitespace() || ch in WORD_DELIMITERS) {
                flushPendingWord()
            } else {
                pendingWord.append(ch)
                if (pendingWord.length >= MAX_PENDING_WORD) flushPendingWord()
            }
        }
        maybeReassessContent(connection)
    }

    /**
     * Commits the buffered word to the learning path.
     *
     * Called on every boundary, so this is the *only* place a word becomes learned.
     * The tokenizer is applied here as a safety net (a multi-word buffer can occur
     * when text is committed in one call by a suggestion).
     */
    private fun flushPendingWord() {
        if (pendingWord.isEmpty()) return
        val word = pendingWord.toString()
        pendingWord.setLength(0)
        if (word.length < MIN_LEARNABLE_WORD) return
        onCommittedWord(word)
    }

    private fun sendKey(code: Int) {
        val connection = currentInputConnection ?: return
        if (code == KeyEvent.KEYCODE_DEL) {
            connection.deleteSurroundingText(1, 0)
            if (pendingWord.isNotEmpty()) pendingWord.setLength(pendingWord.length - 1)
            return
        }
        flushPendingWord()
        val event = KeyEvent(KeyEvent.ACTION_DOWN, code)
        connection.sendKeyEvent(event)
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun deleteBackward() {
        val connection = currentInputConnection ?: return
        val selected = connection.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            connection.commitText("", 1)
        } else {
            connection.deleteSurroundingText(1, 0)
        }
        if (pendingWord.isNotEmpty()) pendingWord.setLength(pendingWord.length - 1)
        maybeReassessContent(connection)
    }

    private fun sendEnter() {
        val connection = currentInputConnection ?: return
        flushPendingWord()
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_NONE
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            connection.performEditorAction(action)
        } else {
            sendKey(KeyEvent.KEYCODE_ENTER)
        }
    }

    /**
     * Every committed word reaches the store through here, and only through here.
     *
     * The `PrivacyDecision` for the current field is the only thing that decides
     * whether the word is stored. There is no second path.
     */
    private fun onCommittedWord(text: String) {
        val state = currentState ?: return
        val gate = gate ?: return
        val store = learningStore

        // Split a sentence into word-ish chunks so that a whole pasted paragraph
        // never becomes one dictionary entry.
        for (token in tokenize(text)) {
            val category = categorize(token)
            val stored = gate.remember(
                text = token,
                claimedCategory = category,
                signals = state.signals,
                decision = state.decision,
                origin = LearnedWord.Origin.LOCAL_DICTIONARY,
            )
            // Even if the gate stored it, a *credential-shaped* token typed in an
            // OPEN field must not stay: the field may have been misdeclared and
            // the content is the only proof.
            if (stored && ContentGuards.isLikelyCredential(token)) {
                store.forget(token)
                auditRepository.recordCredentialRevoked(state.signals.packageName)
            }
        }
        maybeReassessContent(currentInputConnection)
    }

    /**
     * Content re-check while typing. Runs at most every [CONTENT_CHECK_INTERVAL_MS]
     * because escalation needs a *whole* card number or e-mail, not every keypress,
     * and the round trip to the app is not free.
     *
     * There is intentionally no early-return based on the current tier. An earlier
     * version skipped this when cloud was enabled and the field was already
     * sensitive, which meant a field that had just locked could never record the
     * new evidence — including the evidence that triggered its own cleanup. The
     * escalation path is monotonic (see [FieldPrivacyEngine.onTextChanged]), so
     * re-running it on a locked field is safe and cheap.
     */
    private fun maybeReassessContent(connection: InputConnection?) {
        val state = currentState ?: return
        val now = System.currentTimeMillis()
        if (now - lastContentCheckMillis < CONTENT_CHECK_INTERVAL_MS) return
        lastContentCheckMillis = now

        val sample = signalCollector.readSample(connection) ?: return
        if (sample == lastSample) return
        lastSample = sample
        val escalated = engine?.onTextChanged(state, sample) ?: return
        if (escalated !== state) {
            currentState = escalated
            // If the field just became sensitive, purge anything learned while it
            // was still open. This is the in-session part of the leak fix.
            if (escalated.decision.learningDisabled) {
                purgeRecentFromCurrentField(escalated)
            }
            uiController?.render(escalated, accessibilityUsed = false)
            uiController?.updatePrivacyIndicator(escalated)
        }
    }

    /**
     * Removes words learned in *this app* during this session when the field turned
     * out to be sensitive.
     *
     * Bounded on two axes so it cannot touch unrelated vocabulary:
     *  * only tokens that appear in the field's own content sample, and
     *  * only entries whose recorded provenance is this package (or unknown, for
     *    entries migrated from a build that did not record provenance).
     *
     * Without the provenance check, a user typing a number into a card field could
     * lose the same token from an unrelated dictionary entry.
     */
    private fun purgeRecentFromCurrentField(state: EngineState) {
        val sample = state.signals.textSample ?: return
        val store = learningStore
        val packageName = state.signals.packageName
        val toPurge = tokenize(sample).filter { token ->
            val word = store.findWord(token) ?: return@filter false
            word.packageName == null || word.packageName == packageName
        }
        if (toPurge.isNotEmpty()) {
            store.forgetAll(toPurge)
            auditRepository.recordSessionPurge(toPurge.size, packageName)
        }
    }

    private fun tokenize(text: String): List<String> = WordTokenizer.tokenize(text)

    private fun categorize(token: String): Category =
        ContentGuards.categoryOf(token)

    // --------------------------------------------------------------- cloud

    /**
     * Called by the UI when the composing prefix changes. The decision is read
     * from the live state, never from a cached "is this app private" flag, so a
     * field that escalates mid-sentence stops cloud requests immediately.
     */
    fun requestCloudSuggestions(prefix: String, onResult: (List<String>) -> Unit) {
        val state = currentState ?: run {
            onResult(emptyList())
            return
        }
        CloudLexiconClient.requestSuggestions(
            decision = state.decision,
            signals = state.signals,
            prefix = prefix,
            locale = localeTag(),
            callbackExecutor = { r -> mainHandler.post(r) },
            onResult = onResult,
        )
    }

    // ------------------------------------------------------------- clipboard

    /**
     * Registered from [onCreate] on the platform [android.content.ClipboardManager].
     *
     * This callback is the *only* capture path. It fires for every copy the user
     * makes anywhere on the device, which is why the decision has to be read live
     * rather than cached: a copy made while a password field is focused must not
     * be captured, even if the same keyboard was in a chat field a second earlier.
     *
     * It is registered only while clipboard sync is enabled, so a user who turns
     * the feature off does not even subscribe to copy events.
     */
    internal fun onClipboardChanged() {
        if (!settings.clipboardSyncEnabled) return
        if (!clipboardHistory.available) return
        try {
            clipboardService.onPrimaryClipChanged(currentState?.decision)
        } catch (_: Exception) {
            // A clipboard failure must not take the keyboard down.
        }
    }

    /**
     * Explicit paste. Always allowed: the user asked for the clipboard content,
     * and refusing would break the keyboard. Capture is the guarded operation,
     * not reading.
     */
    fun readClipboardForPaste(): String? = clipboardService.readForPaste(allowed = true)

    // --------------------------------------------------------------- helpers

    private fun screenTitle(): String? {
        // The IME does not get window titles from the platform. Accessibility can
        // supply one for web pages via the root node's pane title; the probe
        // already folds that into `labelledText` for fields. Keeping this null
        // rather than guessing avoids reading unrelated screen content.
        return null
    }

    private fun localeTag(): String =
        resources.configuration.locales[0]?.toLanguageTag() ?: "en"

    private object ContentGuards {
        fun categoryOf(token: String): Category {
            val evidence = com.privateime.core.detect.ContentClassifier.residueEvidence(token)
            return evidence.firstOrNull { it.category.isSecret }?.category ?: Category.NONE
        }

        fun isLikelyCredential(token: String): Boolean =
            com.privateime.core.detect.ContentClassifier.residueEvidence(token)
                .any { it.category.isSecret && it.weight >= 0.85 }
    }

    private companion object {
        const val TAG = "PrivateImeService"
        /**
         * Empty in the reference build. Set at build time (a resource or a
         * BuildConfig field) to enable cloud suggestions.
         */
        const val CLOUD_ENDPOINT = ""
        const val CONTENT_CHECK_INTERVAL_MS = 400L
        /** Single characters are never learned; the shortest useful entry is two. */
        const val MIN_LEARNABLE_WORD = 2
        const val MAX_PENDING_WORD = 64
        val WORD_DELIMITERS = "，。！？、；：（）【】《》,.!?;:()[]{}<>\"'“”‘’".toSet()
    }
}

/**
 * Thin indirection so the UI can ask "did accessibility enrich this field?" without
 * importing the probe directly (and so tests can stub it).
 */
object AccessibilityFieldProbeBridge {
    fun isConnected(): Boolean = com.privateime.input.AccessibilityFieldProbe.serviceConnected
}
