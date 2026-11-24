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

package io.github.leproide.nightlatch.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Thin wrapper around [NfcAdapter.enableReaderMode]. Reader mode only works while
 * the activity is resumed, so call [start] from onResume and [stop] from onPause.
 * While it is on, no other app can grab a tag: keep it on for the whole time the
 * activity is visible and just ignore UIDs you don't need. A tag left on the phone
 * is reported once, with one short vibration (system beeps are disabled).
 * The callback is delivered on the main thread.
 */
class TagScanner(
    private val activity: Activity,
    /** Short vibration on each new read. Off on the ringing screen: it would cut the alarm vibration. */
    private val haptic: Boolean = true,
    private val onUid: (String) -> Unit,
) {

    companion object {
        private const val TAG = "NightLatch/NFC"
        /** A tag that keeps being re-detected within this window counts as one read. */
        private const val SAME_TAG_WINDOW_MS = 2_000L
        /** How long the system ignores the last tag after reader mode is turned off. */
        private const val IGNORE_ON_STOP_MS = 1_500
        /** Tags must answer the presence check less often: fewer re-discoveries. */
        private const val PRESENCE_CHECK_DELAY_MS = 1_000
    }

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private val main = Handler(Looper.getMainLooper())
    private var active = false

    private var lastUid = ""
    private var lastAt = 0L
    @Volatile private var lastTag: Tag? = null
    @Volatile private var lastTagAt = 0L

    val hasNfc: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    fun start() {
        val nfc = adapter ?: return
        if (active || !nfc.isEnabled) return
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
            // We only need the UID: skip NDEF discovery (faster, works on unformatted tags).
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
            // No system beep/vibration on every discovery: we give our own single feedback.
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, PRESENCE_CHECK_DELAY_MS) }
        try {
            nfc.enableReaderMode(activity, { tag ->
                lastTag = tag
                lastTagAt = SystemClock.elapsedRealtime()
                val uid = NfcUid.fromBytes(tag.id)
                main.post {
                    val now = SystemClock.elapsedRealtime()
                    val sameTagStillThere = uid == lastUid && now - lastAt < SAME_TAG_WINDOW_MS
                    // Refresh the window on every re-detection: a tag left on the phone is one read.
                    lastUid = uid
                    lastAt = now
                    if (sameTagStillThere) return@post
                    if (haptic) tick()
                    onUid(uid)
                }
            }, flags, extras)
            active = true
        } catch (e: Exception) {
            // Can throw if the NFC service is restarting; the caller keeps the math fallback.
            Log.w(TAG, "enableReaderMode failed", e)
        }
    }

    fun stop() {
        if (!active) return
        val nfc = adapter
        // If a tag is (probably) still touching the phone, ask the system to ignore it for a
        // moment: otherwise, once reader mode is off, Android re-discovers it and opens the
        // default NFC app. Done only here, not on every read (that caused a re-read loop).
        val tag = lastTag
        if (nfc != null && tag != null && SystemClock.elapsedRealtime() - lastTagAt < SAME_TAG_WINDOW_MS) {
            try {
                nfc.ignore(tag, IGNORE_ON_STOP_MS, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "ignore() failed", e)
            }
        }
        try {
            nfc?.disableReaderMode(activity)
        } catch (e: Exception) {
            Log.w(TAG, "disableReaderMode failed", e)
        }
        active = false
    }

    @Suppress("DEPRECATION")
    private fun tick() {
        try {
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                activity.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                activity.getSystemService(Vibrator::class.java)
            }
            if (v.hasVibrator()) v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            Log.w(TAG, "haptic failed", e)
        }
    }
}
