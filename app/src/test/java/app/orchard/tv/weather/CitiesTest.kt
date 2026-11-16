package app.orchard.tv.weather

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 手机页面上填的城市名要能在本地城市表里落地, 才谈得上换成中国天气网的站点代码。
 * 这里盯住的正是之前查不到的两类名字: 地级市全称, 和带民族名的自治县全称。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CitiesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun findsPrefectureByFullName() {
        assertEquals("怀化", Cities.find(context, "怀化市")?.name)
        assertEquals("怀化", Cities.find(context, "怀化")?.name)
        assertEquals("怀化", Cities.find(context, "湖南怀化")?.name)
    }

    @Test
    fun findsAutonomousCountyByFullName() {
        assertEquals("新晃", Cities.find(context, "新晃侗族自治县")?.name)
        assertEquals("新晃", Cities.find(context, "新晃")?.name)
    }

    @Test
    fun findsCountyUnderItsPrefecture() {
        assertEquals("芷江", Cities.find(context, "芷江侗族自治县")?.name)
        assertEquals("会同", Cities.find(context, "会同县")?.name)
    }
}
