package com.privateime.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.privateime.R
import com.privateime.clipboard.ClipboardHistory
import com.privateime.core.cleanup.ResidueScanner
import com.privateime.core.lexicon.LearnedWord
import com.privateime.data.AuditRepository
import com.privateime.data.SqliteLearningStore
import java.util.concurrent.Executors

/**
 * The "find and clean what was already learned" screen.
 *
 * This is the answer to the hardest part of the incident: the fix prevents new
 * leaks, but the user's old password is still in the dictionary. This screen
 * finds it, shows it masked, and lets the user remove it — from the private store
 * *and* from the system user dictionary that other keyboards read.
 *
 * Three deliberate choices:
 *
 *  1. **Scanning runs off the main thread.** A large dictionary plus decryption of
 *     clipboard history can take a moment; the IME being blocked behind it would
 *     be worse than the leak.
 *  2. **Grouped, one-tap bulk actions.** Users will not review 200 rows. They will
 *     tap "delete 34 card numbers".
 *  3. **Masked previews.** Showing the full leaked password on a screen that any
 *     other app can screenshot would repeat the harm in a new medium.
 */
class CleanupActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "cleanup-scan") }
    private lateinit var store: SqliteLearningStore
    private lateinit var container: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var actionsView: LinearLayout

    private var lastReport: ResidueScanner.Report? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SqliteLearningStore(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        statusView = TextView(this).apply { textSize = 15f }
        actionsView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(statusView)
        container.addView(actionsView)
        setContentView(ScrollView(this).apply { addView(container) })
        setTitle(R.string.settings_section_cleanup)
        startScan()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        store.close()
        super.onDestroy()
    }

    private fun startScan() {
        statusView.setText(R.string.settings_cleanup_scanning)
        actionsView.removeAllViews()
        executor.execute {
            val scanner = ResidueScanner(store)
            val report = try {
                scanner.scan()
            } catch (e: Exception) {
                runOnUiThread {
                    statusView.text = getString(
                        R.string.settings_cleanup_failed,
                        e.message ?: getString(R.string.settings_cleanup_failed_unknown),
                    )
                }
                return@execute
            }
            runOnUiThread { showReport(report) }
        }
    }

    private fun showReport(report: ResidueScanner.Report) {
        lastReport = report
        if (report.totalAtRisk == 0) {
            statusView.setText(R.string.settings_cleanup_clean)
            return
        }
        statusView.text = getString(
            R.string.settings_cleanup_found,
            report.totalAtRisk,
            report.autoDeleteCount,
        )
        actionsView.removeAllViews()

        if (report.autoDeleteCount > 0) {
            actionsView.addView(
                Button(this).apply {
                    setText(
                        getString(R.string.settings_cleanup_auto, report.autoDeleteCount),
                    )
                    setOnClickListener { deleteAuto(report) }
                },
            )
        }

        val groups = ResidueScanner.groupForDisplay(report)
        for ((key, findings) in groups) {
            val header = TextView(this).apply {
                text = getString(R.string.settings_cleanup_group_header, groupTitle(key), findings.size)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(16), 0, dp(4))
            }
            actionsView.addView(header)
            for (finding in findings.take(50)) {
                actionsView.addView(rowFor(finding))
            }
            if (findings.size > 50) {
                actionsView.addView(
                    TextView(this).apply {
                        text = getString(R.string.settings_cleanup_more, findings.size - 50)
                        textSize = 13f
                        setPadding(0, dp(4), 0, dp(4))
                    },
                )
            }
        }
    }

    private fun rowFor(finding: ResidueScanner.Finding): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val preview = TextView(this).apply {
            text = finding.preview
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val detail = TextView(this).apply {
            text = riskLabel(finding)
            textSize = 11f
        }
        val delete = Button(this).apply {
            setText(R.string.delete)
            setOnClickListener { deleteOne(finding) }
        }
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(preview)
            addView(detail)
        }
        row.addView(textColumn)
        row.addView(delete)
        return row
    }

    private fun riskLabel(finding: ResidueScanner.Finding): String {
        val base = when (finding.disposition) {
            ResidueScanner.Finding.Disposition.AUTO_DELETE -> "确定敏感"
            ResidueScanner.Finding.Disposition.REVIEW -> "疑似敏感"
            ResidueScanner.Finding.Disposition.KEEP -> ""
        }
        var label = getString(R.string.settings_cleanup_risk_detail, base)
        finding.word.packageName?.let {
            label = getString(R.string.settings_cleanup_risk_detail_source, label, it)
        }
        if (finding.word.occurrences > 1) {
            label = getString(R.string.settings_cleanup_risk_detail_count, label, finding.word.occurrences)
        }
        return label
    }

    private fun deleteOne(finding: ResidueScanner.Finding) {
        confirmDelete(listOf(finding)) {
            val removed = ResidueScanner(store).purge(listOf(finding))
            afterDelete(removed)
        }
    }

    private fun deleteAuto(report: ResidueScanner.Report) {
        val auto = report.findings.filter {
            it.disposition == ResidueScanner.Finding.Disposition.AUTO_DELETE
        }
        confirmDelete(auto) {
            val removed = ResidueScanner(store).purge(auto)
            afterDelete(removed)
        }
    }

    private fun confirmDelete(findings: List<ResidueScanner.Finding>, onConfirm: () -> Unit) {
        if (findings.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_cleanup_dialog_title)
            .setMessage(R.string.settings_cleanup_dialog_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> onConfirm() }
            .show()
    }

    private fun afterDelete(removed: Int) {
        // Clipboard history can hold the same strings. Scrub only entries the
        // credential shape-checker flags — a blanket `scrub { true }` would
        // destroy the user's whole history for deleting one e-mail address.
        val clipboardRemoved = try {
            ClipboardHistory(this).scrub { com.privateime.core.privacy.ClipboardGuard.isResidue(it) }
        } catch (_: Exception) {
            0
        }
        AuditRepository(this).recordCleanup(
            removed = removed,
            scanSize = lastReport?.scanned ?: 0,
            source = "manual",
        )
        val message = if (clipboardRemoved > 0) {
            getString(R.string.settings_cleanup_done, removed) +
                getString(R.string.settings_cleanup_clipboard_also, clipboardRemoved)
        } else {
            getString(R.string.settings_cleanup_done, removed)
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        startScan()
    }

    private fun groupTitle(key: String): String = when (key) {
        "cards" -> getString(R.string.settings_cleanup_group_cards)
        "codes" -> getString(R.string.settings_cleanup_group_codes)
        "emails" -> getString(R.string.settings_cleanup_group_emails)
        "national_ids" -> getString(R.string.settings_cleanup_group_national_ids)
        "tokens" -> getString(R.string.settings_cleanup_group_tokens)
        "pins" -> getString(R.string.settings_cleanup_group_pins)
        else -> getString(R.string.settings_cleanup_group_other)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
