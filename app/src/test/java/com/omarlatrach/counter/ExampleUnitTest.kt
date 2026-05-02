package com.omarlatrach.counter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun countSoundResForValue_returnsExpectedAudioResource() {
        assertEquals(R.raw.count_01, countSoundResForValue(1))
        assertEquals(R.raw.count_20, countSoundResForValue(20))
    }

    @Test
    fun countSoundResForValue_returnsNullOutsideSupportedRange() {
        assertNull(countSoundResForValue(0))
        assertNull(countSoundResForValue(21))
    }

    @Test
    fun formatElapsedTime_formatsMinutesAndSeconds() {
        assertEquals("01:05", formatElapsedTime(65))
    }

    @Test
    fun formatElapsedTime_formatsHoursWhenNeeded() {
        assertEquals("1:01:01", formatElapsedTime(3661))
    }
}
