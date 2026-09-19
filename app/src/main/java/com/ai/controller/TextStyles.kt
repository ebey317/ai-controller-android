package com.ai.controller

import android.content.Context
import java.io.File

/**
 * Selectable emoji skin tone (Fitzpatrick scale) — every customer picks their
 * own in Settings. Neutral renders the base emoji with no modifier; the rest
 * append the matching U+1F3Fx modifier to every hand/person emoji, exactly
 * once per emoji. Default is Dark — the Linux build's signature look.
 */
enum class EmojiSkinTone(val modifier: String?, val label: String) {
    NEUTRAL(null, "✌️  Neutral"),
    LIGHT("🏻", "✌🏻  Light"),
    MEDIUM_LIGHT("🏼", "✌🏼  Medium-light"),
    MEDIUM("🏽", "✌🏽  Medium"),
    MEDIUM_DARK("🏾", "✌🏾  Medium-dark"),
    DARK("🏿", "✌🏿  Dark (default)");
}

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
 *
 * Emoji pipeline ported from ptt_pynput.py: keyword-emoji insertion (the full
 * Linux keyword map, longest-phrase-first), the casual emoji boost, and the
 * customer-selected skin tone applied at table-build time.
 */
object TextStyles {

    enum class Mode { PRO, BUBBLY, CASUAL, BOLD, BIG }

    private const val CURSIVE_LOWER = "𝓪𝓫𝓬𝓭𝓮𝓯𝓰𝓱𝓲𝓳𝓴𝓵𝓶𝓷𝓸𝓹𝓺𝓻𝓼𝓽𝓾𝓿𝔀𝔁𝔂𝔃"
    private const val CURSIVE_UPPER = "𝓐𝓑𝓒𝓓𝓔𝓕𝓖𝓗𝓘𝓙𝓚𝓛𝓜𝓝𝓞𝓟𝓠𝓡𝓢𝓣𝓤𝓥𝓦𝓧𝓨𝓩"
    private const val BOLD_LOWER = "𝐚𝐛𝐜𝐝𝐞𝐟𝐠𝐡𝐢𝐣𝐤𝐥𝐦𝐧𝐨𝐩𝐪𝐫𝐬𝐭𝐮𝐯𝐰𝐱𝐲𝐳"
    private const val BOLD_UPPER = "𝐀𝐁𝐂𝐃𝐄𝐅𝐆𝐇𝐈𝐉𝐊𝐋𝐌𝐍𝐎𝐏𝐐𝐑𝐒𝐓𝐔𝐕𝐖𝐗𝐘𝐙"
    private const val FRAKTUR_LOWER = "𝖆𝖇𝖈𝖉𝖊𝖋𝖌𝖍𝖎𝖏𝖐𝖑𝖒𝖓𝖔𝖕𝖖𝖗𝖘𝖙𝖚𝖛𝖜𝖝𝖞𝖟"
    private const val FRAKTUR_UPPER = "𝕬𝕭𝕮𝕯𝕰𝕱𝕲𝕳𝕴𝕵𝕶𝕷𝕸𝕹𝕺𝕻𝕼𝕽𝕾𝕿𝖀𝖁𝖂𝖃𝖄𝖅"

    private fun buildMap(lower: String, upper: String): Map<Char, String> {
        val map = HashMap<Char, String>(52)
        for (i in 0 until 26) {
            map['a' + i] = lower.substring(i * 2, i * 2 + 2)
            map['A' + i] = upper.substring(i * 2, i * 2 + 2)
        }
        return map
    }

    private val cursiveMap = buildMap(CURSIVE_LOWER, CURSIVE_UPPER)
    private val boldMap = buildMap(BOLD_LOWER, BOLD_UPPER)
    private val frakturMap = buildMap(FRAKTUR_LOWER, FRAKTUR_UPPER)

    private fun mapChars(text: String, map: Map<Char, String>): String =
        buildString(text.length * 2) {
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                if (ch.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                    append(text, i, i + 2) // astral glyph (or styled pair) passes through whole
                    i += 2
                } else {
                    append(map[ch] ?: ch.toString())
                    i++
                }
            }
        }

    fun toCursive(text: String): String = mapChars(text, cursiveMap)
    fun toBold(text: String): String = mapChars(text, boldMap)
    fun toOldEnglish(text: String): String = mapChars(text, frakturMap)

    // ---- selectable skin tone (Fitzpatrick scale) ----

    @Volatile private var skinTone: EmojiSkinTone = EmojiSkinTone.DARK

    fun currentSkinTone(): EmojiSkinTone = skinTone

    /** Apply the customer's tone and rebuild the emoji tables. Safe to call
     *  from any thread; table references are swapped atomically. */
    fun setSkinTone(tone: EmojiSkinTone) {
        skinTone = tone
        emojiMap = buildEmojiMap()
        casualEmojis = buildCasualEmojis()
    }

    // ptt_pynput.py's _TONEABLE_BASES: emoji codepoints that accept a modifier.
    private val toneableBases = setOf(
        "👋", "✌", "🙌", "🤙", "🙏", "🙇", "👍", "👎", "👌", "🤷",
    )

    private fun applySkinTone(emoji: String): String {
        val modifier = skinTone.modifier ?: return emoji
        return buildString(emoji.length + 2) {
            var i = 0
            while (i < emoji.length) {
                val ch = emoji[i]
                val isPair = ch.isHighSurrogate() && i + 1 < emoji.length && emoji[i + 1].isLowSurrogate()
                val cp = if (isPair) emoji.substring(i, i + 2) else ch.toString()
                if (cp in toneableBases) {
                    append(cp).append(skinTone.modifier)
                    i += if (isPair) 2 else 1
                    if (i < emoji.length && emoji[i] == '️') { // U+FE0F variation selector
                        append(emoji[i]); i++
                    }
                    continue
                }
                append(cp)
                i += if (isPair) 2 else 1
            }
        }
    }

    // Declaration order preserved from ptt_pynput.py's _EMOJI_MAP; longest-
    // phrase-first matching means equal-length ties keep this order (Python's
    // stable sort), so keep entries in the file's original sequence.
    private val rawEmojiEntries: List<Pair<String, String>> = listOf(
        // emotions
        "happy" to "happy 😊", "sad" to "sad 😢", "love" to "love ❤️", "hate" to "hate 😠",
        "heart" to "heart ❤️", "excited" to "excited 🤩", "bored" to "bored 😐",
        "angry" to "angry 😠", "mad" to "mad 🤬", "tired" to "tired 😴", "sleepy" to "sleepy 😴",
        "sick" to "sick 🤒", "surprised" to "surprised 😲", "shocked" to "shocked 😱",
        "confused" to "confused 😕", "worried" to "worried 😟", "proud" to "proud 🥹",
        "embarrassed" to "embarrassed 😳", "scared" to "scared 😨", "lonely" to "lonely 🥺",
        // reactions
        "lol" to "lol 😂", "haha" to "haha 😂", "lmao" to "lmao 🤣", "wow" to "wow 🤯",
        "omg" to "omg 😱", "yay" to "yay 🎉", "woo" to "woo 🥳", "yikes" to "yikes 😬",
        "ugh" to "ugh 😩", "meh" to "meh 😒", "hm" to "hm 🤔", "hmm" to "hmm 🤔",
        // greetings / goodbyes (hand emojis are tone-free bases; the selected
        // modifier is applied at table-build time)
        "hello" to "hello 👋", "hi" to "hi 👋", "hey" to "hey 👋",
        "goodbye" to "goodbye 👋", "bye" to "bye 👋", "see you" to "see you 👋",
        "good morning" to "good morning 🌅", "good night" to "good night 🌙",
        "thank you" to "thank you 🙏", "thanks" to "thanks 🙏", "please" to "please 🥺",
        "sorry" to "sorry 😔", "apologize" to "apologize 🙇",
        // quality
        "fire" to "fire 🔥", "cool" to "cool 😎", "nice" to "nice ✨", "great" to "great 🎉",
        "awesome" to "awesome 🤩", "amazing" to "amazing 🤩", "perfect" to "perfect 💯",
        "good" to "good 👍", "bad" to "bad 👎", "ok" to "ok 👌", "okay" to "okay 👌",
        "yes" to "yes ✅", "no" to "no ❌", "maybe" to "maybe 🤷", "definitely" to "definitely 💯",
        "check" to "check ✅", "done" to "done ✅", "finished" to "finished ✅",
        // food / drink
        "hungry" to "hungry 🍔", "coffee" to "coffee ☕", "beer" to "beer 🍺", "wine" to "wine 🍷",
        "pizza" to "pizza 🍕", "taco" to "taco 🌮", "burger" to "burger 🍔", "fries" to "fries 🍟",
        "cake" to "cake 🍰", "ice cream" to "ice cream 🍦", "chocolate" to "chocolate 🍫",
        "water" to "water 💧", "tea" to "tea 🍵", "breakfast" to "breakfast 🍳", "dinner" to "dinner 🍽️",
        // objects / tech
        "phone" to "phone 📱", "computer" to "computer 💻", "laptop" to "laptop 💻",
        "game" to "game 🎮", "controller" to "controller 🎮", "music" to "music 🎵",
        "book" to "book 📚", "movie" to "movie 🎬", "tv" to "tv 📺", "money" to "money 💰",
        "idea" to "idea 💡", "light" to "light 💡", "warning" to "warning ⚠️", "rocket" to "rocket 🚀",
        "time" to "time ⏰", "date" to "date 📅", "mail" to "mail 📧", "email" to "email 📧",
        // nature / animals
        "sun" to "sun ☀️", "moon" to "moon 🌙", "star" to "star ⭐", "rain" to "rain 🌧️",
        "snow" to "snow ❄️", "ghost" to "ghost 👻", "skull" to "skull 💀",
        "cat" to "cat 🐱", "dog" to "dog 🐶", "bird" to "bird 🐦", "fish" to "fish 🐟",
        // events
        "party" to "party 🎉", "birthday" to "birthday 🎂", "congratulations" to "congratulations 🎉",
        "weekend" to "weekend 🎉", "work" to "work 💼", "job" to "job 💼",
    )

    @Volatile private var emojiMap: Map<String, String> = buildEmojiMap()
    @Volatile private var casualEmojis: List<String> = buildCasualEmojis()

    private fun buildEmojiMap(): Map<String, String> =
        rawEmojiEntries.associate { (k, v) -> k to applySkinTone(v) }

    private fun buildCasualEmojis(): List<String> =
        listOf("👋", "☕", "😊", "✌️", "🙌", "🤙", "😎", "✨", "💯", "🔥", "🫡")
            .map { applySkinTone(it) }

    private fun addKeywordEmoji(text: String): String {
        val lowered = text.lowercase()
        val phrase = rawEmojiEntries.map { it.first }
            .sortedByDescending { it.length }  // stable sort: ties keep declaration order
            .firstOrNull { lowered.contains(it) }
            ?: return text
        return "$text ${emojiMap.getValue(phrase).substring(phrase.length).trim()}"
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

/**
 * Persists the customer's emoji skin tone. Default Dark — the Linux build's
 * signature look; every customer can switch in Settings at any time.
 */
object SkinToneStore {
    private const val PREFS_NAME = "ai_controller_skin_tone"
    private const val KEY_TONE = "tone"

    fun load(context: Context): EmojiSkinTone =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TONE, null)
            ?.let { raw ->
                EmojiSkinTone.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            }
            ?: EmojiSkinTone.DARK

    fun save(context: Context, tone: EmojiSkinTone) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TONE, tone.name)
            .apply()
    }
}

/**
 * User-adjustable keyboard text size. Reported live 2026-09-19: a fixed size ("this one
 * needs an increased font size") isn't the fix — "we need to be able to allow the
 * consumer to choose the size that they want... it doesn't need to be predetermined."
 * Persisted like the other keyboard prefs (mode, skin tone) so it survives show/hide and
 * app restarts. [STEPS_SP] are the discrete sizes the on-screen +/- cycles through; [next]
 * saturates at the ends rather than wrapping, since "keep pressing +" landing back at tiny
 * text would feel broken.
 */
object KeyboardFontSizeStore {
    private const val PREFS_NAME = "ai_controller_keyboard"
    private const val KEY_SIZE_SP = "font_size_sp"
    const val DEFAULT_SP = 18f
    val STEPS_SP = listOf(14f, 18f, 22f, 26f, 30f, 34f)

    fun load(context: Context): Float =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat(KEY_SIZE_SP, DEFAULT_SP)

    fun save(context: Context, sizeSp: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_SIZE_SP, sizeSp)
            .apply()
    }

    fun larger(current: Float): Float = STEPS_SP.lastOrNull { it > current } ?: STEPS_SP.max()
    fun smaller(current: Float): Float = STEPS_SP.firstOrNull { it < current } ?: STEPS_SP.min()
}