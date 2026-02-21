package com.example.receipto.parser

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import java.time.LocalDate
import java.time.LocalTime

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
}
