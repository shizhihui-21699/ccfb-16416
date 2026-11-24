package com.privateime.input

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * The optional accessibility service that feeds [AccessibilityFieldProbe].
 *
 * ## Scope, stated as narrowly as the platform allows
 *
 *  * It never performs gestures, never clicks, never uses "explore by touch".
 *  * It only reads when the IME is in the foreground and has asked for a
 *    particular package (see `AccessibilityFieldProbe.expectPackage`).
 *  * It reads the focused editable node and, at most, its nearest textual
 *    sibling and parent — nothing else in the tree.
 *  * It does not persist anything. Everything it finds is merged into the
 *    in-memory field snapshot for one focus session.
 *
 * The service exists because Android gives IMEs no other way to see `labelFor`,
 * resource ids or `isPassword` for fields that are not correctly declared.
 */
class PrivacyAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityFieldProbe.onServiceConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Events are only a wake-up. All reads are pull-based and go through
        // `AccessibilityFieldProbe.probe`, which enforces the package guard.
        // This method deliberately does nothing to avoid accumulating state from
        // events the IME never asked about.
    }

    override fun onInterrupt() {
        // No long-running work to interrupt; the probe is a synchronous read.
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AccessibilityFieldProbe.onServiceDisconnected()
        return super.onUnbind(intent)
    }
}
