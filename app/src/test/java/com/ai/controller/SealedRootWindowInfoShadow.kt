package com.ai.controller

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Test-only shadow for AccessibilityWindowInfo.
 *
 * Robolectric's built-in ShadowAccessibilityWindowInfo.getRoot() returns
 * `AccessibilityNodeInfo.obtain(rootNode)` — a copy that is always UNSEALED
 * (its getClone() does not preserve mSealed). Production code calls
 * findFocus()/getBoundsInScreen() on those roots, which throw
 * IllegalStateException on unsealed instances, making the window-discovery
 * paths untestable with the stock shadow.
 *
 * This shadow returns a fresh, sealed node per getRoot() call from a
 * per-window factory registered by the test. Fresh-per-call matters because
 * production recycles the nodes it doesn't keep.
 */
@Implements(AccessibilityWindowInfo::class)
class SealedRootWindowInfoShadow : org.robolectric.shadows.ShadowAccessibilityWindowInfo() {

    @Implementation
    override fun getRoot(): AccessibilityNodeInfo? =
        factories[this]?.invoke() ?: super.getRoot()

    companion object {
        private val factories = mutableMapOf<SealedRootWindowInfoShadow, () -> AccessibilityNodeInfo>()

        fun setFactory(shadow: SealedRootWindowInfoShadow, factory: () -> AccessibilityNodeInfo) {
            factories[shadow] = factory
        }

        fun clearFactories() {
            factories.clear()
        }
    }
}

/** A node as the system would hand one over: sealed, with basic properties set. */
internal fun sealedTestRoot(
    packageName: String,
    editable: Boolean = false,
    onSetText: ((String) -> Unit)? = null
): AccessibilityNodeInfo {
    val root = AccessibilityNodeInfo.obtain()
    root.packageName = packageName
    root.isVisibleToUser = true
    if (editable) root.isEditable = true
    if (onSetText != null) {
        val shadow = org.robolectric.Shadows.shadowOf(root)
        shadow.setOnPerformActionListener(
            org.robolectric.shadows.ShadowAccessibilityNodeInfo.OnPerformActionListener { action, args ->
                if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                    val t = args?.getCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE
                    )?.toString().orEmpty()
                    onSetText(t)
                    root.text = t
                    true
                } else {
                    false
                }
            }
        )
    }
    root.javaClass.getDeclaredField("mSealed").apply { isAccessible = true }
        .setBoolean(root, true)
    return root
}
