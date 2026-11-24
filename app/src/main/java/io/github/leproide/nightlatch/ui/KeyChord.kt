/*
 * NightLatch - NFC alarm clock
 * Copyright (C) 2026 Leproide
 * Author: https://github.com/Leproide
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.leproide.nightlatch.ui

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

/**
 * Hardware-key chord recogniser: a list of short taps followed by one long hold.
 * Feed every key down/up event; [onMatch] runs once when the chord completes.
 * Each tap must follow the previous one within [stepTimeoutMs]; any other key or
 * a slow/long tap resets the progress.
 */
class KeyChord(
    private val taps: IntArray,
    private val holdKey: Int,
    private val holdMs: Long,
    private val onMatch: () -> Unit,
    /** Extra condition that must hold at every step and for the whole hold. */
    private val guard: () -> Boolean = { true },
    private val stepTimeoutMs: Long = 3_000L,
    private val maxTapMs: Long = 1_000L,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var progress = 0
    private var lastStepAt = 0L
    private var holding = false
    private val holdDone = Runnable {
        holding = false
        progress = 0
        if (guard()) onMatch()
    }

    fun onKeyDown(event: KeyEvent) {
        if (event.repeatCount > 0) return // auto-repeat while held: timing is done by holdDone
        expireIfStale(event.eventTime)
        if (!guard()) { reset(); return }
        if (progress == taps.size && event.keyCode == holdKey) {
            holding = true
            handler.postDelayed(holdDone, holdMs)
        }
    }

    fun onKeyUp(event: KeyEvent) {
        if (holding) {
            // Released before the hold time elapsed: start over.
            handler.removeCallbacks(holdDone)
            holding = false
            reset()
            return
        }
        expireIfStale(event.eventTime)
        if (!guard()) { reset(); return }
        val quick = event.eventTime - event.downTime <= maxTapMs
        when {
            progress < taps.size && quick && event.keyCode == taps[progress] -> {
                progress++
                lastStepAt = event.eventTime
            }
            // Wrong key: it may be the first tap of a fresh attempt.
            quick && taps.isNotEmpty() && event.keyCode == taps[0] -> {
                progress = 1
                lastStepAt = event.eventTime
            }
            else -> reset()
        }
    }

    fun cancel() {
        handler.removeCallbacks(holdDone)
        holding = false
        reset()
    }

    private fun expireIfStale(now: Long) {
        if (progress > 0 && now - lastStepAt > stepTimeoutMs) reset()
    }

    private fun reset() {
        progress = 0
        lastStepAt = 0L
    }
}
