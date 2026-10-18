package com.ai.controller

/**
 * Shared per-key style logic, extracted verbatim from FloatingKeyboardOverlay and
 * keyboard/AIInputMethodService (which duplicated it) so it is defined and tested once.
 * Behavior unchanged: each PTT text mode maps a key label to the string that gets
 * committed, and each mode has its display label for the style row.
 */
internal fun styleChar(ch: String, mode: TextStyles.Mode): String = when (mode) {
    TextStyles.Mode.BUBBLY -> TextStyles.toCursive(ch)
    TextStyles.Mode.BOLD -> TextStyles.toBold(ch)
    TextStyles.Mode.BIG -> TextStyles.toOldEnglish(ch)
    TextStyles.Mode.CASUAL -> ch.lowercase()
    TextStyles.Mode.PRO -> ch
}

internal fun styleLabel(mode: TextStyles.Mode): String = when (mode) {
    TextStyles.Mode.PRO -> "PRO"
    TextStyles.Mode.BUBBLY -> "\u2728 " + TextStyles.toCursive("Cursive")
    TextStyles.Mode.CASUAL -> "\u2615 casual"
    TextStyles.Mode.BOLD -> TextStyles.toBold("Bold")
    TextStyles.Mode.BIG -> TextStyles.toOldEnglish("Old-E")
}
