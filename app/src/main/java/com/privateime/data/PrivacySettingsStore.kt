package com.privateime.data

import android.content.Context
import android.content.SharedPreferences
import com.privateime.core.model.Category
import com.privateime.core.privacy.CandidateMode
import com.privateime.core.privacy.PrivacySettings
import com.privateime.core.privacy.Tier

/**
 * Persistence for [PrivacySettings].
 *
 * SharedPreferences rather than DataStore because this is read on the IME's
 * critical path (every focus change) and must never block on a coroutine. Writes
 * happen only when the user changes a setting, so the commit cost is irrelevant.
 */
class PrivacySettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): PrivacySettings {
        val defaults = PrivacySettings()
        return PrivacySettings(
            cloudSuggestionsEnabled = prefs.getBoolean(KEY_CLOUD, defaults.cloudSuggestionsEnabled),
            clipboardSyncEnabled = prefs.getBoolean(KEY_CLIPBOARD, defaults.clipboardSyncEnabled),
            localLearningEnabled = prefs.getBoolean(KEY_LOCAL_LEARNING, defaults.localLearningEnabled),
            adaptiveLearningEnabled = prefs.getBoolean(KEY_ADAPTIVE, defaults.adaptiveLearningEnabled),
            learnPiiLocally = prefs.getBoolean(KEY_PII, defaults.learnPiiLocally),
            guardedCandidateMode = readEnum(KEY_GUARDED_MODE, defaults.guardedCandidateMode),
            strictWebMode = prefs.getBoolean(KEY_STRICT_WEB, defaults.strictWebMode),
            strictFinanceApps = prefs.getBoolean(KEY_STRICT_FINANCE, defaults.strictFinanceApps),
            appOverrides = readAppOverrides(),
            trustedSignatures = prefs.getStringSet(KEY_TRUSTED, emptySet())?.toSet() ?: emptySet(),
            alwaysPrivateSignatures = prefs.getStringSet(KEY_ALWAYS_PRIVATE, emptySet())?.toSet() ?: emptySet(),
        )
    }

    fun save(settings: PrivacySettings) {
        prefs.edit()
            .putBoolean(KEY_CLOUD, settings.cloudSuggestionsEnabled)
            .putBoolean(KEY_CLIPBOARD, settings.clipboardSyncEnabled)
            .putBoolean(KEY_LOCAL_LEARNING, settings.localLearningEnabled)
            .putBoolean(KEY_ADAPTIVE, settings.adaptiveLearningEnabled)
            .putBoolean(KEY_PII, settings.learnPiiLocally)
            .putString(KEY_GUARDED_MODE, settings.guardedCandidateMode.name)
            .putBoolean(KEY_STRICT_WEB, settings.strictWebMode)
            .putBoolean(KEY_STRICT_FINANCE, settings.strictFinanceApps)
            .putStringSet(KEY_TRUSTED, settings.trustedSignatures)
            .putStringSet(KEY_ALWAYS_PRIVATE, settings.alwaysPrivateSignatures)
            .putString(KEY_APP_OVERRIDES, encodeAppOverrides(settings.appOverrides))
            .apply()
    }

    fun setTrusted(signatureKey: String, trusted: Boolean) {
        val current = prefs.getStringSet(KEY_TRUSTED, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (trusted) current.add(signatureKey) else current.remove(signatureKey)
        prefs.edit().putStringSet(KEY_TRUSTED, current).apply()
    }

    fun setAlwaysPrivate(signatureKey: String, always: Boolean) {
        val current = prefs.getStringSet(KEY_ALWAYS_PRIVATE, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (always) current.add(signatureKey) else current.remove(signatureKey)
        prefs.edit().putStringSet(KEY_ALWAYS_PRIVATE, current).apply()
    }

    fun setAppOverride(packageName: String, tier: Tier?) {
        val current = parseAppOverrides(prefs.getString(KEY_APP_OVERRIDES, null)).toMutableMap()
        if (tier == null) current.remove(packageName) else current[packageName] = tier
        prefs.edit().putString(KEY_APP_OVERRIDES, encodeAppOverrides(current)).apply()
    }

    private fun readEnum(key: String, default: CandidateMode): CandidateMode =
        prefs.getString(key, null)?.let { runCatching { CandidateMode.valueOf(it) }.getOrNull() } ?: default

    private fun readAppOverrides(): Map<String, Tier> =
        parseAppOverrides(prefs.getString(KEY_APP_OVERRIDES, null))

    companion object {
        private const val FILE = "privacy_settings"
        private const val KEY_CLOUD = "cloud"
        private const val KEY_CLIPBOARD = "clipboard"
        private const val KEY_LOCAL_LEARNING = "local_learning"
        private const val KEY_ADAPTIVE = "adaptive"
        private const val KEY_PII = "learn_pii"
        private const val KEY_GUARDED_MODE = "guarded_mode"
        private const val KEY_STRICT_WEB = "strict_web"
        private const val KEY_STRICT_FINANCE = "strict_finance"
        private const val KEY_TRUSTED = "trusted_signatures"
        private const val KEY_ALWAYS_PRIVATE = "always_private_signatures"
        private const val KEY_APP_OVERRIDES = "app_overrides"

        internal fun encodeAppOverrides(overrides: Map<String, Tier>): String =
            overrides.entries.joinToString(";") { it.key + ":" + it.value.name }

        internal fun parseAppOverrides(raw: String?): Map<String, Tier> {
            if (raw.isNullOrBlank()) return emptyMap()
            val out = LinkedHashMap<String, Tier>(4)
            for (entry in raw.split(';')) {
                val idx = entry.lastIndexOf(':')
                if (idx <= 0) continue
                val pkg = entry.substring(0, idx)
                val tier = runCatching { Tier.valueOf(entry.substring(idx + 1)) }.getOrNull() ?: continue
                out[pkg] = tier
            }
            return out
        }

        fun categoryLabelKey(category: Category): String =
            "category." + category.name.lowercase()
    }
}
