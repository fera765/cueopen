package dev.fluwy.app.ui.mascot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SvgMascotParserTest {
    private val svg = """
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
          <defs><linearGradient id="g"><stop offset="0" stop-color="#fff"/><stop offset="1" stop-color="#000"/></linearGradient></defs>
          <g id="corpo"><circle cx="50" cy="55" r="40" fill="currentColor"/></g>
          <g id="olho_esq" transform="translate(35 45)"><ellipse rx="5" ry="7" fill="#16161c"/></g>
          <ellipse id="right-eye" cx="65" cy="45" rx="5" ry="7" style="fill:#16161c"/>
          <path id="boca" d="M40 70 Q50 78 60 70" stroke="black" fill="none" stroke-width="3"/>
          <rect id="braco" x="5" y="50" width="12" height="30" rx="6" fill="url(#g)"/>
          <polygon points="45,10 55,10 50,2" fill="rgb(255, 200, 0)"/>
        </svg>
    """.trimIndent()

    @Test
    fun readsShapesRolesAndColors() {
        val m = SvgMascotParser.parse(svg)
        assertEquals(100f, m.width); assertEquals(6, m.shapes.size)
        val roles = m.shapes.map { it.role }
        assertEquals(SvgMascotParser.Role.BODY, roles[0])
        assertEquals(SvgMascotParser.Role.EYE, roles[1])
        assertEquals(SvgMascotParser.Role.EYE, roles[2])
        assertEquals(SvgMascotParser.Role.MOUTH, roles[3])
        assertEquals(SvgMascotParser.Role.ARM, roles[4])
        assertEquals(SvgMascotParser.Role.NONE, roles[5])
        assertTrue(m.usesAgentColor && m.hasAnimatedParts)
        assertEquals(35f, m.shapes[1].transform.e)
        assertTrue(m.shapes[4].fill is SvgMascotParser.Paint.Linear)
        assertEquals(SvgMascotParser.Paint.Solid(0xFFFFC800), m.shapes[5].fill)
        assertEquals(null, m.shapes[3].fill)
    }

    @Test
    fun readsAndroidVectorDrawable() {
        val xml = """
            <vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp"
                android:viewportWidth="24" android:viewportHeight="24">
              <group android:name="wing_left" android:rotation="10" android:pivotX="12" android:pivotY="12">
                <path android:pathData="M2,2h4v4h-4z" android:fillColor="#80FF0000"/>
              </group>
              <path android:name="body" android:pathData="M8,8h8v8h-8z" android:fillColor="?attr/colorPrimary"/>
            </vector>
        """.trimIndent()
        val m = SvgMascotParser.parse(xml)
        assertEquals(2, m.shapes.size)
        assertEquals(SvgMascotParser.Role.WING, m.shapes[0].role)
        assertEquals(SvgMascotParser.Paint.Solid(0x80FF0000), m.shapes[0].fill)
        assertEquals(SvgMascotParser.Paint.Current, m.shapes[1].fill)
    }

    @Test
    fun rejectsDoctypeAndEmptyFiles() {
        for (bad in listOf("<!DOCTYPE svg [<!ENTITY x \"y\">]><svg/>", "<svg viewBox=\"0 0 10 10\"></svg>", "<html/>")) {
            try { SvgMascotParser.parse(bad); fail(bad) } catch (e: SvgMascotParser.InvalidMascot) { }
        }
    }

    @Test
    fun colorFormats() {
        assertEquals(0xFFFF0000, SvgMascotParser.parseColor("#f00", true))
        assertEquals(0x80FF0000, SvgMascotParser.parseColor("#FF000080", true))   // CSS: alpha no fim
        assertEquals(0x80FF0000, SvgMascotParser.parseColor("#80FF0000", false))  // Android: alpha no início
        assertEquals(0xFF008080, SvgMascotParser.parseColor("teal", true))
    }

    @Test
    fun roleNamesInPortugueseAndEnglish() {
        assertEquals(SvgMascotParser.Role.EYE, SvgMascotParser.roleFromName("olhos"))
        assertEquals(SvgMascotParser.Role.TAIL, SvgMascotParser.roleFromName("rabo-do-gato"))
        assertEquals(SvgMascotParser.Role.NONE, SvgMascotParser.roleFromName("highlight2"))
    }
}
