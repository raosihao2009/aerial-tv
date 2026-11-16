package app.orchard.tv.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** 胶囊收缩时长: 一个汉字 0.2 秒, 汉字以外的字符不计时。 */
class CapsuleTimingTest {

    @Test
    fun twoHanCharsTakeFourHundredMs() {
        assertEquals(400, capsuleShrinkMs("元旦"))
    }

    @Test
    fun fourHanCharsTakeEightHundredMs() {
        assertEquals(800, capsuleShrinkMs("南方小年"))
    }

    @Test
    fun eightHanCharsTakeSixteenHundredMs() {
        assertEquals(1600, capsuleShrinkMs("一二三四五六七八"))
    }

    @Test
    fun digitsAndPunctuationDoNotCount() {
        assertEquals(600, capsuleShrinkMs("3·8 妇女节"))
    }

    @Test
    fun noHanCharsFallsBackToMinimum() {
        assertEquals(400, capsuleShrinkMs("38"))
    }

    @Test
    fun blankTextFallsBackToMinimum() {
        assertEquals(400, capsuleShrinkMs(""))
    }

    @Test
    fun veryLongTextIsCapped() {
        assertEquals(3000, capsuleShrinkMs("一二三四五六七八九十一二三四五六七八九十"))
    }
}
