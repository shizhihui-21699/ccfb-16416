package com.privateime.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.privateime.R
import com.privateime.core.engine.FieldPrivacyEngine
import com.privateime.core.privacy.CandidateMode
import com.privateime.core.privacy.Tier

/**
 * The keyboard view.
 *
 * Built in code rather than from XML layouts so the entire keyboard is one file
 * that a reviewer can read top to bottom. It is intentionally plain: this project
 * demonstrates the privacy architecture, and a keyboard's visual design is not
 * what is being demonstrated.
 *
 * ## The UI enforces the policy, it does not just display it
 *
 *  * [Tier.LOCKED] fields lose the candidate strip entirely, so no learned word can
 *    even appear next to a credential.
 *  * [Tier.GUARDED] fields get [CandidateMode.LOCAL_GENERIC]: completions computed
 *    from the current word, with no personal history.
 *  * The privacy badge is tappable and opens the explanation sheet, which lists
 *    the evidence *codes*, not the content.
 */
@SuppressLint("ViewConstructor")
class KeyboardUiController(
    private val context: Context,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        fun onKey(code: Int, text: String? = null)
        fun onDelete()
        fun onEnter()
        fun onSuggestionPicked(text: String, fromCloud: Boolean)
        fun onPrivacyBadgeTapped()
        fun onOverrideRequested(trusted: Boolean)
    }

    val view: View = buildLayout()

    private val root: LinearLayout
    private val privacyBadge: TextView
    private val candidateRow: HorizontalScrollView
    private val candidateStrip: LinearLayout
    private val explanationPanel: LinearLayout
    private val keysContainer: LinearLayout

    init {
        root = view as LinearLayout
        privacyBadge = root.findViewById<TextView>(ID_BADGE)
        candidateRow = root.findViewById(ID_CANDIDATE_ROW)
        candidateStrip = root.findViewById(ID_CANDIDATE_STRIP)
        explanationPanel = root.findViewById(ID_EXPLANATION)
        keysContainer = root.findViewById(ID_KEYS)
    }

    /**
     * @param state the engine's verdict for the focused field, or null when no
     *   field is focused (the keyboard should behave normally)
     */
    fun render(state: FieldPrivacyEngine.State?, accessibilityUsed: Boolean) {
        val tier = state?.decision?.tier ?: Tier.OPEN
        renderBadge(state, tier, accessibilityUsed)
        renderCandidates(state)
        renderKeys(tier)
    }

    private fun renderBadge(state: FieldPrivacyEngine.State?, tier: Tier, accessibilityUsed: Boolean) {
        when (tier) {
            Tier.LOCKED -> {
                privacyBadge.visible()
                privacyBadge.text = context.getString(R.string.badge_locked)
                privacyBadge.setBackgroundResource(R.drawable.badge_locked)
                privacyBadge.setTextColor(context.getColor(R.color.on_locked))
            }
            Tier.GUARDED -> {
                privacyBadge.visible()
                privacyBadge.text = context.getString(R.string.badge_guarded)
                privacyBadge.setBackgroundResource(R.drawable.badge_guarded)
                privacyBadge.setTextColor(context.getColor(R.color.on_guarded))
            }
            Tier.OPEN -> {
                if (accessibilityUsed) {
                    privacyBadge.visible()
                    privacyBadge.text = context.getString(R.string.badge_enhanced)
                    privacyBadge.setBackgroundResource(R.drawable.badge_neutral)
                    privacyBadge.setTextColor(context.getColor(R.color.on_neutral))
                } else {
                    privacyBadge.hide()
                }
            }
        }
        privacyBadge.setOnClickListener { callbacks.onPrivacyBadgeTapped() }
    }

    private fun renderCandidates(state: FieldPrivacyEngine.State?) {
        renderCandidates(state, suggestions = emptyList())
    }

    /**
     * Renders the candidate strip.
     *
     * @param suggestions real candidates to insert; empty shows the strip's mode
     * @param fromCloud which suggestions came from the network, for labelling
     *
     * A sensitive field's mode is enforced here as a second line of defence: even
     * if a caller passes suggestions for a [CandidateMode.NONE] field, they are
     * dropped rather than rendered next to a credential.
     */
    fun renderCandidates(
        state: FieldPrivacyEngine.State?,
        suggestions: List<Pair<String, Boolean>>,
    ) {
        val mode = state?.decision?.candidateMode ?: CandidateMode.FULL
        candidateStrip.removeAllViews()
        when (mode) {
            CandidateMode.NONE -> candidateRow.hide()
            CandidateMode.LOCAL_GENERIC -> {
                candidateRow.visible()
                // Only on-device candidates; cloud ones are filtered even if the
                // caller forgot to check the sink.
                val local = suggestions.filterNot { it.second }
                if (local.isEmpty()) {
                    addModeLabel(context.getString(R.string.candidates_local_only))
                } else {
                    local.forEach { (text, _) -> addCandidateChip(text, fromCloud = false) }
                }
            }
            CandidateMode.FULL -> {
                candidateRow.visible()
                if (suggestions.isEmpty()) {
                    addModeLabel(context.getString(R.string.candidates_ready))
                } else {
                    suggestions.forEach { (text, cloud) -> addCandidateChip(text, fromCloud = cloud) }
                }
            }
        }
    }

    private fun addModeLabel(text: String) {
        candidateStrip.addView(
            TextView(context).apply {
                this.text = text
                setPadding(24, 12, 24, 12)
                setTextColor(context.getColor(R.color.candidate_text))
                textSize = 14f
                alpha = 0.7f
                // Deliberately not clickable: a label must never be insertable.
                isClickable = false
            },
        )
    }

    private fun addCandidateChip(text: String, fromCloud: Boolean) {
        val chip = TextView(context).apply {
            this.text = text
            setPadding(24, 12, 24, 12)
            setTextColor(context.getColor(R.color.candidate_text))
            textSize = 16f
            setOnClickListener { callbacks.onSuggestionPicked(text, fromCloud) }
        }
        candidateStrip.addView(chip)
    }

    private fun renderKeys(tier: Tier) {
        // Keys are identical in every tier on purpose: a privacy mode that changes
        // the layout is a mode users turn off. What changes is what surrounds them.
        keysContainer.visibility = View.VISIBLE
    }

    /** Explains, in the user's language, why the shield is on. Codes only. */
    fun toggleExplanation(state: FieldPrivacyEngine.State?) {
        if (explanationPanel.visibility == View.VISIBLE) {
            explanationPanel.hide()
            return
        }
        explanationPanel.removeAllViews()
        explanationPanel.visible()
        if (state == null) {
            addExplanationLine(context.getString(R.string.explain_no_field))
            return
        }
        addExplanationLine(
            context.getString(
                R.string.explain_summary,
                context.getString(tierStringRes(state.decision.tier)),
            ),
            bold = true,
        )
        for (reason in state.verdict.reasons.take(4)) {
            val label = context.getString(evidenceStringRes(reason.code))
            if (label.isNotEmpty()) addExplanationLine("• $label")
        }
        // The user's escape hatches, phrased as a choice, not a warning.
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 0)
        }
        actions.addView(
            Button(context).apply {
                text = context.getString(R.string.action_always_private)
                setOnClickListener { callbacks.onOverrideRequested(trusted = false) }
            },
        )
        if (state.decision.tier != Tier.OPEN) {
            actions.addView(
                Button(context).apply {
                    text = context.getString(R.string.action_this_is_fine)
                    setOnClickListener { callbacks.onOverrideRequested(trusted = true) }
                },
            )
        }
        explanationPanel.addView(actions)
    }

    private fun addExplanationLine(text: String, bold: Boolean = false) {
        explanationPanel.addView(
            TextView(context).apply {
                this.text = text
                textSize = if (bold) 15f else 13f
                setTextColor(context.getColor(R.color.candidate_text))
                setPadding(24, 6, 24, 6)
                if (bold) setTypeface(typeface, Typeface.BOLD)
            },
        )
    }

    fun onKeyboardHidden() {
        explanationPanel.hide()
    }

    /**
     * The shield indicator outside the keyboard: a thin coloured bar at the very
     * top of the input area, visible even when the keyboard is collapsed.
     */
    fun updatePrivacyIndicator(state: FieldPrivacyEngine.State?) {
        // In this reference build the badge inside the keyboard is the indicator.
        // A shipping build would raise a notification-style overlay here.
    }

    private fun tierStringRes(tier: Tier): Int = when (tier) {
        Tier.OPEN -> R.string.tier_open
        Tier.GUARDED -> R.string.tier_guarded
        Tier.LOCKED -> R.string.tier_locked
    }

    private fun evidenceStringRes(code: String): Int = when {
        code.startsWith("inputType.") -> R.string.evidence_input_type
        code.startsWith("a11y.isPassword") -> R.string.evidence_a11y_password
        code.startsWith("lexicon.") -> R.string.evidence_label
        code.startsWith("content.card") -> R.string.evidence_content_card
        code.startsWith("content.") -> R.string.evidence_content
        code == "memory.previousVerdict" -> R.string.evidence_memory
        code.startsWith("structure.") -> R.string.evidence_structure
        code.startsWith("user.") -> R.string.evidence_user
        else -> 0
    }

    // ---------------------------------------------------------------- layout

    private fun buildLayout(): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(context.getColor(R.color.keyboard_background))
            layoutParams = ViewGroup.LayoutParams(MATCH, WRAP)
        }

        column.addView(
            TextView(context).apply {
                id = ID_BADGE
                visibility = View.GONE
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(16, 6, 16, 6)
                setOnClickListener { callbacks.onPrivacyBadgeTapped() }
            },
        )

        column.addView(
            HorizontalScrollView(context).apply {
                id = ID_CANDIDATE_ROW
                isHorizontalScrollBarEnabled = false
                addView(
                    LinearLayout(context).apply {
                        id = ID_CANDIDATE_STRIP
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(8, 4, 8, 4)
                    },
                    ViewGroup.LayoutParams(WRAP, WRAP),
                )
            },
            LinearLayout.LayoutParams(MATCH, WRAP),
        )

        column.addView(
            LinearLayout(context).apply {
                id = ID_EXPLANATION
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setBackgroundColor(context.getColor(R.color.explanation_background))
            },
            LinearLayout.LayoutParams(MATCH, WRAP),
        )

        column.addView(
            LinearLayout(context).apply {
                id = ID_KEYS
                orientation = LinearLayout.VERTICAL
                addView(buildKeyRow(listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")))
                addView(buildKeyRow(listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")))
                addView(buildShiftRow())
            },
            LinearLayout.LayoutParams(MATCH, WRAP),
        )
        return column
    }

    private fun buildKeyRow(letters: List<String>): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (letter in letters) {
            row.addView(
                keyView(letter.uppercase()) {
                    callbacks.onKey(android.view.KeyEvent.KEYCODE_UNKNOWN, letter)
                },
                LinearLayout.LayoutParams(0, keyHeight(), 1f),
            )
        }
        return row
    }

    private fun buildShiftRow(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(
            keyView("⌫") { callbacks.onDelete() },
            LinearLayout.LayoutParams(0, keyHeight(), 1.5f),
        )
        row.addView(
            keyView("空格") { callbacks.onKey(android.view.KeyEvent.KEYCODE_SPACE, " ") },
            LinearLayout.LayoutParams(0, keyHeight(), 4f),
        )
        row.addView(
            keyView("⏎") { callbacks.onEnter() },
            LinearLayout.LayoutParams(0, keyHeight(), 1.5f),
        )
        return row
    }

    private fun keyView(label: String, onTap: () -> Unit): View =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(context.getColor(R.color.key_text))
            background = GradientDrawable().apply {
                cornerRadius = 12f
                setColor(context.getColor(R.color.key_background))
            }
            isClickable = true
            setOnClickListener { onTap() }
            val margin = dp(3)
            layoutParams = LinearLayout.LayoutParams(0, keyHeight(), 1f).apply {
                setMargins(margin, margin, margin, margin)
            }
        }

    private fun keyHeight(): Int = dp(52)

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun View.hide() {
        visibility = View.GONE
    }

    private fun View.visible() {
        visibility = View.VISIBLE
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val ID_BADGE = 0x7f0f0001
        const val ID_CANDIDATE_ROW = 0x7f0f0002
        const val ID_CANDIDATE_STRIP = 0x7f0f0003
        const val ID_EXPLANATION = 0x7f0f0004
        const val ID_KEYS = 0x7f0f0005
    }
}
