package app.orchard.tv.ui.home

/** 一个汉字的收缩时长: 0.2 秒。 */
private const val MS_PER_HAN_CHAR = 200

/** 最短收缩时长: 纯数字或空白也要走完一段动画, 不能瞬间消失。 */
private const val MIN_SHRINK_MS = 400

/** 最长收缩时长: 名字再长也不拖过 3 秒。 */
private const val MAX_SHRINK_MS = 3000

/**
 * 胶囊从右往左收缩(或从右边展开)的时长: 按汉字个数算, 一个汉字 0.2 秒, 8 个汉字就是 1.6 秒。
 * 汉字以外的字符(数字、顿号、空格、字母)不计时 —— 它们本来就窄, 不该跟着拉长动画。
 */
fun capsuleShrinkMs(text: String): Int {
    var han = 0
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        if (isHan(cp)) han++
        i += Character.charCount(cp)
    }
    return (han * MS_PER_HAN_CHAR).coerceIn(MIN_SHRINK_MS, MAX_SHRINK_MS)
}

/** 汉字区按码点手工判: UnicodeScript 要 API 24, 这个 App 最低 Android 5.0。 */
private fun isHan(cp: Int): Boolean = when (cp) {
    in 0x4E00..0x9FFF,     // 基本区
    in 0x3400..0x4DBF,     // 扩展 A
    in 0xF900..0xFAFF,     // 兼容汉字
    in 0x20000..0x2FA1F,   // 扩展 B 及以后 + 兼容补充
    in 0x30000..0x323AF -> true // 扩展 G/H
    else -> false
}
