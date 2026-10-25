package com.brycewg.asrkb.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnAudioWindowsTest {
    @Test
    fun `short audio is padded without changing original samples`() {
        val audio = FloatArray(8000) { it.toFloat() / 8000 }
        val ranges = QnnAudioWindows.ranges(audio.size, emptyList())
        assertEquals(listOf(0 until 8000), ranges)
        val padded = QnnAudioWindows.padded(audio, ranges.single())
        assertEquals(80000, padded.size)
        assertTrue(audio.contentEquals(padded.copyOfRange(0, 8000)))
        assertTrue(padded.drop(8000).all { it == 0f })
    }

    @Test
    fun `continuous speech longer than five seconds is never truncated`() {
        for (size in listOf(1, 80000, 80001, 160000, 480000)) {
            val ranges = QnnAudioWindows.ranges(size, emptyList())
            assertEquals(0, ranges.first().first)
            assertEquals(size - 1, ranges.last().last)
            assertTrue(ranges.all { it.count() in 1..80000 })
            ranges.zipWithNext().forEach { (left, right) ->
                assertTrue(right.first > left.first)
                assertTrue(right.first <= left.last + 1)
            }
        }
        assertTrue(QnnAudioWindows.ranges(0, emptyList()).isEmpty())
    }

    @Test
    fun `ten VAD silence boundaries are preferred over arbitrary cuts`() {
        val ranges = QnnAudioWindows.ranges(160000, listOf(63000 until 65000))
        assertEquals(63999, ranges.first().last + 1)
        assertEquals(ranges.first().last + 1, ranges[1].first)
    }

    @Test
    fun `text overlap is removed only for overlapping windows`() {
        assertEquals("你好世界", QnnAudioWindows.append("你好世", "世界", true))
        assertEquals("你好你好", QnnAudioWindows.append("你好", "你好", false))
        assertEquals("hello world again", QnnAudioWindows.append("hello world", "world again", true))
        assertEquals("hello orange", QnnAudioWindows.append("hello", "orange", true))
        assertEquals("hello hello", QnnAudioWindows.append("hello", "hello", false))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `oversized model input is rejected instead of silently truncated`() {
        QnnAudioWindows.padded(FloatArray(80001), 0..80000)
    }
}
