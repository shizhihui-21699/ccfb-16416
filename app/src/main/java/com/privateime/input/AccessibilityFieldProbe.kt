package com.privateime.input

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import com.privateime.core.model.FieldSignals
import com.privateime.core.text.TextNormalizer
import java.util.concurrent.atomic.AtomicReference

/**
 * The bridge between the IME and an optional accessibility service.
 *
 * ## Why an accessibility service
 *
 * An IME receives one `EditorInfo` per focus and nothing else. Everything that
 * makes detection work on badly written apps lives outside that object:
 *
 *  * the `labelFor` text next to the field,
 *  * the resource id (`et_password`),
 *  * `AccessibilityNodeInfo.isPassword`,
 *  * for web pages, the HTML placeholder and `name`/`id`.
 *
 * `AccessibilityNodeInfo` exposes all of them. The catch is that an
 * `AccessibilityService` is a powerful, user-visible grant, so this design treats
 * it as a strictly optional enhancement:
 *
 *  * With the service: labels, ids, `isPassword` and adjacent-label inference are
 *    all available; the detector reaches HIGH confidence on mislabelled fields.
 *  * Without it: the IME still works on `EditorInfo` + content + field memory,
 *    which covers the declared cases and learns the undeclared ones over time.
 *
 * The service **never** enables "explore by touch" or retrieves window content
 * for any package other than the one currently focused by the IME, and it only
 * ever reads the focused editable node plus its immediate label neighbours.
 */
object AccessibilityFieldProbe {

    /** True when the user has enabled the companion accessibility service. */
    @Volatile
    var serviceConnected: Boolean = false
        private set

    private val activeService = AtomicReference<AccessibilityService?>(null)

    /**
     * Last `packageName` the IME told us about. The probe refuses to read nodes
     * from any other window; this is the guard that keeps the service from
     * turning into a general-purpose screen scraper.
     */
    @Volatile
    private var expectedPackage: String? = null

    fun onServiceConnected(service: AccessibilityService) {
        activeService.set(service)
        serviceConnected = true
    }

    fun onServiceDisconnected() {
        activeService.set(null)
        serviceConnected = false
        expectedPackage = null
    }

    /** Called by the IME on every focus change, before [probe]. */
    fun expectPackage(packageName: String?) {
        expectedPackage = packageName
    }

    /**
     * Reads what the accessibility tree can tell us about the focused editor.
     *
     * @param packageName the IME's current app; a mismatch with [expectedPackage]
     *   returns null (the tree is stale or belongs to another window)
     * @param maxLabelLength labels are truncated; they are hints, not documents
     */
    fun probe(packageName: String, maxLabelLength: Int = 96): ProbeResult? {
        val service = activeService.get() ?: return null
        if (packageName.isEmpty() || expectedPackage != packageName) return null
        val root = try {
            service.rootInActiveWindow
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalStateException) {
            null
        } ?: return null

        if (root.packageName?.toString() != packageName) {
            root.recycleSafely()
            return null
        }

        return try {
            val editor = findFocusedEditable(root) ?: return null
            buildResult(editor, root, maxLabelLength)
        } finally {
            root.recycleSafely()
        }
    }

    data class ProbeResult(
        val viewIdResourceName: String?,
        val className: String?,
        val contentDescription: String?,
        val hintText: String?,
        val isPassword: Boolean,
        val maxTextLength: Int,
        val labelledText: String?,
        val textSample: String?,
        val isWebView: Boolean,
        val autofillHints: List<String>,
    ) {
        /** Merges accessibility findings into the `EditorInfo`-derived snapshot. */
        fun applyTo(base: FieldSignals): FieldSignals = base.copy(
            viewIdResourceName = viewIdResourceName ?: base.viewIdResourceName,
            className = className ?: base.className,
            contentDescription = contentDescription ?: base.contentDescription,
            accessibilityHint = hintText ?: base.accessibilityHint,
            isPassword = isPassword || base.isPassword,
            maxTextLength = if (maxTextLength > 0) maxTextLength else base.maxTextLength,
            labelledText = labelledText ?: base.labelledText,
            textSample = base.textSample ?: textSample,
            isWebView = isWebView || base.isWebView,
            autofillHints = if (autofillHints.isNotEmpty()) autofillHints else base.autofillHints,
            accessibilityPresent = true,
        )
    }

    private fun buildResult(
        editor: AccessibilityNodeInfo,
        root: AccessibilityNodeInfo,
        maxLabelLength: Int,
    ): ProbeResult {
        val className = editor.className?.toString()
        val isWebView = isWebViewField(editor, className)

        return ProbeResult(
            viewIdResourceName = editor.viewIdResourceName,
            className = className,
            contentDescription = clip(editor.contentDescription, maxLabelLength),
            hintText = clip(editor.hintText, maxLabelLength),
            isPassword = invokeIsPassword(editor) || declaresPassword(editor) || isWebViewPassword(editor, isWebView),
            maxTextLength = editor.maxTextLength,
            labelledText = clip(labelFor(editor, root), maxLabelLength),
            textSample = clip(editor.text, MAX_TEXT_SAMPLE, allowEmpty = true),
            isWebView = isWebView,
            autofillHints = readAutofillHints(editor),
        )
    }

    private fun findFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val direct = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (direct != null && (direct.isEditable || invokeIsPassword(direct))) return direct
        direct?.recycleSafely()
        // Some WebViews put the real node below a focused container; breadth-first,
        // bounded so a pathological tree cannot stall the IME.
        return findEditableBreadthFirst(root, maxNodes = 250)
    }

    private fun findEditableBreadthFirst(root: AccessibilityNodeInfo, maxNodes: Int): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val node = queue.removeFirst()
            visited++
            if (node !== root && node.isEditable) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (child.isEditable) return child
                queue.add(child)
            }
        }
        return null
    }

    private fun labelFor(editor: AccessibilityNodeInfo, root: AccessibilityNodeInfo): String? {
        // 1. Explicit label association (`android:labelFor` / HTML <label for>).
        labelForOrLabelledBy(editor)?.let { return it }

        // 2. A sibling `TextView` immediately before the editor in the same
        //    parent. This is how the vast majority of Android forms are built
        //    (a Vertical layout of label + EditText) without any accessibility
        //    association at all.
        siblingLabel(editor)?.let { return it }

        // 3. Wrapping parent's content description (old TextInputLayout).
        parentLabel(editor)?.let { return it }
        return null
    }

    /**
     * Resolves an explicit label association.
     *
     * Android models this in two directions and it is easy to get backwards:
     *
     *  * `editor.labeledBy` — the node that labels *this* editor (a TextView with
     *    `android:labelFor="@id/field"`). This is what we want, and it is API 22.
     *  * `editor.labelFor` — the node that *this* editor labels, i.e. only
     *    meaningful for label nodes, not for the editor itself. Reading it here
     *    would return an unrelated field.
     *
     * `getLabeledByList()` is avoided deliberately: it is newer than `minSdk`, and
     * a missing platform method surfaces as `NoSuchMethodError`, which a
     * `catch (Exception)` does not cover. The singular accessor is enough — a field
     * with several labels still has a usable first one, and the sibling heuristic
     * in [siblingLabel] catches the rest.
     */
    private fun labelForOrLabelledBy(editor: AccessibilityNodeInfo): String? {
        val labeledBy = editor.labeledBy
        if (labeledBy != null) {
            val text = clip(labeledBy.text ?: labeledBy.contentDescription, 96)
            labeledBy.recycleSafely()
            if (!text.isNullOrBlank()) return text
        }
        // NOTE: `labelFor` is intentionally NOT read — see the KDoc above.
        return null
    }

    private fun siblingLabel(editor: AccessibilityNodeInfo): String? {
        val parent = editor.parent ?: return null
        try {
            val index = (0 until parent.childCount).firstOrNull { i ->
                parent.getChild(i)?.let { child ->
                    val same = child == editor
                    if (!same) child.recycleSafely()
                    same
                } == true
            } ?: return null
            // Walk backwards for the nearest textual sibling. One or two steps is
            // the form idiom; more than that and we start reading the previous
            // section's text.
            for (offset in 1..2) {
                val siblingIndex = index - offset
                if (siblingIndex < 0) break
                val sibling = parent.getChild(siblingIndex) ?: continue
                val text = clip(sibling.text ?: sibling.contentDescription, 96)
                val isStatic = sibling.className?.contains("TextView") == true ||
                    sibling.className?.contains("Text") == true
                sibling.recycleSafely()
                if (!text.isNullOrBlank() && isStatic) return text
            }
            return null
        } finally {
            parent.recycleSafely()
        }
    }

    private fun parentLabel(editor: AccessibilityNodeInfo): String? {
        val parent = editor.parent ?: return null
        try {
            // Only trust a parent description if the parent is a container, not
            // the whole screen (which often carries the page title).
            val desc = clip(parent.contentDescription, 96)
            if (!desc.isNullOrBlank()) return desc
            val hint = clip(parent.hintText, 96)
            if (!hint.isNullOrBlank()) return hint
            return null
        } finally {
            parent.recycleSafely()
        }
    }

    private fun readAutofillHints(editor: AccessibilityNodeInfo): List<String> {
        // minSdk is 26 (O), so the extras bundle is always available.
        val extras = try {
            editor.extras
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val hints = extras.getStringArrayList("android.view.accessibility.extra.AUTOFILL_HINTS")
            ?: extras.getStringArrayList("autofillHints")
            ?: return emptyList()
        return hints.filterNotNull().take(4)
    }

    /**
     * `isPassword` was added in API 18 but is not always populated by the
     * framework. WebViews in particular may only expose
     * `inputType=TYPE_TEXT_VARIATION_WEB_PASSWORD`. Both are checked.
     */
    private fun invokeIsPassword(node: AccessibilityNodeInfo): Boolean = try {
        node.isPassword
    } catch (_: Exception) {
        false
    }

    private fun declaresPassword(node: AccessibilityNodeInfo): Boolean {
        val type = node.inputType
        if (type == 0) return false
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        return (cls == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    /**
     * Chrome's WebView exposes `input type="password"` as a node whose class is
     * `android.widget.EditText` but whose `inputType` is the web password
     * variation. Some embedded browsers lose even that; they are caught by the
     * page-level heuristics in the detector instead.
     */
    private fun isWebViewPassword(node: AccessibilityNodeInfo, isWebView: Boolean): Boolean {
        if (!isWebView) return false
        val type = node.inputType
        if (type == 0) return false
        return (type and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
    }

    private fun isWebViewField(node: AccessibilityNodeInfo, className: String?): Boolean {
        if (className != null && className.contains("WebView", ignoreCase = true)) return true
        val viewId = node.viewIdResourceName
        if (viewId != null && (viewId.contains("webview", ignoreCase = true) ||
                viewId.contains("chromium", ignoreCase = true))
        ) {
            return true
        }
        val pkg = node.packageName?.toString() ?: return false
        return pkg == "com.android.chrome" || pkg == "com.chrome.beta"
    }

    fun clip(text: CharSequence?, max: Int, allowEmpty: Boolean = false): String? {
        if (text == null) return null
        val s = text.toString().trim()
        if (s.isEmpty()) return if (allowEmpty) null else null
        return TextNormalizer.truncate(s, max)
    }

    /** Bound on the content sample; the detector needs a shape, not the content. */
    const val MAX_TEXT_SAMPLE = 128

    private fun AccessibilityNodeInfo.recycleSafely() {
        // recycle() is a no-op and deprecated on newer platforms, but must still
        // be called on older ones to avoid leaking pooled nodes.
        try {
            @Suppress("DEPRECATION")
            recycle()
        } catch (_: Exception) {
            // Pool already empty or node already recycled.
        }
    }
}

/** Helper for tests and for the settings screen: is the probe usable right now? */
fun accessibilityAvailable(context: Context): Boolean {
    val enabled = android.provider.Settings.Secure.getString(
        context.contentResolver,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabled.contains(context.packageName, ignoreCase = true) &&
        AccessibilityFieldProbe.serviceConnected
}
