package com.rigstudio.core.tests

import com.rigstudio.core.anim.PlaybackClock
import com.rigstudio.core.harness.Assert
import com.rigstudio.core.harness.TestCase

/**
 * Playback timing tests.
 *
 * The clock is driven by an injected nanosecond source, so every test steps time exactly and
 * deterministically — no sleeps, no flakiness, and the same code path the editor uses at 60 fps.
 */
object PlaybackTests {

    /** A fake wall clock the tests advance by hand. */
    private class FakeNanos(var nanos: Long = 0L) {
        val source: () -> Long = { nanos }
        fun advanceSeconds(seconds: Double) {
            nanos += (seconds * 1_000_000_000L).toLong()
        }
    }

    /**
     * Advances the fake wall clock in display-frame increments, ticking after each one — exactly
     * what the editor does at 60 fps. Stepping like this (instead of one giant jump) also exercises
     * the clock's per-tick delta cap the way real playback does.
     */
    private fun step(
        clock: PlaybackClock,
        fake: FakeNanos,
        seconds: Double,
        frameSeconds: Double = 1.0 / 60.0,
    ): Boolean {
        val frameNanos = (frameSeconds * 1_000_000_000L).toLong()
        var remaining = (seconds * 1_000_000_000L).toLong()
        var moved = false
        while (remaining > 0L) {
            val chunk = if (remaining > frameNanos) frameNanos else remaining
            fake.nanos += chunk
            remaining -= chunk
            moved = moved or clock.tick()
            if (!clock.isPlaying) break // a one-shot clip stopped itself; nothing more to advance
        }
        return moved
    }

    private fun clock(
        duration: Float = 2f,
        speed: Float = 1f,
        loop: Boolean = true,
        fake: FakeNanos = FakeNanos(),
    ): PlaybackClock = PlaybackClock(duration, speed, loop, fake.source)

    val cases: List<TestCase> = listOf(
        TestCase("starts paused at the beginning of the clip") {
            val fake = FakeNanos()
            val clock = clock(fake = fake)
            Assert.equals(0f, clock.timeSeconds, "playhead should start at zero")
            Assert.equals(0f, clock.normalizedTime, "normalised time should start at zero")
            Assert.equals(false, clock.isPlaying, "clock should not auto-play")
            fake.advanceSeconds(1.0)
            Assert.equals(false, clock.tick(), "a paused clock must not move")
            Assert.equals(0f, clock.timeSeconds, "paused playhead must stay put")
        },

        TestCase("advances in clip seconds at 1x speed") {
            val fake = FakeNanos()
            val clock = clock(duration = 2f, fake = fake)
            clock.play()
            Assert.equals(true, step(clock, fake, 0.5), "a playing clock should report movement")
            Assert.close(0.5f, clock.timeSeconds, 1e-4f, "half a wall second = half a clip second at 1x")
            Assert.close(0.25f, clock.normalizedTime, 1e-4f, "0.5s of a 2s clip is a quarter")
            Assert.equals(true, clock.isPlaying, "still playing mid-clip")
            Assert.equals(false, clock.isFinished, "not finished mid-clip")
        },

        TestCase("a tick with no elapsed wall time asks for no redraw") {
            val fake = FakeNanos()
            val clock = clock(duration = 2f, fake = fake)
            clock.play()
            step(clock, fake, 0.25)
            Assert.equals(false, clock.tick(), "zero elapsed time means zero movement")
            fake.advanceSeconds(0.016)
            Assert.equals(true, clock.tick(), "one display frame later it moves again")
        },

        TestCase("speed multiplier scales the rate, not the meaning of time") {
            val fake = FakeNanos()
            val clock = clock(duration = 2f, speed = 2f, fake = fake)
            clock.play()
            step(clock, fake, 0.5)
            Assert.close(1f, clock.timeSeconds, 1e-4f, "2x speed consumes two clip seconds per wall second")
            Assert.close(0.5f, clock.normalizedTime, 1e-4f, "half way through the clip")
            Assert.close(1f, clock.cycleSeconds, 1e-4f, "a 2s clip at 2x takes one wall second per cycle")
            Assert.close(0.5f, clock.elapsedSeconds, 1e-4f, "elapsed wall seconds = clip seconds / speed")
        },

        TestCase("slow motion stretches the cycle") {
            val fake = FakeNanos()
            val clock = clock(duration = 1f, speed = 0.25f, fake = fake)
            clock.play()
            step(clock, fake, 1.0)
            Assert.close(0.25f, clock.timeSeconds, 1e-4f, "quarter speed consumes a quarter clip second")
            Assert.close(4f, clock.cycleSeconds, 1e-4f, "a 1s clip at 0.25x takes four wall seconds")
        },

        TestCase("looping clips wrap instead of stopping") {
            val fake = FakeNanos()
            val clock = clock(duration = 1f, loop = true, fake = fake)
            clock.play()
            step(clock, fake, 1.3)
            Assert.inRange(clock.normalizedTime, 0f, 1f, "wrapped time must stay inside 0..1")
            Assert.close(0.3f, clock.timeSeconds, 1e-3f, "1.3s into a 1s loop is 0.3s into the next cycle")
            Assert.equals(true, clock.isPlaying, "a looping clip never stops on its own")
            Assert.equals(false, clock.isFinished, "a looping clip never finishes")
        },

        TestCase("looping never lands exactly on the wrap point") {
            val fake = FakeNanos()
            val clock = clock(duration = 1f, loop = true, fake = fake)
            clock.play()
            step(clock, fake, 0.97)
            Assert.that(clock.normalizedTime < 1f) { "just before the wrap it must stay below 1.0" }
            Assert.that(clock.normalizedTime > 0.9f) { "and it must be near the end of the cycle" }
            step(clock, fake, 0.05)
            Assert.that(clock.normalizedTime < 0.5f) { "crossing the wrap point restarts the cycle" }
            Assert.equals(true, clock.isPlaying, "looping keeps playing across the wrap")
        },

        TestCase("one-shot clips hold their final frame and stop") {
            val fake = FakeNanos()
            val clock = clock(duration = 1.5f, loop = false, fake = fake)
            clock.play()
            step(clock, fake, 0.6)
            Assert.equals(false, clock.isFinished, "not finished yet")
            val moved = clock.tick()
            Assert.equals(false, moved, "still not finished after one more frame")
            step(clock, fake, 2.0)
            Assert.equals(true, clock.isFinished, "the finishing step stops the clock")
            Assert.close(1f, clock.normalizedTime, 1e-6f, "clamped to the end")
            Assert.close(1.5f, clock.timeSeconds, 1e-4f, "playhead parks on the last frame")
            Assert.equals(true, clock.isFinished, "one-shot clip is finished")
            Assert.equals(false, clock.isPlaying, "and it stops itself")
            fake.advanceSeconds(5.0)
            Assert.equals(false, clock.tick(), "a finished clock reports no movement (no wasted redraws)")
            Assert.close(1.5f, clock.timeSeconds, 1e-6f, "final frame is held, not advanced")
        },

        TestCase("play after finishing restarts a one-shot clip") {
            val fake = FakeNanos()
            val clock = clock(duration = 1f, loop = false, fake = fake)
            clock.play()
            step(clock, fake, 2.0)
            Assert.equals(true, clock.isFinished)
            clock.play()
            Assert.equals(0f, clock.timeSeconds, "playing a finished one-shot starts over")
            Assert.equals(false, clock.isFinished, "finished flag cleared")
            Assert.equals(true, clock.isPlaying, "playing again")
        },

        TestCase("pause freezes the playhead but keeps its position") {
            val fake = FakeNanos()
            val clock = clock(duration = 4f, fake = fake)
            clock.play()
            step(clock, fake, 1.0)
            clock.pause()
            val parked = clock.timeSeconds
            fake.advanceSeconds(3.0)
            Assert.equals(false, clock.tick(), "paused clock reports no movement")
            Assert.close(parked, clock.timeSeconds, 1e-6f, "playhead did not move while paused")
            clock.play()
            step(clock, fake, 1.0)
            Assert.close(parked + 1f, clock.timeSeconds, 1e-4f, "resuming continues from where it paused")
        },

        TestCase("toggle switches between play and pause") {
            val clock = clock()
            Assert.equals(false, clock.isPlaying)
            clock.toggle()
            Assert.equals(true, clock.isPlaying, "first toggle plays")
            clock.toggle()
            Assert.equals(false, clock.isPlaying, "second toggle pauses")
        },

        TestCase("restart zeroes the playhead and plays") {
            val fake = FakeNanos()
            val clock = clock(duration = 2f, fake = fake)
            clock.play()
            step(clock, fake, 1.0)
            clock.restart()
            Assert.equals(0f, clock.timeSeconds, "playhead back to zero")
            Assert.equals(true, clock.isPlaying, "restart plays immediately")
            Assert.equals(false, clock.isFinished)
        },

        TestCase("seek moves the playhead and clamps out-of-range values") {
            val fake = FakeNanos()
            val clock = clock(duration = 4f, loop = true, fake = fake)
            clock.seekNormalized(0.5f)
            Assert.close(2f, clock.timeSeconds, 1e-4f, "half of a 4s clip is 2s")
            Assert.close(0.5f, clock.normalizedTime, 1e-6f)
            clock.seekNormalized(-3f)
            Assert.equals(0f, clock.timeSeconds, "negative seek clamps to the start")
            clock.seekNormalized(9f)
            Assert.close(4f, clock.timeSeconds, 1e-4f, "overshoot clamps to the end")
            clock.seekSeconds(1f)
            Assert.close(0.25f, clock.normalizedTime, 1e-4f, "second-based seek maps through the duration")
        },

        TestCase("seeking to the end of a one-shot clip marks it finished") {
            val fake = FakeNanos()
            val clock = clock(duration = 2f, loop = false, fake = fake)
            clock.seekNormalized(1f)
            Assert.equals(true, clock.isFinished, "scrubbing to the end of a one-shot holds the last frame")
            Assert.equals(false, clock.isPlaying, "and it does not keep running")
            val looping = clock(duration = 2f, loop = true, fake = FakeNanos())
            looping.seekNormalized(1f)
            Assert.equals(false, looping.isFinished, "scrubbing to the end of a loop is just the wrap point")
        },

        TestCase("seeking resets the delta timer so the next tick is honest") {
            val fake = FakeNanos()
            val clock = clock(duration = 4f, fake = fake)
            fake.advanceSeconds(10.0) // clock idle, not playing
            clock.seekNormalized(0.25f)
            clock.play()
            step(clock, fake, 0.5)
            Assert.close(1.5f, clock.timeSeconds, 1e-4f, "only the 0.5s after the seek counts")
        },

        TestCase("a huge gap is clamped so backgrounding cannot teleport the clip") {
            val fake = FakeNanos()
            val clock = clock(duration = 100f, fake = fake)
            clock.play()
            fake.advanceSeconds(600.0) // ten minutes in the background, one single tick
            Assert.equals(true, clock.tick(), "the first tick after a gap still moves")
            Assert.close(PlaybackClock.MAX_TICK_SECONDS, clock.timeSeconds, 1e-4f, "capped at MAX_TICK_SECONDS")
            Assert.equals(true, clock.isPlaying, "still playing after the capped step")
        },

        TestCase("clock refuses nonsense durations and speeds") {
            val clock = clock(duration = 0f)
            Assert.that(clock.durationSeconds >= PlaybackClock.MIN_DURATION) { "duration is floored" }
            Assert.inRange(clock.normalizedTime, 0f, 1f, "and normalised time stays finite")
            val negative = clock(duration = -3f)
            Assert.that(negative.durationSeconds >= PlaybackClock.MIN_DURATION) { "negative duration is floored too" }
            clock.speed = -5f
            Assert.close(PlaybackClock.MIN_SPEED, clock.speed, 1e-6f, "speed clamps to the floor")
            clock.speed = 1000f
            Assert.close(PlaybackClock.MAX_SPEED, clock.speed, 1e-6f, "speed clamps to the ceiling")
            Assert.inRange(clock.normalizedTime, 0f, 1f, "normalised time is always in range")
        },

        TestCase("retarget keeps the proportional position when switching clips") {
            val fake = FakeNanos()
            val clock = clock(duration = 1f, speed = 1f, loop = true, fake = fake)
            clock.seekNormalized(0.4f)
            clock.retarget(durationSeconds = 2.6f, speed = 2f)
            Assert.close(0.4f, clock.normalizedTime, 1e-4f, "still 40% through, now of the new clip")
            Assert.close(1.04f, clock.timeSeconds, 1e-3f, "40% of 2.6s")
            Assert.close(2f, clock.speed, 1e-6f, "new speed applied")
            clock.play()
            step(clock, fake, 0.25)
            Assert.close(1.54f, clock.timeSeconds, 1e-3f, "advances at the new speed")
        },

        TestCase("retargeting a one-shot clip re-evaluates the finished flag") {
            val clock = clock(duration = 1f, loop = false)
            clock.seekNormalized(1f)
            Assert.equals(true, clock.isFinished)
            clock.retarget(durationSeconds = 3f, loop = true)
            Assert.equals(true, clock.loop, "new loop setting applied")
            Assert.equals(false, clock.isFinished, "a looping clip is never finished")
            clock.retarget(durationSeconds = 3f, loop = false)
            Assert.equals(true, clock.isFinished, "back on a one-shot clip at the end, it is finished again")
        },

        TestCase("the clock matches the clip sampler it feeds") {
            val fake = FakeNanos()
            val clip = com.rigstudio.core.anim.AnimationLibrary.WALK
            val clock = clock(duration = clip.durationSeconds, loop = clip.loop, fake = fake)
            clock.play()
            step(clock, fake, clip.durationSeconds.toDouble() / 2.0)
            val pose = clip.sample(clock.normalizedTime)
            Assert.close(0.5f, clock.normalizedTime, 1e-3f, "half way through the walk cycle")
            Assert.inRange(pose.timeSeconds, 0f, clip.durationSeconds, "sampler accepted the clock's time")
            Assert.close(clip.durationSeconds * 0.5f, pose.timeSeconds, 1e-2f, "and reported matching clip time")
        },
    )
}
