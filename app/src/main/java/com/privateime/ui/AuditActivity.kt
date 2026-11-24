package com.privateime.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.privateime.R
import com.privateime.data.AuditRepository
import java.text.DateFormat
import java.util.Date

/**
 * Shows the audit trail: which apps the shield engaged in, when, and why.
 *
 * Everything on this screen comes from [com.privateime.core.engine.AuditLog.Entry],
 * which has no field for typed content. The screen is the user-visible proof that
 * the "record nothing" claim is structural rather than a promise.
 */
class AuditActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        val repository = AuditRepository(this)
        val entries = repository.recent(200)
        if (entries.isEmpty()) {
            container.addView(TextView(this).apply { setText(R.string.settings_audit_empty) })
        } else {
            val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            for (entry in entries) {
                container.addView(
                    TextView(this).apply {
                        text = getString(
                            R.string.settings_audit_item,
                            formatter.format(Date(entry.atMillis)),
                            entry.packageName.ifBlank { "—" },
                            entry.tier.name.lowercase(),
                        )
                        textSize = 13f
                        setPadding(0, dp(4), 0, dp(4))
                    },
                )
            }
        }
        container.addView(
            Button(this).apply {
                setText(R.string.settings_audit_clear)
                setOnClickListener {
                    repository.clear()
                    recreate()
                }
            },
        )
        setContentView(ScrollView(this).apply { addView(container) })
        setTitle(R.string.settings_section_audit)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
