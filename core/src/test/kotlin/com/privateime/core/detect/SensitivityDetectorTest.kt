package com.privateime.core.detect

import com.privateime.core.model.Category
import com.privateime.core.model.Confidence
import com.privateime.core.model.FieldSignals
import com.privateime.core.model.InputTypeNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detection tests are written as *scenarios taken from real apps*, because the
 * whole difficulty of this feature is that the metadata lies. Each test names the
 * kind of app and what its field actually is.
 */
class SensitivityDetectorTest {

    private val detector = SensitivityDetector()

    private fun text(variation: Int = InputTypeNames.TEXT_VARIATION_NORMAL, flags: Int = 0) =
        InputTypeNames.CLASS_TEXT or variation or flags

    // ---------------------------------------------------------------- declared

    @Test
    fun `declared password field is locked`() {
        val v = detector.assess(
            FieldSignals(packageName = "com.example.app", inputType = text(InputTypeNames.TEXT_VARIATION_PASSWORD)),
        )
        assertEquals(Category.PASSWORD, v.category)
        assertEquals(Confidence.HIGH, v.confidence)
        assertTrue(v.secret)
        assertTrue(v.sticky)
    }

    @Test
    fun `web password variation is locked`() {
        val v = detector.assess(
            FieldSignals(packageName = "com.android.chrome", inputType = text(InputTypeNames.TEXT_VARIATION_WEB_PASSWORD), isWebView = true),
        )
        assertEquals(Category.PASSWORD, v.category)
        assertTrue(v.blocksLearning)
    }

    @Test
    fun `numeric password variation is a pin`() {
        val v = detector.assess(
            FieldSignals(
                packageName = "com.bank.app",
                inputType = InputTypeNames.CLASS_NUMBER or InputTypeNames.NUMBER_VARIATION_PASSWORD,
            ),
        )
        assertEquals(Category.PIN, v.category)
        assertTrue(v.secret)
    }

    @Test
    fun `accessibility isPassword alone is enough`() {
        // Chrome sometimes reports a plain text type for an <input type=password>.
        val v = detector.assess(
            FieldSignals(
                packageName = "com.android.chrome",
                inputType = text(),
                isPassword = true,
                isWebView = true,
            ),
        )
        assertEquals(Category.PASSWORD, v.category)
        assertTrue(v.blocksLearning)
    }

    // ------------------------------------------------------- undeclared, labelled

    @Test
    fun `mislabelled password box caught by chinese hint`() {
        // The classic case: inputType is plain text, only the placeholder tells the truth.
        val v = detector.assess(
            FieldSignals(
                packageName = "com.example.shop",
                inputType = text(),
                hintText = "请输入登录密码",
            ),
        )
        assertEquals(Category.PASSWORD, v.category)
        assertTrue(v.blocksLearning)
    }

    @Test
    fun `login password via resource id`() {
        val v = detector.assess(
            FieldSignals(
                packageName = "com.example.shop",
                inputType = text(),
                viewIdResourceName = "com.example.shop:id/et_login_pwd",
            ),
        )
        assertEquals(Category.PASSWORD, v.category)
    }

    @Test
    fun `camelcase field name`() {
        val v = detector.assess(
            FieldSignals(
                packageName = "com.example.shop",
                inputType = text(),
                fieldName = "confirmPassword",
            ),
        )
        assertEquals(Category.PASSWORD, v.category)
        assertTrue(v.blocksLearning)
    }

    @Test
    fun `sms code field is an otp`() {
        val v = detector.assess(
            FieldSignals(
                packageName = "com.example.bank",
                inputType = InputTypeNames.CLASS_NUMBER,
                maxTextLength = 6,
                hintText = "请输入6位短信验证码",
            ),
        )
        assertEquals(Category.OTP, v.category)
        assertTrue(v.secret)
    }

    @Test
    fun `english one-time code placeholder`() {
        val v = detector.assess(
            FieldSignals(
                packageName = "com.example",
                inputType = InputTypeNames.CLASS_NUMBER,
                maxTextLength = 6,
                hintText = "Enter the 6-digit code",
            ),
        )
        assertEquals(Category.OTP, v.category)
    }

    @Test
    fun `card number label`() {
        val v = detector.assess(
            FieldSignals(
                packageName = "com.example.pay",
                inputType = InputTypeNames.CLASS_NUMBER,
                maxTextLength = 19,
                hintText = "银行卡号",
            ),
        )
        assertEquals(Category.PAYMENT_CARD, v.category)
        assertTrue(v.secret)
    }

    @Test
    fun `payment pin in chinese`() {
        val v = detector.assess(
            FieldSignals(
                packageName = "com.example.pay",
                inputType = InputTypeNames.CLASS_NUMBER,
                maxTextLength = 6,
                hintText = "请输入支付密码",
            ),
        )
        assertTrue(v.secret)
        assertTrue(v.category == Category.PIN || v.category == Category.PASSWORD)
    }

    // ------------------------------------------------------- must stay productive

    @Test
    fun `search box that mentions password stays open`() {
        // The negative-signal mechanism exists for exactly this: a password
        // manager's search box says "搜索密码" and must keep predictions.
        val v = detector.assess(
            FieldSignals(
                packageName = "com.example.keepass",
                inputType = text(),
                hintText = "搜索密码",
            ),
        )
        assertFalse("a search box must not lock", v.blocksLearning)
        assertFalse(v.category.isSecret)
    }

    @Test
    fun `english search field stays open`() {
        val v = detector.assess(
            FieldSignals(
                packageName = "com.example",
                inputType = text(),
                hintText = "Search your saved passwords",
            ),
        )
        assertFalse(v.blocksLearning)
        assertFalse(v.category.isSecret)
    }

    @Test
    fun `chat input stays open`() {
        val v = detector.assess(
            FieldSignals(
                packageName = "com.tencent.mm",
                inputType = text(),
                hintText = "说点什么…",
                viewIdResourceName = "com.tencent.mm:id/chat_input",
            ),
        )
        assertEquals(Category.NONE, v.category)
        assertEquals(Confidence.NONE, v.confidence)
    }

    @Test
    fun `comment box stays open`() {
        val v = detector.assess(
            FieldSignals(
                packageName = "com.example.social",
                inputType = text(flags = InputTypeNames.FLAG_AUTO_CORRECT),
                hintText = "说点什么…",
            ),
        )
        assertEquals(Category.NONE, v.category)
    }

    @Test
    fun `username field is pii not secret`() {
        val v = detector.assess(
            FieldSignals(
                packageName = "com.example",
                inputType = text(),
                hintText = "用户名",
            ),
        )
        assertFalse("username is not a credential", v.category.isSecret)
    }

    @Test
    fun `email field is pii`() {
        val v = detector.assess(
            FieldSignals(
                packageName = "com.example",
                inputType = text(InputTypeNames.TEXT_VARIATION_EMAIL_ADDRESS),
                hintText = "邮箱",
            ),
        )
        assertEquals(Category.PII, v.category)
        assertTrue(v.pii)
        assertFalse(v.secret)
        assertFalse("pii must not block learning outright", v.blocksLearning)
    }

    @Test
    fun `short numeric field with no label is at most weak`() {
        val v = detector.assess(
            FieldSignals(
                packageName = "com.example",
                inputType = InputTypeNames.CLASS_NUMBER,
                maxTextLength = 6,
            ),
        )
        // Structural inference alone must not lock a field: too many legitimate
        // six-digit inputs (quantities, years, prices).
        assertFalse(v.blocksLearning)
    }

    // ------------------------------------------------------------------ content

    @Test
    fun `luhn-valid card number in a plain text field escalates`() {
        val v = detector.assess(
            FieldSignals(
                packageName = "com.android.chrome",
                inputType = text(InputTypeNames.TEXT_VARIATION_WEB_EDIT_TEXT),
                isWebView = true,
                maxTextLength = 19,
                textSample = "4111 1111 1111 1111",
            ),
        )
        assertEquals(Category.PAYMENT_CARD, v.category)
        assertTrue(v.blocksLearning)
    }

    @Test
    fun `random digit string fails luhn and stays open`() {
        val v = detector.assess(
            FieldSignals(
                packageName = "com.example",
                inputType = text(),
                maxTextLength = 19,
                textSample = "4111 1111 1111 1112",
            ),
        )
        assertFalse(v.category.isSecret)
    }

    @Test
    fun `email-shaped content in unlabelled field`() {
        val v = detector.assess(
            FieldSignals(
                packageName = "com.android.chrome",
                inputType = text(InputTypeNames.TEXT_VARIATION_WEB_EDIT_TEXT),
                isWebView = true,
                textSample = "someone@example.com",
            ),
        )
        assertEquals(Category.PII, v.category)
    }

    @Test
    fun `six digits typed in a plain box do not immediately lock`() {
        // A 6-digit run alone is ambiguous; it should guard, not lock.
        val v = detector.assess(
            FieldSignals(
                packageName = "com.example",
                inputType = InputTypeNames.CLASS_NUMBER,
                maxTextLength = 6,
                textSample = "482913",
            ),
        )
        assertEquals(Category.PIN, v.category)
        // Confidence is deliberately below the lock threshold for content-only PINs.
        assertFalse(v.blocksLearning)
    }

    // ------------------------------------------------------------------- memory

    @Test
    fun `sticky memory keeps a field locked when the app hides its type`() {
        val signals = FieldSignals(
            packageName = "com.example.bank",
            inputType = text(),
            viewIdResourceName = "com.example.bank:id/pwd",
        )
        val first = detector.assess(signals)
        assertTrue(first.blocksLearning)

        // Second visit: the app now reports plain text and no hint.
        val second = detector.assess(
            FieldSignals(
                packageName = "com.example.bank",
                inputType = text(),
                viewIdResourceName = "com.example.bank:id/pwd",
            ),
            memory = first,
        )
        assertTrue("sticky memory must survive", second.blocksLearning)
    }

    // ---------------------------------------------------------------- web pages

    @Test
    fun `web page title supplies context`() {
        val v = detector.assess(
            FieldSignals(
                packageName = "com.android.chrome",
                inputType = text(InputTypeNames.TEXT_VARIATION_WEB_EDIT_TEXT),
                isWebView = true,
                screenTitle = "网上银行登录",
                viewIdResourceName = "password",
            ),
        )
        assertTrue(v.secret)
    }

    @Test
    fun `web search field stays open`() {
        val v = detector.assess(
            FieldSignals(
                packageName = "com.android.chrome",
                inputType = text(InputTypeNames.TEXT_VARIATION_WEB_EMAIL_ADDRESS),
                isWebView = true,
                screenTitle = "Google",
                hintText = "Search",
                viewIdResourceName = "q",
            ),
        )
        // Email variation on a web search box: pii, but not a credential, and the
        // search negative keeps the score down.
        assertFalse(v.secret)
    }

    // ------------------------------------------------------------- explanation

    @Test
    fun `explain never contains the typed text`() {
        val signals = FieldSignals(
            packageName = "com.example",
            inputType = text(InputTypeNames.TEXT_VARIATION_PASSWORD),
            textSample = "hunter2-secret-value",
        )
        val v = detector.assess(signals)
        val explanation = detector.explain(signals, v)
        assertFalse(explanation.contains("hunter2"))
        assertTrue(explanation.contains("inputType.password"))
    }

    @Test
    fun `evidence carries no raw text`() {
        val signals = FieldSignals(
            packageName = "com.example",
            inputType = text(),
            hintText = "请输入密码",
            textSample = "MySecretValue123!",
        )
        val v = detector.assess(signals)
        for (reason in v.reasons) {
            assertFalse(reason.detail.contains("MySecretValue"))
        }
    }
}
