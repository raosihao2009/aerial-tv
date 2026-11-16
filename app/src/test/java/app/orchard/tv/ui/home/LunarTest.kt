package app.orchard.tv.ui.home

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The table was checked against macOS's Chinese calendar for every day from 1901 to 2100; it agreed
 * on all but 2057-09-28 and 2097-08-07, where macOS gives day 0 (the new moon falls about midnight).
 * These are a few of those days, kept so the table and its reading stay right.
 */
class LunarTest {
    private fun text(y: Int, m: Int, d: Int) = Lunar.text(LocalDate.of(y, m, d))

    @Test fun newYear() {
        assertEquals("正月初一", text(2026, 2, 17))
        assertEquals("腊月廿九", text(2026, 2, 16))
        assertEquals("正月初一", text(1901, 2, 19))
    }

    @Test fun ordinaryDays() {
        assertEquals("九月十六", text(2026, 10, 25))
        assertEquals("腊月初一", text(2100, 12, 31))
    }

    @Test fun leapMonths() {
        assertEquals("闰二月初一", text(2023, 3, 22))
        assertEquals("三月初一", text(2023, 4, 20))
        assertEquals("闰六月初一", text(2025, 7, 25))
        assertEquals("闰冬月初一", text(2033, 12, 22))
    }

    @Test fun outsideTheTable() {
        assertNull(text(1901, 2, 18))
        assertNull(text(2101, 3, 1))
    }

    @Test fun fixedFestivals() {
        assertEquals(listOf("元旦"), Lunar.festivals(LocalDate.of(2026, 1, 1)))
        assertTrue(Lunar.festivals(LocalDate.of(2026, 1, 10)).contains("中国人民警察节"))
        assertTrue(Lunar.festivals(LocalDate.of(2026, 10, 25))
            .containsAll(listOf("台湾光复纪念日", "抗美援朝纪念日")))
        assertTrue(Lunar.festivals(LocalDate.of(2026, 12, 24)).contains("长津湖战役胜利日"))
        assertEquals("万圣夜", Lunar.festivals(LocalDate.of(2026, 10, 31)).first())
        assertEquals("万圣节", Lunar.festivals(LocalDate.of(2026, 11, 1)).first())
    }

    @Test fun sanFu2026() {
        assertEquals("入伏", Lunar.seasonal(LocalDate.of(2026, 7, 15)).firstOrNull())
        assertEquals("中伏", Lunar.seasonal(LocalDate.of(2026, 7, 25)).firstOrNull())
        assertEquals("末伏", Lunar.seasonal(LocalDate.of(2026, 8, 14)).firstOrNull())
        assertTrue(Lunar.seasonal(LocalDate.of(2026, 7, 14)).isEmpty())
    }

    @Test fun shuJiu() {
        val dongZhi = LocalDate.of(2026, 12, 22)
        assertEquals("入九天", Lunar.seasonal(dongZhi).firstOrNull())
        assertEquals("三九天", Lunar.seasonal(dongZhi.plusDays(19)).firstOrNull())
    }

    @Test fun solarTermOnTheDay() {
        assertEquals("霜降", Lunar.solarTerm(LocalDate.of(2026, 10, 23)))
        assertNull(Lunar.solarTerm(LocalDate.of(2026, 10, 24)))
    }

    @Test fun currentTermStaysUntilNext() {
        val day = LocalDate.of(2026, 10, 23) // 霜降当天
        assertEquals("霜降", Lunar.currentTerm(day))
        assertEquals("霜降", Lunar.currentTerm(day.plusDays(1)))
        assertEquals("立冬", Lunar.currentTerm(LocalDate.of(2026, 11, 7)))
        // 元旦到小寒之前仍算上一年的冬至
        assertEquals("冬至", Lunar.currentTerm(LocalDate.of(2027, 1, 1)))
        assertEquals("小寒", Lunar.currentTerm(LocalDate.of(2027, 1, 6)))
    }

    @Test fun lunarFestivals2026() {
        assertTrue(Lunar.festivals(LocalDate.of(2026, 3, 3)).contains("元宵节"))
        assertTrue(Lunar.festivals(LocalDate.of(2026, 6, 19)).contains("端午节"))
        assertTrue(Lunar.festivals(LocalDate.of(2026, 9, 25)).contains("中秋节"))
    }

    @Test fun thanksgiving() {
        val first = LocalDate.of(2026, 11, 1)
        val fourthThu = generateSequence(first) { it.plusDays(1) }
            .filter { it.dayOfWeek == java.time.DayOfWeek.THURSDAY }.take(4).last()
        assertTrue(Lunar.festivals(fourthThu).contains("感恩节"))
    }
}
