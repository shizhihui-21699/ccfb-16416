package com.privateime.core.privacy

/**
 * A small, curated list of packages where extra caution is warranted.
 *
 * **What this is not:** a way to detect sensitive fields. A finance app's chat
 * box is not sensitive, and a notes app's password box is. The registry can only
 * ever *raise* a field to [Tier.GUARDED]; it can never lock one, and an explicit
 * user override always wins.
 *
 * **What is deliberately excluded:** "super apps" that are a bank *and* a chat
 * app (WeChat, Alipay, KakaoTalk, LINE). Raising every field in those to GUARDED
 * would cut cloud suggestions across the user's primary messaging surface — the
 * exact experience regression that makes people give up on privacy modes. Their
 * payment screens are caught by their own labels ("支付密码", "卡号"), which is
 * the mechanism that generalizes anyway.
 */
class AppRegistry private constructor(
    private val financePackages: Set<String>,
    private val financeSegmentTokens: Set<String>,
) {

    /**
     * True when [packageName] is a curated finance/payment app.
     *
     * Matching is by whole package segment, not substring: `com.acme.pay` is
     * caught, `com.acme.payroll` is not (`payroll` is its own segment), and
     * `com.paypal.android` is caught via the explicit list rather than the
     * `pay` token.
     */
    fun isFinance(packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        if (packageName in financePackages) return true
        if (financeSegmentTokens.isEmpty()) return false
        val segments = packageName.split('.')
        // The application-id leaf is what vendors actually vary; a match deeper
        // in the package path ("com.acme.bank.feature") is usually a module name.
        return segments.any { it.lowercase() in financeSegmentTokens }
    }

    companion object {
        val EMPTY = AppRegistry(emptySet(), emptySet())

        /** Segment values that are strong enough to imply a finance product. */
        private val TOKENS: Set<String> = setOf(
            "bank", "banking", "mobilebank", "ebank", "ebanking", "mbank",
            "wallet", "payments", "payment", "creditcard", "debitcard",
            "securities", "broker", "brokerage", "invest", "investment",
            "insurance", "crypto", "exchange", "fintech", "lending", "loan",
        )

        /**
         * Default seed. Short on purpose: every entry is a maintenance burden and
         * a place to be wrong, so only vendors whose *core* product is money
         * movement are listed.
         */
        fun default(): AppRegistry = AppRegistry(
            financePackages = setOf(
                // Global
                "com.chase.sig.android",
                "com.bankofamerica.cashpromobile",
                "com.infonow.bofa",
                "com.wf.wellsfargomobile",
                "com.citi.citimobile",
                "com.paypal.android.p2pmobile",
                "com.venmo",
                "com.squareup.cash",
                "com.coinbase.android",
                "com.revolut.revolut",
                "com.monzo.monzo",
                "com.grppl.android.shell.CMBlloydsTSB73",
                // Banking cores of super apps are *not* here on purpose.
                // India
                "com.icicibank.pocketsbyicici",
                "com.sbi.SBIFreedomPlus",
                "com.hdfcbank.payzapp",
                // Japan / Korea
                "jp.mufg.bk.applisp.app",
                "com.kbstar.kbbank",
                "com.shinhan.sbanking",
                "com.toss.android",
            ),
            financeSegmentTokens = TOKENS,
        )

        /** Used by tests to build a deterministic registry. */
        fun of(packages: Set<String>): AppRegistry = AppRegistry(packages, emptySet())
    }
}
