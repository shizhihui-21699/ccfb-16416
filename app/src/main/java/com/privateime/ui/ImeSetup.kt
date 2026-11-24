package com.privateime.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.privateime.ime.PrivateImeService

/**
 * Helpers for the two pieces of setup an IME needs: enable it, and select it.
 *
 * Android separates these on purpose (enabling a keyboard is a security-relevant
 * choice, so it is not a runtime permission the app can request), which means an
 * app can only *deep-link* the user to the right screen. Doing that at the right
 * moment — on first launch, with one button — is the difference between a feature
 * nobody finds and one that works.
 */
object ImeSetup {

    /** True when this keyboard is present in the enabled-IME list. */
    fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_INPUT_METHODS,
        ) ?: return false
        val expected = ComponentName(context, PrivateImeService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    /** True when this keyboard is the *currently selected* one. */
    fun isSelected(context: Context): Boolean {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return false
        // getCurrentInputMethodInfo() is the modern accessor; the legacy
        // `currentInputMethodName` string property no longer exists.
        val current = try {
            manager.currentInputMethodInfo
        } catch (_: Exception) {
            null
        } ?: return false
        return current.component == ComponentName(context, PrivateImeService::class.java)
    }

    /** Opens the system screen where the user enables this keyboard. */
    fun openEnableScreen(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        } catch (_: Exception) {
            // Some OEMs hide this screen; fall back to the general settings root.
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            )
        }
    }

    /** Opens the system keyboard picker so the user can choose this IME. */
    fun openPicker(context: Context) {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        try {
            manager?.showInputMethodPicker()
        } catch (_: Exception) {
            openEnableScreen(context)
        }
    }
}
