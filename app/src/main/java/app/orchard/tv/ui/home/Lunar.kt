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

    /** 天干地支年, 如 "丙午年" (1900年为庚子年基准) */
    fun yearText(date: LocalDate): String? {
        var i = date.year - 1900
        if (i < 0 || i >= 200) return null
        val newYear = newYear(i - 1)
        if (date.isBefore(newYear)) i--
        if (i < 0) return null
        return GAN[(i + 6) % 10] + ZHI[i % 12] + "年"
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

    /** 二十四节气 (21世纪C值公式), 返回当天节气名, 否则 null */
    fun solarTerm(date: LocalDate): String? {
        val y = date.year % 100
        val leap = ((y - 1) / 4.0).toInt()
        // name, month, C
        val terms = arrayOf(
            arrayOf("小寒", 1, 5.4055),
            arrayOf("大寒", 1, 20.12),
            arrayOf("立春", 2, 3.87),
            arrayOf("雨水", 2, 18.73),
            arrayOf("惊蛰", 3, 5.63),
            arrayOf("春分", 3, 20.646),
            arrayOf("清明", 4, 4.81),
            arrayOf("谷雨", 4, 20.10),
            arrayOf("立夏", 5, 5.52),
            arrayOf("小满", 5, 21.04),
            arrayOf("芒种", 6, 5.678),
            arrayOf("夏至", 6, 21.37),
            arrayOf("小暑", 7, 7.108),
            arrayOf("大暑", 7, 22.83),
            arrayOf("立秋", 8, 7.50),
            arrayOf("处暑", 8, 23.13),
            arrayOf("白露", 9, 7.646),
            arrayOf("秋分", 9, 23.042),
            arrayOf("寒露", 10, 8.318),
            arrayOf("霜降", 10, 23.438),
            arrayOf("立冬", 11, 7.438),
            arrayOf("小雪", 11, 22.36),
            arrayOf("大雪", 12, 7.18),
            arrayOf("冬至", 12, 21.94),
        )
        for (t in terms) {
            if ((t[1] as Int) != date.monthValue) continue
            val day = Math.floor(y * 0.2422 + (t[2] as Double)).toInt() - leap
            if (day == date.dayOfMonth) return t[0] as String
        }
        return null
    }

    /** 当天所有节日 (可能多个, 如中秋+国庆), 无则返回空列表 */
    fun festivals(date: LocalDate): List<String> {
        val out = mutableListOf<String>()
        // 清明既是节气也是重要传统节日
        if (solarTerm(date) == "清明") out += "清明节"

        when (date.monthValue) {
            1 -> if (date.dayOfMonth == 1) out += "元旦"
            2 -> if (date.dayOfMonth == 14) out += "情人节"
            3 -> when (date.dayOfMonth) {
                8 -> out += "妇女节"
                12 -> out += "植树节"
            }
            4 -> if (date.dayOfMonth == 1) out += "愚人节"
            5 -> when (date.dayOfMonth) {
                1 -> out += "劳动节"
                4 -> out += "青年节"
            }
            6 -> if (date.dayOfMonth == 1) out += "儿童节"
            7 -> if (date.dayOfMonth == 1) out += "建党节"
            8 -> if (date.dayOfMonth == 1) out += "建军节"
            9 -> if (date.dayOfMonth == 10) out += "教师节"
            10 -> when (date.dayOfMonth) {
                1 -> out += "国庆节"
                31 -> out += "万圣节"
            }
            12 -> when (date.dayOfMonth) {
                24 -> out += "平安夜"
                25 -> out += "圣诞节"
            }
        }
        // 母亲节: 5月第二个周日; 父亲节: 6月第三个周日
        if (date.monthValue == 5 && date.dayOfWeek == java.time.DayOfWeek.SUNDAY) {
            val first = date.withDayOfMonth(1)
            val firstSunday = if (first.dayOfWeek == java.time.DayOfWeek.SUNDAY) 1
                else 8 - first.dayOfWeek.value
            if (date.dayOfMonth == firstSunday + 7) out += "母亲节"
        }
        if (date.monthValue == 6 && date.dayOfWeek == java.time.DayOfWeek.SUNDAY) {
            val first = date.withDayOfMonth(1)
            val firstSunday = if (first.dayOfWeek == java.time.DayOfWeek.SUNDAY) 1
                else 8 - first.dayOfWeek.value
            if (date.dayOfMonth == firstSunday + 14) out += "父亲节"
        }
        // 农历节日
        val lunar = of(date)
        if (lunar != null && !lunar.third) {
            val (m, d) = lunar
            when (m) {
                1 -> when (d) {
                    1 -> out += "春节"
                    15 -> out += "元宵节"
                }
                2 -> if (d == 2) out += "龙抬头"
                3 -> if (d == 3) out += "上巳节"
                5 -> if (d == 5) out += "端午节"
                7 -> when (d) {
                    7 -> out += "七夕节"
                    15 -> out += "中元节"
                }
                8 -> if (d == 15) out += "中秋节"
                9 -> if (d == 9) out += "重阳节"
                10 -> if (d == 1) out += "寒衣节"
                12 -> when (d) {
                    23 -> out += "北方小年"
                    24 -> out += "南方小年"
                    8 -> out += "腊八节"
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
