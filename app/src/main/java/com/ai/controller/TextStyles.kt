package com.ai.controller

import android.content.Context

/**
 * Text style transforms — Kotlin port of ai-controller's text_styles.py and
 * the mode dispatch in ptt_pynput.py's `_transform_text`. Same five modes,
 * same Unicode blocks, same semantics:
 *  - PRO returns the raw transcript untouched.
 *  - BUBBLY maps ASCII letters into Mathematical Bold Script (U+1D4D0).
 *  - CASUAL lowercases the text and appends an emoji.
 *  - BOLD maps ASCII letters into Mathematical Bold (U+1D400).
 *  - BIG maps ASCII letters into Mathematical Bold Fraktur (U+1D56C) — chosen
 *    over plain Fraktur (U+1D504) because that block has legacy gaps
 *    (missing C/H/I/R/Z, aliased into Letterlike Symbols); Bold Fraktur is a
 *    contiguous, fully populated 52-codepoint block.
 */
object TextStyles {

    enum class Mode { PRO, BUBBLY, CASUAL, BOLD, BIG }

    private const val CURSIVE_LOWER = "𝓪𝓫𝓬𝓭𝓮𝓯𝓰𝓱𝓲𝓳𝓴𝓵𝓶𝓷𝓸𝓹𝓺𝓻𝓼𝓽𝓾𝓿𝔀𝔁𝔂𝔃"
    private const val CURSIVE_UPPER = "𝓐𝓑𝓒𝓓𝓔𝓕𝓖𝓗𝓘𝓙𝓚𝓛𝓜𝓝𝓞𝓟𝓠𝓡𝓢𝓣𝓤𝓥𝓦𝓧𝓨𝓩"
    private const val BOLD_LOWER = "𝐚𝐛𝐜𝐝𝐞𝐟𝐠𝐡𝐢𝐣𝐤𝐥𝐦𝐧𝐨𝐩𝐪𝐫𝐬𝐭𝐮𝐯𝐰𝐱𝐲𝐳"
    private const val BOLD_UPPER = "𝐀𝐁𝐂𝐃𝐄𝐅𝐆𝐇𝐈𝐉𝐊𝐋𝐌𝐍𝐎𝐏𝐐𝐑𝐒𝐓𝐔𝐕𝐖𝐗𝐘𝐙"
    private const val FRAKTUR_LOWER = "𝖆𝖇𝖈𝖉𝖊𝖋𝖌𝖍𝖎𝖏𝖐𝖑𝖒𝖓𝖔𝖕𝖖𝖗𝖘𝖙𝖚𝖛𝖜𝖝𝖞𝖟"
    private const val FRAKTUR_UPPER = "𝕬𝕭𝕮𝕯𝕰𝕱𝕲𝕳𝕴𝕵𝕶𝕷𝕸𝕹𝕺𝕻𝕼𝕽𝕾𝕿𝖀𝖁𝖂𝖃𝖄𝖅"

    private fun buildMap(lower: String, upper: String): Map<Char, Char> {
        val map = HashMap<Char, Char>(52)
        for (i in 0 until 26) {
            map['a' + i] = lower[i]
            map['A' + i] = upper[i]
        }
        return map
    }

    private val cursiveMap = buildMap(CURSIVE_LOWER, CURSIVE_UPPER)
    private val boldMap = buildMap(BOLD_LOWER, BOLD_UPPER)
    private val frakturMap = buildMap(FRAKTUR_LOWER, FRAKTUR_UPPER)

    private fun mapChars(text: String, map: Map<Char, Char>): String =
        buildString(text.length) { for (ch in text) append(map[ch] ?: ch) }

    fun toCursive(text: String): String = mapChars(text, cursiveMap)
    fun toBold(text: String): String = mapChars(text, boldMap)
    fun toOldEnglish(text: String): String = mapChars(text, frakturMap)

    // Keyword -> emoji. Longer phrases are checked first so "thank you" beats "thanks",
    // matching ptt_pynput.py's _add_emojis. Trimmed from the desktop's much larger
    // dictionary to a representative cross-section of the same categories.
    private val emojiMap = linkedMapOf(
        "good morning" to "🌅", "good night" to "🌙", "thank you" to "🙏🏿",
        "thanks" to "🙏🏿", "please" to "🥺", "sorry" to "😔",
        "hello" to "👋🏿", "hi" to "👋🏿", "hey" to "👋🏿", "bye" to "👋🏿", "goodbye" to "👋🏿",
        "happy" to "😊", "sad" to "😢", "love" to "❤️", "angry" to "😠", "tired" to "😴",
        "lol" to "😂", "haha" to "😂", "wow" to "🤯", "omg" to "😱", "yay" to "🎉",
        "cool" to "😎", "nice" to "✨", "great" to "🎉", "awesome" to "🤩", "perfect" to "💯",
        "yes" to "✅", "no" to "❌", "maybe" to "🤷🏿", "done" to "✅", "check" to "✅",
        "coffee" to "☕", "pizza" to "🍕", "fire" to "🔥", "money" to "💰", "idea" to "💡",
        "phone" to "📱", "computer" to "💻", "game" to "🎮", "music" to "🎵", "book" to "📚",
        "sun" to "☀️", "moon" to "🌙", "star" to "⭐", "rain" to "🌧️",
        "cat" to "🐱", "dog" to "🐶", "party" to "🎉", "birthday" to "🎂"
    )

    private val casualEmojis = listOf("👋🏿", "☕", "😊", "✌🏿", "🙌🏿", "🤙🏿", "😎", "✨", "💯", "🔥", "🫡")

    private fun addKeywordEmoji(text: String): String {
        val lowered = text.lowercase()
        val phrase = emojiMap.keys.sortedByDescending { it.length }.firstOrNull { lowered.contains(it) }
            ?: return text
        return "$text ${emojiMap.getValue(phrase)}"
    }

    private fun casualEmojiBoost(text: String): String {
        if (emojiMap.values.any { text.endsWith(it) }) return text
        return "$text ${casualEmojis.random()}"
    }

    /** Applies [mode] to [text], matching ptt_pynput.py's `_transform_text` semantics. */
    fun transform(text: String, mode: Mode): String {
        if (mode == Mode.PRO) return text
        val withEmoji = addKeywordEmoji(text)
        return when (mode) {
            Mode.BUBBLY -> toCursive(withEmoji)
            Mode.CASUAL -> casualEmojiBoost(withEmoji.lowercase())
            Mode.BOLD -> toBold(withEmoji)
            Mode.BIG -> toOldEnglish(withEmoji)
            Mode.PRO -> text
        }
    }

    fun parseMode(raw: String?): Mode = when (raw?.lowercase()) {
        "bubbly" -> Mode.BUBBLY
        "casual" -> Mode.CASUAL
        "bold" -> Mode.BOLD
        "big" -> Mode.BIG
        else -> Mode.PRO
    }

    /** Cycle order used by KeyboardActivity's single mode-toggle button. */
    fun next(mode: Mode): Mode {
        val order = Mode.values()
        return order[(order.indexOf(mode) + 1) % order.size]
    }
}

/**
 * Persists the active [TextStyles.Mode] — the Android analogue of
 * ptt_pynput.py's MODE_FILE, shared between KeyboardActivity (which sets it)
 * and ControllerAccessibilityService (which applies it to voice transcripts).
 */
object PttModeStore {
    private const val PREFS_NAME = "ai_controller_ptt_mode"
    private const val KEY_MODE = "mode"

    fun load(context: Context): TextStyles.Mode =
        TextStyles.parseMode(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_MODE, null)
        )

    fun save(context: Context, mode: TextStyles.Mode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name.lowercase())
            .apply()
    }
}
