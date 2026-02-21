package com.example.receipto.parser

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import java.time.LocalDate
import java.time.LocalTime
import android.graphics.Rect
import org.mockito.Mockito.`when`

class KieReceiptParserTest {

    private lateinit var classUnderTest: KieReceiptParser

    @Before
    fun setup() {
        val mockContext = mock(Context::class.java)
        classUnderTest = KieReceiptParser(mockContext)
    }

    @Test
    fun testParseLocalDateFromText() {
        // Valid US formats
        assertEquals(LocalDate.of(2023, 10, 25), classUnderTest.parseLocalDateFromText("10/25/2023"))
        assertEquals(LocalDate.of(2023, 10, 25), classUnderTest.parseLocalDateFromText("10-25-2023"))
        
        // Single digit months/days
        assertEquals(LocalDate.of(2023, 5, 4), classUnderTest.parseLocalDateFromText("5/4/2023"))
        
        // EU formats
        assertEquals(LocalDate.of(2023, 10, 25), classUnderTest.parseLocalDateFromText("25.10.2023"))
        
        // Textual dates
        assertEquals(LocalDate.of(2023, 10, 25), classUnderTest.parseLocalDateFromText("Oct 25, 2023"))
        assertEquals(LocalDate.of(2023, 10, 25), classUnderTest.parseLocalDateFromText("25 Oct 2023"))
        
        // Invalid dates
        assertNull(classUnderTest.parseLocalDateFromText("Not a date"))
        assertNull(classUnderTest.parseLocalDateFromText("2023"))
    }

    @Test
    fun testParseLocalTimeFromText() {
        // Valid formats
        assertEquals(LocalTime.of(14, 30, 45), classUnderTest.parseLocalTimeFromText("14:30:45"))
        assertEquals(LocalTime.of(14, 30), classUnderTest.parseLocalTimeFromText("14:30"))
        assertEquals(LocalTime.of(14, 30), classUnderTest.parseLocalTimeFromText("02:30 PM"))
        assertEquals(LocalTime.of(2, 30), classUnderTest.parseLocalTimeFromText("02:30 AM"))
        
        // Invalid times
        assertNull(classUnderTest.parseLocalTimeFromText("Not a time"))
        assertNull(classUnderTest.parseLocalTimeFromText("25:00"))
    }

    private fun mockRect(l: Int, t: Int, r: Int, b: Int): Rect {
        val rect = mock(Rect::class.java)
        rect.left = l
        rect.top = t
        rect.right = r
        rect.bottom = b
        `when`(rect.height()).thenReturn(b - t)
        return rect
    }

    @Test
    fun testGroupTokensIntoLinesWithMedianHeight() {
        // Page height is 1000px. 
        // Token boxes are 30 pixels high. Median height = 30px.
        // Row tolerance will be 15px (which is 0.015 in normalized coords).
        
        val tok1 = KieReceiptParser.UiTok("Apple", mockRect(10, 100, 100, 130)) // Center Y: 115
        val tok2 = KieReceiptParser.UiTok("Pie", mockRect(110, 102, 190, 132))   // Center Y: 117
        val tok3 = KieReceiptParser.UiTok("$5.00", mockRect(800, 108, 900, 138)) // Center Y: 123
        
        // This token is on a different line entirely
        val tok4 = KieReceiptParser.UiTok("Banana", mockRect(10, 200, 100, 230)) // Center Y: 215
        
        val tokens = listOf(tok1, tok2, tok3, tok4)
        
        val lines = classUnderTest.groupTokensIntoLines(tokens, 1000)
        
        // Assert we have exactly 2 lines
        assertEquals(2, lines.size)
        
        // Line 1 should cleanly contain Apple, Pie, and $5.00 thanks to strict dynamic tolerance
        assertEquals(3, lines[0].size)
        assertEquals("Apple", lines[0][0].text)
        assertEquals("Pie", lines[0][1].text)
        assertEquals("$5.00", lines[0][2].text)
        
        // Line 2 should contain Banana
        assertEquals(1, lines[1].size)
        assertEquals("Banana", lines[1][0].text)
    }
}
