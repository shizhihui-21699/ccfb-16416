package com.privateime.core.detect

import com.privateime.core.model.Category
import com.privateime.core.model.Confidence
import com.privateime.core.model.Evidence
import com.privateime.core.model.FieldSignals
import com.privateime.core.model.FieldSource
import com.privateime.core.model.ImeFlags
import com.privateime.core.model.InputTypeNames
import com.privateime.core.model.Verdict
import com.privateime.core.model.withoutText
import com.privateime.core.text.TextNormalizer
import java.util.Locale

/**
 * Decides whether the currently focused field is sensitive.
 *
 * ## Why a scored model rather than a boolean
 *
 * The three signals available to an IME are individually unreliable:
 *
 *  * `inputType` is authoritative when correct, but a large fraction of real
 *    password fields declare `TYPE_CLASS_TEXT|NORMAL` because the developer
 *    forgot `android:inputType` or set it on the wrong view.
 *  * Labels are explicit but adversarial: a banking app's chat input may be
 *    labelled "输入内容" while another app's search box is labelled
 *    "请输入银行卡号" *because it is a search box*.
 *  * Content is precise but arrives too late: by the time the user has typed a
 *    full card number, the first three digits may already have been learned.
 *
 * So each signal becomes weighted [Evidence], evidence is summed per category,
 * negative evidence (search/comment/nickname surfaces) is applied, and a score
 * threshold maps to a [Confidence] tier. Hard evidence (an explicit password
 * input type, an accessibility `isPassword`) short-circuits the negatives,
 * because a field cannot be "just a search box" if the platform says it is a
 * password.
 *
 * The engine is pure: no Android, no I/O, no clocks. [DetectorConfig] carries
 * the tunables so the product can trade false positives against false negatives
 * per release without touching the logic.
 */
class SensitivityDetector(
    private val config: DetectorConfig = DetectorConfig(),
) {

    /**
     * Assess a field.
     *
     * @param memory a previously stored verdict for this exact field signature,
     *   or null. Sticky memory is how the engine survives apps that change
     *   `inputType` after the first keystroke (a very common anti-autofill trick).
     */
    fun assess(signals: FieldSignals, memory: Verdict? = null): Verdict {
        val evidence = ArrayList<Evidence>(10)
        var hard = false

        // ------------------------------------------------------- 1. inputType
        signals.inputType?.let { inputType ->
            val cls = InputTypeNames.classOf(inputType)
            val variation = InputTypeNames.variationOf(inputType)
            when {
                cls == InputTypeNames.CLASS_TEXT &&
                    (variation == InputTypeNames.TEXT_VARIATION_PASSWORD ||
                        variation == InputTypeNames.TEXT_VARIATION_WEB_PASSWORD ||
                        variation == InputTypeNames.TEXT_VARIATION_VISIBLE_PASSWORD) -> {
                    evidence += hard(
                        "inputType.password",
                        FieldSource.INPUT_TYPE,
                        Category.PASSWORD,
                        "variation=${InputTypeNames.describe(inputType)}",
                    )
                    hard = true
                }
                cls == InputTypeNames.CLASS_NUMBER &&
                    variation == InputTypeNames.NUMBER_VARIATION_PASSWORD -> {
                    evidence += hard(
                        "inputType.numberPassword",
                        FieldSource.INPUT_TYPE,
                        Category.PIN,
                        "variation=number/password",
                    )
                    hard = true
                }
                cls == InputTypeNames.CLASS_TEXT &&
                    variation == InputTypeNames.TEXT_VARIATION_EMAIL_ADDRESS -> {
                    evidence += Evidence(
                        "inputType.email", FieldSource.INPUT_TYPE, 0.75, Category.PII,
                        "variation=text/email", hard = true,
                    )
                }
                cls == InputTypeNames.CLASS_TEXT &&
                    variation == InputTypeNames.TEXT_VARIATION_WEB_EMAIL_ADDRESS -> {
                    evidence += Evidence(
                        "inputType.webEmail", FieldSource.INPUT_TYPE, 0.75, Category.PII,
                        "variation=text/webEmail", hard = true,
                    )
                }
                cls == InputTypeNames.CLASS_PHONE -> {
                    evidence += Evidence(
                        "inputType.phone", FieldSource.INPUT_TYPE, 0.4, Category.PII,
                        "class=phone",
                    )
                }
                cls == InputTypeNames.CLASS_TEXT &&
                    variation == InputTypeNames.TEXT_VARIATION_POSTAL_ADDRESS -> {
                    evidence += Evidence(
                        "inputType.postal", FieldSource.INPUT_TYPE, 0.5, Category.PII,
                        "variation=text/postalAddress", hard = true,
                    )
                }
            }
        }

        // ------------------------------------------- 2. accessibility isPassword
        if (signals.isPassword) {
            evidence += hard(
                "a11y.isPassword", FieldSource.ACCESSIBILITY, Category.PASSWORD, "isPassword=true",
            )
            hard = true
        }

        // ------------------------------------------------ 3. platform opt-outs
        // These do not decide *category*, they decide whether learning is
        // allowed. Handled by the privacy policy, recorded here for the audit.
        if (signals.noPersonalizedLearning) {
            evidence += Evidence(
                "ime.noPersonalizedLearning", FieldSource.IME_OPTIONS, 0.0, Category.NONE,
                "IME_FLAG_NO_PERSONALIZED_LEARNING",
            )
        }

        // -------------------------------------------------------- 4. label text
        val candidates = LabelParser.candidates(signals)
        var negative = 0.0
        val negativeHits = ArrayList<String>(2)
        for (candidate in candidates) {
            for (term in SensitiveLexicon.match(candidate.text)) {
                evidence += Evidence(
                    code = "lexicon." + term.text.replace(' ', '_'),
                    source = candidate.source,
                    weight = term.strength.weight,
                    category = term.category,
                    detail = "term=\"${term.text}\" in ${candidate.source.name.lowercase(Locale.ROOT)}",
                )
            }
            val neg = SensitiveLexicon.negativeWeight(candidate.text)
            if (neg > 0.0) {
                negative += neg
                negativeHits += candidate.text
            }
        }

        // ----------------------------------------------- 5. weak structural cues
        // A short numeric field with no other signal is a plausible PIN pad.
        if (signals.numericClass && signals.maxTextLength in 4..8 && evidence.none { it.category.isSecret }) {
            evidence += Evidence(
                "structure.shortNumeric", FieldSource.SEQUENCE, 0.3, Category.PIN,
                "numeric, maxLen=${signals.maxTextLength}",
            )
        }
        // A "no suggestions" flag on a password-like field is a hint, not proof.
        if (signals.noSuggestionsFlag && signals.numericClass && signals.maxTextLength in 4..8) {
            evidence += Evidence(
                "structure.noSuggestionsNumeric", FieldSource.SEQUENCE, 0.15, Category.PIN,
                "NO_SUGGESTIONS on short numeric",
            )
        }

        // ------------------------------------------------------ 6. field memory
        if (memory != null && memory.sensitive && memory.confidence.atLeast(Confidence.MODERATE)) {
            evidence += Evidence(
                "memory.previousVerdict", FieldSource.MEMORY,
                if (memory.sticky) 1.0 else 0.55,
                memory.category,
                "sticky=${memory.sticky}, previous=${memory.category}",
            )
            if (memory.sticky) hard = true
        }

        // ------------------------------------------------------- 7. content
        if (config.useContentClues && !signals.textSample.isNullOrEmpty()) {
            evidence += ContentClassifier.classify(
                text = signals.textSample,
                numericField = signals.numericClass,
                maxTextLength = signals.maxTextLength,
            )
        }

        // ------------------------------------------------------------ scoring
        val perCategory = LinkedHashMap<Category, Double>()
        for (e in evidence) {
            if (e.weight <= 0.0 || e.category == Category.NONE) continue
            perCategory[e.category] = (perCategory[e.category] ?: 0.0) + e.weight
        }

        // Negative evidence is applied only to categories without hard support.
        // Rationale: "search" in a label cannot make a declared password field
        // safe, but it can stop a bare "账号" from becoming a card field.
        val hardCategories = evidence.filter { it.hard }.mapTo(HashSet()) { it.category }
        if (negative > 0.0) {
            val it2 = perCategory.entries.iterator()
            while (it2.hasNext()) {
                val entry = it2.next()
                if (entry.key in hardCategories) continue
                // PII and weak hits absorb the full penalty; secrets that reached
                // this point were already only label-supported.
                val penalty = negative * config.negativePenalty
                val adjusted = entry.value - penalty
                if (adjusted <= 0.0) it2.remove() else perCategory[entry.key] = adjusted
            }
        }

        var best: Category = Category.NONE
        var bestScore = 0.0
        for ((category, score) in perCategory) {
            if (score > bestScore + 1e-9 ||
                (kotlin.math.abs(score - bestScore) <= 1e-9 && category.specificity > best.specificity)
            ) {
                best = category
                bestScore = score
            }
        }

        val confidence = confidenceFor(best, bestScore).let { raw ->
            // Content and structural evidence are *circumstantial*: a 6-digit run
            // could be an OTP, a price, a year or a quantity. Metadata (an input
            // type, a hint, a resource id) is authoritative in a way that typed
            // characters are not. So when nothing but circumstantial evidence
            // supports a digit-run category, the result may guard the field but
            // must never lock it — locking would silently remove candidates from
            // ordinary numeric inputs across the whole system.
            //
            // Card numbers are exempt: a Luhn-valid 13–19 digit string is strong
            // enough to stand alone (the test is 1-in-10 against random digits and
            // far weaker against real typed text, and the cost of a miss is high).
            if (raw.rank > Confidence.WEAK.rank &&
                best in CONTENT_ONLY_GUARD_CATEGORIES &&
                evidence.none { it.weight > 0.0 && it.source in AUTHORITATIVE_SOURCES }
            ) {
                Confidence.WEAK
            } else {
                raw
            }
        }
        val category = if (confidence == Confidence.NONE) Category.NONE else best

        val sticky = hard || (signals.web && category.isSecret && confidence.atLeast(Confidence.HIGH))
        val verdict = Verdict(
            category = category,
            confidence = confidence,
            score = bestScore,
            reasons = evidence.sortedByDescending { it.weight },
            signatureKey = signals.signatureKey,
            sticky = sticky,
        )

        // Optional diagnostics hook. It never receives user text (only the signals
        // snapshot, whose `textSample` this call deliberately strips) and its return
        // value is ignored.
        config.traceSink?.invoke(signals.withoutText(), verdict)
        return verdict
    }

    /**
     * Re-assesses using only content, for the case where the user keeps typing
     * into a field that looked innocent at focus time. Used to *escalate* only;
     * the caller never downgrades on this path.
     */
    fun escalateFromContent(base: Verdict, signals: FieldSignals): Verdict {
        if (!config.useContentClues) return base
        if (signals.textSample.isNullOrEmpty()) return base
        val contentEvidence = ContentClassifier.classify(
            text = signals.textSample,
            numericField = signals.numericClass,
            maxTextLength = signals.maxTextLength,
        )
        if (contentEvidence.isEmpty()) return base
        val merged = assess(signals.copy(textSample = signals.textSample))
        return if (merged.confidence.rank > base.confidence.rank || merged.category.specificity > base.category.specificity) {
            merged
        } else {
            base
        }
    }

    private fun confidenceFor(category: Category, score: Double): Confidence {
        if (category == Category.NONE) return Confidence.NONE
        val thresholds = if (category.isPii) config.piiThresholds else config.secretThresholds
        return when {
            score >= thresholds.high -> Confidence.HIGH
            score >= thresholds.moderate -> Confidence.MODERATE
            score >= thresholds.weak -> Confidence.WEAK
            else -> Confidence.NONE
        }
    }

    private fun hard(code: String, source: FieldSource, category: Category, detail: String) =
        Evidence(code = code, source = source, weight = 1.0, category = category, detail = detail, hard = true)

    companion object {
        /** Sources we trust enough to lock a field on. */
        private val AUTHORITATIVE_SOURCES = setOf(
            FieldSource.INPUT_TYPE,
            FieldSource.ACCESSIBILITY,
            FieldSource.HINT,
            FieldSource.LABEL,
            FieldSource.CONTENT_DESC,
            FieldSource.VIEW_ID,
            FieldSource.FIELD_NAME,
            FieldSource.AUTOFILL_HINT,
            FieldSource.MEMORY,
        )

        /**
         * Categories that a digit run alone can suggest but not prove. Content
         * evidence for these is capped at [Confidence.WEAK] unless metadata backs
         * it up.
         */
        private val CONTENT_ONLY_GUARD_CATEGORIES = setOf(
            Category.PIN,
            Category.OTP,
            Category.PASSWORD,
        )
    }

    /** Debug helper: a compact, content-free description of why a verdict was reached. */
    fun explain(signals: FieldSignals, verdict: Verdict): String = buildString {
        append(signals.shapeDescription())
        append(" -> ").append(verdict.category).append('/').append(verdict.confidence)
        append(" (").append(String.format(Locale.ROOT, "%.2f", verdict.score)).append(')')
        if (verdict.sticky) append(" [sticky]")
        for (r in verdict.reasons) {
            if (r.weight <= 0.0) continue
            append("\n  - ").append(r.code)
            append(" +").append(String.format(Locale.ROOT, "%.2f", r.weight))
            if (r.hard) append(" [hard]")
            if (r.detail.isNotEmpty()) append(" :: ").append(r.detail)
        }
    }
}

/** Tunables for [SensitivityDetector]. Defaults are the shipping policy. */
data class DetectorConfig(
    /**
     * How much negative (benign-context) evidence subtracts. 1.0 means a single
     * strong negative term annihilates a single strong positive one; that is the
     * right default because "搜索框里有'password'" really is a search box.
     */
    val negativePenalty: Double = 1.0,
    val secretThresholds: Thresholds = Thresholds(weak = 0.35, moderate = 0.6, high = 0.9),
    val piiThresholds: Thresholds = Thresholds(weak = 0.4, moderate = 0.7, high = 1.0),
    val useContentClues: Boolean = true,
    /** Optional diagnostics hook; return value is ignored. Never receives user text. */
    val traceSink: ((FieldSignals, Verdict) -> Boolean)? = null,
) {
    data class Thresholds(val weak: Double, val moderate: Double, val high: Double) {
        init {
            require(weak <= moderate && moderate <= high) { "thresholds must be ordered" }
        }
    }
}
