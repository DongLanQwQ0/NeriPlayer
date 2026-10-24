package moe.ouom.neriplayer.core.player.service.presentation

import org.junit.Assert.*
import org.junit.Test

class VivoMusicProgressMotionTest {
    @Test fun `seek and fast retarget start from the visible position`() {
        val motion = VivoMusicProgressMotion()
        motion.update("song", 60_000, 6_000, false, 0)
        motion.update("song", 60_000, 48_000, false, 0, seek = true)
        assertEquals(0.1f, motion.frame(0).from, 0.001f)
        val halfway = motion.frame(200).from
        assertEquals(0.45f, halfway, 0.001f)
        motion.update("song", 60_000, 12_000, false, 200, seek = true)
        assertEquals(halfway, motion.frame(200).from, 0.001f)
        assertTrue(motion.frame(300).from in 0.2f..halfway)
        assertEquals(0.2f, motion.frame(600).from, 0.001f)
    }

    @Test fun `player acknowledgement does not end the transition early`() {
        val motion = VivoMusicProgressMotion()
        motion.update("song", 60_000, 6_000, false, 0)
        motion.update("song", 60_000, 48_000, false, 0, seek = true)
        motion.update("song", 60_000, 48_000, false, 20)
        assertTrue(motion.frame(20).from < 0.12f)
        assertEquals(380L, motion.frame(20).remainingMs)
        assertEquals(0.8f, motion.frame(400).from, 0.001f)
    }

    @Test fun `playback advances between updates and pauses without creeping`() {
        val motion = VivoMusicProgressMotion()
        motion.update("song", 60_000, 6_000, true, 0)
        assertEquals(0.15f, motion.frame(3_000).from, 0.001f)
        motion.update("song", 60_000, 9_000, false, 3_000)
        assertEquals(0.15f, motion.frame(8_000).from, 0.001f)
    }

    @Test fun `song duration and clear reset old motion and clamp to duration`() {
        val motion = VivoMusicProgressMotion()
        motion.update("old", 60_000, 6_000, false, 0)
        motion.update("old", 60_000, 48_000, false, 0, seek = true)
        motion.update("new", 30_000, 15_000, false, 100)
        assertEquals(0.5f, motion.frame(100).from, 0f)
        assertEquals(0L, motion.frame(100).remainingMs)
        motion.update("new", 10_000, 15_000, false, 110)
        assertEquals(1f, motion.frame(110).from, 0f)
        motion.clear()
        assertEquals(0f, motion.frame(500).from, 0f)
    }

    @Test fun `pause during a seek keeps the unfinished transition continuous`() {
        val motion = VivoMusicProgressMotion()
        motion.update("song", 60_000, 6_000, true, 0)
        motion.update("song", 60_000, 48_000, true, 0, seek = true)
        val visible = motion.frame(100).from
        motion.update("song", 60_000, 48_100, false, 100)
        assertEquals(visible, motion.frame(100).from, 0.001f)
        assertEquals(300L, motion.frame(100).remainingMs)
        assertEquals(48_100f / 60_000, motion.frame(500).from, 0.001f)
    }
}
