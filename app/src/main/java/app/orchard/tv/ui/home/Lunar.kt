package app.orchard.tv.ui.home

import java.time.LocalDate

/**
 * The Chinese calendar's date, for the large clock when the TV speaks Chinese: "九月初五", as the
 * iPhone's lock screen shows it beside the date. A table rather than android.icu's ChineseCalendar,
 * which only came in Android 7 (the Xiaomi MiTV4 sets run Android 6).
 */
object Lunar {
    private const val FIRST_YEAR = 1901

    private val GAN = listOf("甲","乙","丙","丁","戊","己","庚","辛","壬","癸")
    private val ZHI = listOf("子","丑","寅","卯","辰","巳","午","未","申","酉","戌","亥")
    private val ANIMAL = listOf("鼠","牛","虎","兔","龙","蛇","马","羊","猴","鸡","狗","猪")

    /** 天干地支生肖年, 如 "丙午马年" (1900年为庚子年基准) */
    fun yearText(date: LocalDate): String? {
        var i = date.year - 1900
        if (i < 0 || i >= 200) return null
        val newYear = newYear(i - 1)
        if (date.isBefore(newYear)) i--
        if (i < 0) return null
        val zhiIdx = i % 12
        return GAN[(i + 6) % 10] + ZHI[zhiIdx] + ANIMAL[zhiIdx] + "年"
    }

    /**
     * One lunar year per Gregorian year it begins in, from 1901 to 2100, generated from macOS's
     * Chinese calendar and checked against it day by day (LunarTest). Bits 0-3: after which month
     * the leap month comes, 0 for none; bits 4-16: whether each month in turn (the leap one in its
     * place) has 30 days; bits 17-21 and 22-23: the day and month (0 = January) of the new year.
     */
    private val YEARS = intArrayOf(
        0x667520, 0x50ea50, 0x3b64a5, 0x6064b0, 0x48a9b0, 0x335564, 0x5a56a0, 0x44b590, 0x2d7522, 0x547520,
        0x3db256, 0x64b250, 0x4ca4b0, 0x352ab5, 0x5caad0, 0x4856a0, 0x2eb692, 0x56da90, 0x43d927, 0x68d920,
        0x50d250, 0x39a4d5, 0x60a560, 0x4a2b60, 0x315b54, 0x5a6d40, 0x44ea90, 0x2fe922, 0x54e920, 0x3cd266,
        0x6252b0, 0x4ca570, 0x352b65, 0x5cb5a0, 0x486d40, 0x30ec93, 0x567490, 0x3f6937, 0x66a930, 0x5052b0,
        0x36a5b6, 0x5eaad0, 0x4a56a0, 0x33b554, 0x5aba40, 0x44b490, 0x2da932, 0x54a950, 0x3b52d7, 0x625360,
        0x4caad0, 0x375aa5, 0x5c5b20, 0x46da50, 0x31d4a3, 0x58d4a0, 0x3ea958, 0x64a970, 0x505560, 0x38ab56,
        0x5ead50, 0x4a6d20, 0x32ea54, 0x5aea50, 0x4464a0, 0x2ac973, 0x52a9b0, 0x3d55a7, 0x6256a0, 0x4cb690,
        0x377525, 0x5eb520, 0x46b250, 0x2f64b4, 0x56a4b0, 0x3f4ab8, 0x642ad0, 0x4e56d0, 0x38b696, 0x60da90,
        0x4ad920, 0x33d254, 0x5ad250, 0x45a4da, 0x68a560, 0x522b60, 0x3a5b56, 0x626d50, 0x4cea90, 0x37e925,
        0x5ee920, 0x48d260, 0x2ea563, 0x54a570, 0x3f4d68, 0x6635a0, 0x4e6d50, 0x396c95, 0x607490, 0x4a6930,
        0x3152b4, 0x5852b0, 0x42a5b0, 0x2d55a2, 0x5256a0, 0x3bb557, 0x64ba40, 0x4eb490, 0x35a935, 0x5ca950,
        0x4652d0, 0x2eaad4, 0x54ab50, 0x3f5aa9, 0x665d20, 0x50da50, 0x39d4a6, 0x60d4a0, 0x4ac950, 0x3352e4,
        0x585560, 0x42ab50, 0x2d5b22, 0x546d20, 0x3aea56, 0x627250, 0x4c64b0, 0x34c975, 0x5acab0, 0x4655a0,
        0x2ead63, 0x56b690, 0x3f752b, 0x66b520, 0x50b250, 0x39a4b6, 0x5ea4b0, 0x484ab0, 0x3055b5, 0x585ad0,
        0x42b6a0, 0x2db522, 0x54d920, 0x3dd257, 0x62d250, 0x4ca550, 0x354ad5, 0x5c4b60, 0x445b50, 0x2edaa3,
        0x56ec90, 0x43e928, 0x66e920, 0x50d260, 0x38a566, 0x5ea570, 0x484d60, 0x306d54, 0x587550, 0x447490,
        0x2ae933, 0x526930, 0x3b52b7, 0x6252b0, 0x4aa5b0, 0x3555a5, 0x5c56a0, 0x46b650, 0x2f74a4, 0x56b4a0,
        0x3fa958, 0x66a950, 0x4e52d0, 0x36aad6, 0x5eab50, 0x4a5aa0, 0x30ba54, 0x58da50, 0x44d4a0, 0x2dc953,
        0x52c960, 0x3b94e7, 0x625560, 0x4cab50, 0x355b25, 0x5c6d20, 0x46ea50, 0x30e4a4, 0x5468b0, 0x3cc978,
        0x644ab0, 0x4e55b0, 0x36ad66, 0x5eb6a0, 0x4a7520, 0x337254, 0x58b250, 0x42a8b0, 0x2b49b2, 0x524ab0,
    )

    private val MONTHS = arrayOf("正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊")
    private val TENS = arrayOf("初", "十", "廿", "三")
    private val UNITS = arrayOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十")

    /** [date] in the Chinese calendar, as "九月初五" or "闰六月十二"; null outside 1901-2100. */
    fun text(date: LocalDate): String? {
        val (month, day, leap) = of(date) ?: return null
        val days = when (day) {
            10 -> "初十"
            20 -> "二十"
            30 -> "三十"
            else -> TENS[day / 10] + UNITS[day % 10 - 1]
        }
        return (if (leap) "闰" else "") + MONTHS[month - 1] + "月" + days
    }

    /** The month (1-12), day and whether the month is a leap one; null outside the table. */
    fun of(date: LocalDate): Triple<Int, Int, Boolean>? {
        var i = date.year - FIRST_YEAR
        if (i >= YEARS.size) return null
        // Before this year's new year, the date is still in the year that began last January or February.
        if (i >= 0 && date.isBefore(newYear(i))) i--
        if (i < 0) return null
        val info = YEARS[i]
        var left = (date.toEpochDay() - newYear(i).toEpochDay()).toInt()
        val leapAfter = info and 0xf
        val count = if (leapAfter == 0) 12 else 13
        for (k in 0 until count) {
            val len = if (info shr (4 + k) and 1 == 1) 30 else 29
            if (left < len) {
                val month = if (leapAfter == 0 || k < leapAfter) k + 1 else k
                return Triple(month, left + 1, leapAfter != 0 && k == leapAfter)
            }
            left -= len
        }
        return null
    }

    private fun newYear(i: Int): LocalDate {
        val info = YEARS[i]
        return LocalDate.of(FIRST_YEAR + i, (info shr 22 and 0x3) + 1, info shr 17 and 0x1f)
    }

    private val TERMS = listOf(
        Triple("小寒", 1, 5.4055), Triple("大寒", 1, 20.12),
        Triple("立春", 2, 3.87), Triple("雨水", 2, 18.73),
        Triple("惊蛰", 3, 5.63), Triple("春分", 3, 20.646),
        Triple("清明", 4, 4.81), Triple("谷雨", 4, 20.10),
        Triple("立夏", 5, 5.52), Triple("小满", 5, 21.04),
        Triple("芒种", 6, 5.678), Triple("夏至", 6, 21.37),
        Triple("小暑", 7, 7.108), Triple("大暑", 7, 22.83),
        Triple("立秋", 8, 7.50), Triple("处暑", 8, 23.13),
        Triple("白露", 9, 7.646), Triple("秋分", 9, 23.042),
        Triple("寒露", 10, 8.318), Triple("霜降", 10, 23.438),
        Triple("立冬", 11, 7.438), Triple("小雪", 11, 22.36),
        Triple("大雪", 12, 7.18), Triple("冬至", 12, 21.94),
    )

    /** 某年某节气的公历日期 (21 世纪 C 值公式); 越界返回 null。 */
    private fun termDate(year: Int, name: String): LocalDate? {
        val t = TERMS.firstOrNull { it.first == name } ?: return null
        val y = year % 100
        val leap = ((y - 1) / 4.0).toInt()
        val day = Math.floor(y * 0.2422 + t.third).toInt() - leap
        return runCatching { LocalDate.of(year, t.second, day) }.getOrNull()
    }

    /** 二十四节气, 返回当天节气名, 否则 null */
    fun solarTerm(date: LocalDate): String? =
        TERMS.firstOrNull { termDate(date.year, it.first) == date }?.first

    /**
     * 当前所处节气: 最近一个已经到来的节气, 从它当天起一直到下一个节气前一天都返回它。
     * 元旦到小寒之前仍属上一年的冬至, 所以年初回落冬至 (不跨年计算整年, 二十四节气表本身已覆盖)。
     */
    fun currentTerm(date: LocalDate): String {
        val passed = TERMS
            .mapNotNull { t -> termDate(date.year, t.first)?.let { if (it <= date) it to t.first else null } }
            .maxByOrNull { it.first }
        return passed?.second ?: "冬至"
    }

    /** 儒略日数, 用于日干支推算。 */
    private fun jdn(date: LocalDate): Int {
        val a = (14 - date.monthValue) / 12
        val y = date.year + 4800 - a
        val m = date.monthValue + 12 * a - 3
        return date.dayOfMonth + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045
    }

    /** 日干序号: 甲0 … 庚6 … 癸9。 */
    private fun dayStem(date: LocalDate): Int = ((jdn(date) + 49) % 60).mod(10)

    /** 数九: 冬至当天入九, 每九天一九; 只在每一九的第一天返回名字, 避免整个九天里一直挂着变成常驻。 */
    private fun shuJiu(date: LocalDate): String? {
        var dongZhi = termDate(date.year, "冬至") ?: return null
        if (date.isBefore(dongZhi)) dongZhi = termDate(date.year - 1, "冬至") ?: return null
        val delta = (date.toEpochDay() - dongZhi.toEpochDay()).toInt() + 1
        if (delta !in 1..81) return null
        if ((delta - 1) % 9 != 0) return null
        val names = listOf("入九", "二九天", "三九天", "四九天", "五九天", "六九天", "七九天", "八九天", "九九天")
        return names[(delta - 1) / 9]
    }

    /** 三伏: 夏至后第三庚日入伏、第四庚日中伏, 立秋后首个庚日末伏; 只在入伏/中伏/末伏当天返回。 */
    private fun sanFu(date: LocalDate): String? {
        if (date.monthValue !in 6..8) return null
        val xiaZhi = termDate(date.year, "夏至") ?: return null
        val liQiu = termDate(date.year, "立秋") ?: return null
        val geng = mutableListOf<LocalDate>()
        var d = xiaZhi
        while (geng.size < 5 && d <= liQiu.plusDays(20)) {
            if (dayStem(d) == 6) geng += d
            d = d.plusDays(1)
        }
        if (geng.size < 4) return null
        val ruFu = geng[2]
        val zhongFu = geng[3]
        var moFu: LocalDate? = null
        var q = liQiu
        while (q <= liQiu.plusDays(20)) {
            if (dayStem(q) == 6) { moFu = q; break }
            q = q.plusDays(1)
        }
        val mo = moFu ?: return null
        return when (date) {
            ruFu -> "入伏"
            zhongFu -> "中伏"
            mo -> "末伏"
            else -> null
        }
    }

    /** 时令杂节气(数九、三伏), 与公历/农历节日分开。 */
    fun seasonal(date: LocalDate): List<String> = listOfNotNull(shuJiu(date), sanFu(date))

    /** 当天所有节日 (可能多个, 如中秋+国庆), 无则返回空列表 */
    fun festivals(date: LocalDate): List<String> {
        val out = mutableListOf<String>()
        // 清明既是节气也是传统节日; 丰收节定在秋分节气当天。
        if (solarTerm(date) == "清明") out += "清明节"
        if (solarTerm(date) == "秋分") out += "丰收节"

        val m = date.monthValue
        val d = date.dayOfMonth
        when (m) {
            1 -> when (d) {
                1 -> out += "元旦"
                10 -> out += "中国人民警察节"
            }
            2 -> if (d == 14) out += "情人节"
            3 -> when (d) {
                8 -> out += "妇女节"
                12 -> out += "植树节"
                15 -> out += "消费者权益日"
            }
            4 -> when (d) {
                1 -> out += "愚人节"
                22 -> out += "世界地球日"
                23 -> out += "世界读书日"
            }
            5 -> when (d) {
                1 -> out += "劳动节"
                4 -> out += "青年节"
                12 -> out += "护士节"
            }
            6 -> when (d) {
                1 -> out += "儿童节"
                5 -> out += "世界环境日"
                26 -> out += "国际禁毒日"
            }
            7 -> when (d) {
                1 -> { out += "建党节"; out += "香港回归纪念日" }
                7 -> out += "七·七事变纪念日"
            }
            8 -> when (d) {
                1 -> out += "建军节"
                15 -> out += "日本投降日"
                19 -> out += "中国医师节"
            }
            9 -> when (d) {
                3 -> out += "抗战胜利纪念日"
                10 -> out += "教师节"
                18 -> out += "九·一八事变纪念日"
                30 -> out += "烈士纪念日"
            }
            10 -> when (d) {
                1 -> out += "国庆节"
                10 -> out += "辛亥革命纪念日"
                25 -> { out += "台湾光复纪念日"; out += "抗美援朝纪念日" }
                31 -> out += "万圣夜"
            }
            11 -> when (d) {
                1 -> out += "万圣节"
                8 -> out += "记者节"
            }
            12 -> when (d) {
                13 -> out += "国家公祭日"
                20 -> out += "澳门回归纪念日"
                24 -> { out += "平安夜"; out += "长津湖战役胜利日" }
                25 -> out += "圣诞节"
            }
        }
        // 母亲节: 5 月第二个周日; 父亲节: 6 月第三个周日; 感恩节: 11 月第四个周四。
        if (m == 5 && date.dayOfWeek == java.time.DayOfWeek.SUNDAY) {
            val first = date.withDayOfMonth(1)
            val firstSunday = if (first.dayOfWeek == java.time.DayOfWeek.SUNDAY) 1
                else 8 - first.dayOfWeek.value
            if (d == firstSunday + 7) out += "母亲节"
        }
        if (m == 6 && date.dayOfWeek == java.time.DayOfWeek.SUNDAY) {
            val first = date.withDayOfMonth(1)
            val firstSunday = if (first.dayOfWeek == java.time.DayOfWeek.SUNDAY) 1
                else 8 - first.dayOfWeek.value
            if (d == firstSunday + 14) out += "父亲节"
        }
        if (m == 11 && date.dayOfWeek == java.time.DayOfWeek.THURSDAY && (d - 1) / 7 == 3) {
            out += "感恩节"
        }
        // 农历节日
        val lunar = of(date)
        if (lunar != null && !lunar.third) {
            val (lm, ld) = lunar
            when (lm) {
                1 -> when (ld) {
                    1 -> out += "春节"
                    7 -> out += "人日节"
                    15 -> out += "元宵节"
                }
                2 -> if (ld == 2) out += "龙抬头"
                3 -> if (ld == 3) out += "上巳节"
                5 -> if (ld == 5) out += "端午节"
                7 -> when (ld) {
                    7 -> out += "七夕节"
                    15 -> out += "中元节"
                }
                8 -> if (ld == 15) out += "中秋节"
                9 -> if (ld == 9) out += "重阳节"
                10 -> when (ld) {
                    1 -> out += "寒衣节"
                    15 -> out += "下元节"
                }
                12 -> when (ld) {
                    8 -> out += "腊八节"
                    16 -> out += "尾牙"
                    23 -> out += "北方小年"
                    24 -> out += "南方小年"
                    else -> {
                        val tm = of(date.plusDays(1))
                        if (tm != null && tm.first == 1 && tm.second == 1) out += "除夕"
                    }
                }
            }
        }
        return out
    }
}
