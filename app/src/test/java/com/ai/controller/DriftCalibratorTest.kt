package com.ai.controller

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DriftCalibratorTest {

    private lateinit var calibrator: DriftCalibrator

    @Before
    fun setUp() { calibrator = DriftCalibrator() }

    @Test
    fun `fresh calibrator passes values through unchanged`() {
        val (x, y) = calibrator.correct(0.5f, -0.3f)
        assertEquals(0.5f, x, 0.0001f)
        assertEquals(-0.3f, y, 0.0001f)
    }

    @Test
    fun `consistent near-rest drift is learned and subtracted`() {
        // 30 samples at (0.05, 0.03): magnitude ~0.058, inside REST 0.01..0.12
        repeat(30) { calibrator.correct(0.05f, 0.03f) }
        // bias = avg * LEARN_RATE(0.5) = (0.025, 0.015)
        val (x, y) = calibrator.correct(0.05f, 0.03f)
        assertEquals(0.025f, x, 0.0001f)
        assertEquals(0.015f, y, 0.0001f)
    }

    @Test
    fun `bias does not learn before full history window`() {
        repeat(29) { calibrator.correct(0.05f, 0.03f) }
        val (x, y) = calibrator.correct(0.05f, 0.03f)
        // 30th sample completes the window, but THIS call's correction used pre-learn bias
        assertEquals(0.05f, x, 0.0001f)
        assertEquals(0.03f, y, 0.0001f)
        // next call sees the learned bias
        val (x2, y2) = calibrator.correct(0.05f, 0.03f)
        assertEquals(0.025f, x2, 0.0001f)
        assertEquals(0.015f, y2, 0.0001f)
    }

    @Test
    fun `true zero drops partial rest history`() {
        repeat(29) { calibrator.correct(0.05f, 0.03f) }
        calibrator.correct(0.0f, 0.0f) // magnitude 0 < REST_MIN -> history dropped
        // one more near-rest sample = only 1 in window, no bias learned
        val (x, y) = calibrator.correct(0.05f, 0.03f)
        assertEquals(0.05f, x, 0.0001f)
        assertEquals(0.03f, y, 0.0001f)
    }

    @Test
    fun `active motion drops partial rest history`() {
        repeat(29) { calibrator.correct(0.05f, 0.03f) }
        calibrator.correct(0.6f, 0.6f) // magnitude > REST_MAX -> history dropped
        val (x, y) = calibrator.correct(0.05f, 0.03f)
        assertEquals(0.05f, x, 0.0001f)
        assertEquals(0.03f, y, 0.0001f)
    }

    @Test
    fun `reset clears learned bias`() {
        repeat(30) { calibrator.correct(0.05f, 0.03f) }
        calibrator.reset()
        val (x, y) = calibrator.correct(0.05f, 0.03f)
        assertEquals(0.05f, x, 0.0001f)
        assertEquals(0.03f, y, 0.0001f)
    }

    @Test
    fun `negative drift learns negative bias`() {
        repeat(30) { calibrator.correct(-0.04f, -0.02f) }
        val (x, y) = calibrator.correct(-0.04f, -0.02f)
        assertEquals(-0.02f, x, 0.0001f)
        assertEquals(-0.01f, y, 0.0001f)
    }
}
