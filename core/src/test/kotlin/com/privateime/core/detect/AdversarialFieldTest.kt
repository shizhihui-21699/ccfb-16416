package com.privateime.core.detect

import com.privateime.core.engine.FieldPrivacyEngine
import com.privateime.core.lexicon.InMemoryLearningStore
import com.privateime.core.model.Category
import com.privateime.core.model.FieldSignals
import com.privateime.core.model.InputTypeNames
import com.privateime.core.privacy.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adversarial field shapes, modelled on real anti-autofill and anti-detection
 * tricks. Each test is a scenario an attacker or a careless developer produces.
 */
class AdversarialFieldTest {

    private val detector = SensitivityDetector()

    @Test
    fun `app declares plain text but hint is password - locked`() {
        val v = detector.assess(
            FieldSignals(
                packageName = "com.example",
                inputType = InputTypeNames.CLASS_TEXT,
                hintText = "密码",
            ),
        )
        assertTrue(v.secret)
    }

    @Test
    fun `app declares search but the resource id says password - label wins over weak negative`() {
        // "搜索" is a negative term worth 0.75. A password resource id is +1.0.
        // The field is a password-with-a-search-toggle only if the id is specific.
        val v = detector.assess(
            FieldSignals(
                packageName = "com.example.passwordmanager",
                inputType = InputTypeNames.CLASS_TEXT,
                viewIdResourceName = "com.example.passwordmanager:id/et_master_password",
                hintText = "搜索",
            ),
        )
        // 1.0 (password id) - 0.75 (search) = 0.25 < weak threshold → not locked.
        // That is the correct trade: a search box in a password manager is a search box.
        assertFalse(v.blocksLearning)
    }

    @Test
    fun `pin pad digits with a pin resource id lock`() {
        // A PIN pad built as four numeric EditTexts each maxLength=1. The resource
        // id names them "pin_digit_N", which is explicit evidence, so they lock.
        for (i in 0 until 4) {
            val v = detector.assess(
                FieldSignals(
                    packageName = "com.example.bank",
                    inputType = InputTypeNames.CLASS_NUMBER,
                    maxTextLength = 1,
                    viewIdResourceName = "com.example.bank:id/pin_digit_$i",
                ),
            )
            assertTrue("digit $i is labelled as a pin digit", v.blocksLearning)
        }
    }

    @Test
    fun `anonymous single digit boxes are guarded not locked`() {
        // The same layout but with content-less ids ("code_0"): no lexical evidence,
        // so structure alone must not lock — a one-character numeric field is also
        // how an age or a quantity box is built.
        for (i in 0 until 4) {
            val v = detector.assess(
                FieldSignals(
                    packageName = "com.example.bank",
                    inputType = InputTypeNames.CLASS_NUMBER,
                    maxTextLength = 1,
                    viewIdResourceName = "com.example.bank:id/digit_$i",
                ),
            )
            assertFalse("digit $i has no lexical evidence", v.blocksLearning)
        }
    }

    @Test
    fun `web form with no id and no label but password content`() {
        val v = detector.assess(
            FieldSignals(
                packageName = "com.android.chrome",
                inputType = InputTypeNames.CLASS_TEXT or InputTypeNames.TEXT_VARIATION_WEB_EDIT_TEXT,
                isWebView = true,
                textSample = "Xk9#mQ2vL7p",
                maxTextLength = -1,
            ),
        )
        // A long single-token mixed string is credential-shaped → guarded (content
        // evidence alone is capped at WEAK, so it guards but does not lock).
        assertTrue("password-shaped content must at least guard", v.sensitive)
        assertFalse("content alone must not lock a password field", v.blocksLearning)
    }

    @Test
    fun `ime no-personalized-learning flag is respected`() {
        val v = detector.assess(
            FieldSignals(
                packageName = "com.example",
                inputType = InputTypeNames.CLASS_TEXT,
                imeOptions = com.privateime.core.model.ImeFlags.NO_PERSONALIZED_LEARNING,
            ),
        )
        // The flag alone does not change category, but it must be recorded.
        assertTrue(v.reasons.any { it.code == "ime.noPersonalizedLearning" })
    }

    @Test
    fun `web card field with only a name attribute`() {
        val v = detector.assess(
            FieldSignals(
                packageName = "com.android.chrome",
                inputType = InputTypeNames.CLASS_TEXT or InputTypeNames.TEXT_VARIATION_WEB_EDIT_TEXT,
                isWebView = true,
                viewIdResourceName = "cardnumber",
                maxTextLength = 19,
            ),
        )
        assertEquals(Category.PAYMENT_CARD, v.category)
        assertTrue(v.blocksLearning)
    }

    @Test
    fun `six separate otp boxes each declare plain number`() {
        // "验证码" hint on the group is often only on the first box. The first locks;
        // the rest are guarded by structure — acceptable, since they hold 1 digit.
        val first = detector.assess(
            FieldSignals(
                packageName = "com.example",
                inputType = InputTypeNames.CLASS_NUMBER,
                maxTextLength = 1,
                hintText = "请输入验证码",
            ),
        )
        assertTrue(first.secret)
    }
}

/**
 * Precision/recall sanity for the credential-shape heuristic, which exists because
 * the live guard must not cut suggestions during ordinary typing.
 */
class CredentialShapePrecisionTest {

    @Test
    fun `ordinary prose and identifiers are not credentials`() {
        val normal = listOf(
            "hello", "明天见", "iPhone15", "Wi-Fi", "user@name", "C++17", "3.14159",
            "OK", "U.S.A.", "Hello,World!", "v2.0.1", "北京2024", "MyPassword",
            "WiFi6", "COVID-19", "H2O", "Note:1", "a.b.c.", "well-known", "e-mail",
        )
        for (text in normal) {
            assertFalse("should not be flagged: $text", ContentClassifier.looksLikeCredential(text))
        }
    }

    @Test
    fun `real credential shapes are caught`() {
        val creds = listOf("Xk9#mQ2vL7p", "MyP@ssw0rd123", "Tr0ub4dor&3", "p@ssW0rd!")
        for (text in creds) {
            assertTrue("should be flagged: $text", ContentClassifier.looksLikeCredential(text))
        }
    }

    @Test
    fun `residue scan is looser and catches digitless passphrases`() {
        // The review path intentionally accepts these so a passphrase without a
        // digit is not silently ignored in the dictionary.
        assertFalse(ContentClassifier.looksLikeCredential("Correct#Horse", requireDigit = true))
        assertTrue(ContentClassifier.looksLikeCredential("Correct#Horse", requireDigit = false))
    }

    @Test
    fun `live classification of password-shaped content guards but does not lock`() {
        val detector = SensitivityDetector()
        val v = detector.assess(
            FieldSignals(
                packageName = "com.android.chrome",
                inputType = InputTypeNames.CLASS_TEXT or InputTypeNames.TEXT_VARIATION_WEB_EDIT_TEXT,
                isWebView = true,
                textSample = "Xk9#mQ2vL7p",
                maxTextLength = -1,
            ),
        )
        assertTrue(v.sensitive)
        assertFalse("content alone must not lock", v.blocksLearning)
    }
}
