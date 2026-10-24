package moe.ouom.neriplayer.core.player.service.presentation

import org.junit.Assert.*
import org.junit.Test

class VivoMusicSeekStateTest {
    @Test fun `a confirmed latest request still rejects a retired callback`() {
        val state = VivoMusicSeekState()
        state.begin("song", 60_000, 48_000, false, 0)
        state.begin("song", 60_000, 12_000, false, 100)
        assertEquals(12_000L, state.resolve("song", 60_000, 12_000, false, 600))
        assertEquals(12_000L, state.resolve("song", 60_000, 48_000, false, 650))
        assertEquals(12_000L, state.finish(700))
        assertFalse(state.pending)
    }

    @Test fun `guarded playback advances and a pause freezes the current target clock`() {
        val state = VivoMusicSeekState()
        state.begin("song", 60_000, 6_000, true, 0)
        assertEquals(7_200L, state.resolve("song", 60_000, 6_000, true, 1_200))
        assertEquals(7_300L, state.resolve("song", 60_000, 7_300, false, 1_300))
        assertEquals(7_300L, state.resolve("song", 60_000, 48_000, false, 2_000))
        assertEquals(7_300L, state.finish(2_500))
    }

    @Test fun `failed requests expire instead of freezing real playback`() {
        val state = VivoMusicSeekState()
        state.begin("song", 60_000, 48_000, false, 0)
        assertEquals(48_000L, state.resolve("song", 60_000, 6_000, false, 2_999))
        assertEquals(6_000L, state.resolve("song", 60_000, 6_000, false, 3_000))
        assertFalse(state.pending)
        state.begin("song", 60_000, 48_000, false, 3_001)
        assertNull(state.finish(6_001))
    }

    @Test fun `song and duration changes cancel the previous request`() {
        val state = VivoMusicSeekState()
        state.begin("old", 60_000, 48_000, false, 0)
        assertEquals(3_000L, state.resolve("new", 60_000, 3_000, false, 10))
        state.begin("new", 60_000, 48_000, false, 20)
        assertEquals(4_000L, state.resolve("new", 30_000, 4_000, false, 30))
        assertFalse(state.pending)
    }

    @Test fun `same timestamp retargets and old media state timestamps cannot reclaim ownership`() {
        val state = VivoMusicSeekState()
        state.begin("song", 60_000, 48_000, false, 100)
        state.begin("song", 60_000, 12_000, false, 100)
        assertEquals(12_000L, state.resolve("song", 60_000, 48_000, false, 100))
        assertTrue(state.predatesLatestClick(99))
        assertFalse(state.predatesLatestClick(100))
        state.clear()
        assertFalse(state.pending)
        assertFalse(state.predatesLatestClick(99))
    }
}
