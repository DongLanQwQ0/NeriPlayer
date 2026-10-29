package moe.ouom.neriplayer.core.player.metadata

import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import org.junit.Assert.*
import org.junit.Test

class ExternalLyricWindowTest {
    private val lines = listOf(LyricEntry("first", 1000, 3000), LyricEntry("second", 3000, 5000),
        LyricEntry("", 6000, 7000), LyricEntry("third", 10000, 11000))
    @Test fun `lookahead uses the original offset without advancing the shared current line`() {
        val before = externalLyricWindow(lines, 700, 100)
        assertNull(before.current); assertEquals("first", before.next); assertEquals(900L, before.nextPositionMs)
        val next = externalLyricWindow(lines, 2700, 100)
        assertEquals("first", next.current); assertEquals("second", next.next)
        assertEquals(2900L, next.nextPositionMs)
        assertEquals("second", externalLyricWindow(lines, 2900, 100).current)
    }
    @Test fun `blank lines gaps expiry and simultaneous lines follow the current line selector`() {
        assertNull(externalLyricWindow(lines, 5900, 0).next)
        val blank = externalLyricWindow(lines, 6500, 0)
        assertNull(blank.current); assertEquals("third", blank.next)
        val expired = externalLyricWindow(lines, 11000, 0)
        assertEquals(12501L, expired.nextPositionMs)
        assertNull(externalLyricWindow(lines, 12501, 0).current)
        val duplicate = externalLyricWindow(listOf(LyricEntry("one", 1000, 2000), LyricEntry("two", 1000, 2000)), 0, 0)
        assertEquals("two", duplicate.next)
        assertEquals(ExternalLyricWindow(null, null, null, 0), externalLyricWindow(emptyList(), 0, 0))
    }
    @Test fun `repeated text does not hide the following deadline when no flow event is emitted`() {
        val repeated = listOf(LyricEntry("same", 1000, 2000), LyricEntry("same", 2000, 2200), LyricEntry("next", 2200, 3000))
        val window = externalLyricWindow(repeated, 1500, 0)
        assertEquals("same", window.current); assertEquals("next", window.next)
        assertEquals(2200L, window.nextPositionMs)
        assertEquals(200L, window.intervalMs)
    }
}
