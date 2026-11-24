package com.privateime.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.privateime.R
import com.privateime.core.privacy.PrivacySettings
import com.privateime.data.PrivacySettingsStore
import com.privateime.input.PrivacyAccessibilityService
import android.text.InputType

/**
 * The privacy settings screen.
 *
 * Design constraints that shaped it:
 *
 *  * Every switch states its *effect*, not its mechanism ("never in password
 *    fields" rather than "cloud sink disabled"), because a settings screen is the
 *    only place the user ever sees the policy.
 *  * The cleanup entry point is at the top, not buried: the reported harm is
 *    "already learned", so fixing it must be one tap from launch.
 *  * Destructive actions require an explicit confirmation and say that they are
 *    irreversible. There is no undo for a deleted word because keeping a copy
 *    would defeat the purpose.
 */
class SettingsActivity : Activity() {

    private lateinit var store: PrivacySettingsStore
    private lateinit var settings: PrivacySettings
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PrivacySettingsStore(this)
        settings = store.load()

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(container) })
        setTitle(R.string.settings_title)
        build()
    }

    override fun onResume() {
        super.onResume()
        // Accessibility state can change while we are backgrounded.
        rebuildIfNeeded()
    }

    private fun rebuildIfNeeded() {
        container.removeAllViews()
        build()
    }

    private fun build() {
        title(R.string.app_name)

        // ---- setup: an IME nobody can find is not a privacy fix ---------------
        section(R.string.settings_section_setup)
        val imeEnabled = ImeSetup.isEnabled(this)
        val imeSelected = ImeSetup.isSelected(this)
        label(
            when {
                !imeEnabled -> getString(R.string.settings_setup_not_enabled)
                !imeSelected -> getString(R.string.settings_setup_enabled_not_selected)
                else -> getString(R.string.settings_setup_ready)
            },
            bold = true,
        )
        if (!imeEnabled) {
            button(R.string.settings_setup_enable) { ImeSetup.openEnableScreen(this) }
        } else if (!imeSelected) {
            button(R.string.settings_setup_select) { ImeSetup.openPicker(this) }
        }

        // ---- cleanup first: it is the fix for harm already done --------------
        section(R.string.settings_section_cleanup)
        paragraph(R.string.settings_cleanup_desc)
        button(R.string.settings_cleanup_button) {
            startActivity(Intent(this, CleanupActivity::class.java))
        }
        button(R.string.settings_section_audit) {
            startActivity(Intent(this, AuditActivity::class.java))
        }

        // ---- learning --------------------------------------------------------
        section(R.string.settings_section_learning)
        toggle(
            R.string.settings_local_learning,
            R.string.settings_local_learning_desc,
            settings.localLearningEnabled,
        ) { settings = settings.copy(localLearningEnabled = it); save() }
        toggle(
            R.string.settings_adaptive,
            null,
            settings.adaptiveLearningEnabled,
        ) { settings = settings.copy(adaptiveLearningEnabled = it); save() }
        toggle(
            R.string.settings_learn_pii,
            R.string.settings_learn_pii_desc,
            settings.learnPiiLocally,
        ) { settings = settings.copy(learnPiiLocally = it); save() }

        // ---- network ---------------------------------------------------------
        section(R.string.settings_section_network)
        toggle(
            R.string.settings_cloud,
            R.string.settings_cloud_desc,
            settings.cloudSuggestionsEnabled,
        ) { settings = settings.copy(cloudSuggestionsEnabled = it); save() }

        // ---- clipboard -------------------------------------------------------
        section(R.string.settings_section_clipboard)
        toggle(
            R.string.settings_clipboard,
            R.string.settings_clipboard_desc,
            settings.clipboardSyncEnabled,
        ) { settings = settings.copy(clipboardSyncEnabled = it); save() }

        // ---- detection -------------------------------------------------------
        section(R.string.settings_section_detection)
        toggle(
            R.string.settings_strict_web,
            null,
            settings.strictWebMode,
        ) { settings = settings.copy(strictWebMode = it); save() }
        toggle(
            R.string.settings_strict_finance,
            null,
            settings.strictFinanceApps,
        ) { settings = settings.copy(strictFinanceApps = it); save() }

        // ---- accessibility ---------------------------------------------------
        section(R.string.settings_accessibility_title)
        paragraph(R.string.settings_accessibility_desc)
        val enabled = isAccessibilityServiceEnabled()
        label(
            if (enabled) getString(R.string.settings_accessibility_on)
            else getString(R.string.settings_accessibility_off),
            bold = true,
        )
        button(R.string.settings_accessibility_button) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // ---- danger zone -----------------------------------------------------
        section(R.string.settings_section_danger)
        button(R.string.settings_purge_all) { confirmPurgeAll() }
    }

    private fun confirmPurgeAll() {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings_purge_all)
            .setMessage(R.string.settings_purge_all_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                // Wipe the private store, the system dictionary mirror and history.
                val learning = com.privateime.data.SqliteLearningStore(this)
                learning.purgeSink(com.privateime.core.lexicon.LearnedWord.Origin.LOCAL_DICTIONARY)
                learning.purgeSink(com.privateime.core.lexicon.LearnedWord.Origin.ADAPTIVE_MODEL)
                learning.purgeSink(com.privateime.core.lexicon.LearnedWord.Origin.CLOUD_VOCABULARY)
                learning.purgeSink(com.privateime.core.lexicon.LearnedWord.Origin.TYPING_HISTORY)
                learning.close()
                com.privateime.clipboard.ClipboardHistory(this).clear()
                com.privateime.data.AuditRepository(this).clear()
                toast(R.string.settings_purge_done)
            }
            .show()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, PrivacyAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it) == expected
        }
    }

    private fun save() {
        store.save(settings)
        // The IME re-reads settings on its next focus; there is no live connection
        // to push through, which also means a crash of either process cannot put
        // them out of sync.
    }

    // ------------------------------------------------------------- view helpers

    private fun title(res: Int) {
        container.addView(
            TextView(this).apply {
                setText(res)
                textSize = 22f
                setPadding(0, 0, 0, dp(12))
            },
        )
    }

    private fun section(res: Int) {
        container.addView(
            TextView(this).apply {
                setText(res)
                textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(20), 0, dp(6))
            },
        )
    }

    private fun paragraph(res: Int) {
        container.addView(
            TextView(this).apply {
                setText(res)
                textSize = 13f
                setPadding(0, 0, 0, dp(8))
            },
        )
    }

    private fun label(text: String, bold: Boolean = false) {
        container.addView(
            TextView(this).apply {
                this.text = text
                textSize = 14f
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
        )
    }

    private fun toggle(titleRes: Int, descRes: Int?, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(
            TextView(this).apply {
                setText(titleRes)
                textSize = 15f
            },
        )
        if (descRes != null) {
            texts.addView(
                TextView(this).apply {
                    setText(descRes)
                    textSize = 12f
                    setPadding(0, dp(2), 0, 0)
                },
            )
        }
        val toggleView = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _: CompoundButton, value: Boolean -> onChange(value) }
        }
        row.addView(texts)
        row.addView(toggleView)
        container.addView(row)
    }

    private fun button(res: Int, onClick: () -> Unit) {
        container.addView(
            android.widget.Button(this).apply {
                setText(res)
                setOnClickListener { onClick() }
            },
        )
    }

    private fun toast(res: Int) {
        android.widget.Toast.makeText(this, res, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
