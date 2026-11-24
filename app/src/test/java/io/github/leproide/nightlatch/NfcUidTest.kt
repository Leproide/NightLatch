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

package io.github.leproide.nightlatch

import io.github.leproide.nightlatch.nfc.NfcUid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcUidTest {
    @Test
    fun normaliseKeepsHexOnly() {
        assertEquals("04A23B11", NfcUid.normalise("04:a2-3b 11"))
        assertEquals("", NfcUid.normalise("zz::"))
    }

    @Test
    fun validLengthsAre4To10Bytes() {
        assertTrue(NfcUid.isValid("04A23B11"))          // 4 bytes
        assertTrue(NfcUid.isValid("04A23B11223344"))    // 7 bytes
        assertFalse(NfcUid.isValid("04A23B"))           // 3 bytes
        assertFalse(NfcUid.isValid("04A23B1"))          // odd length
        assertFalse(NfcUid.isValid("04a23b11"))         // must be normalised (upper case)
    }

    @Test
    fun randomUidDetected() {
        assertTrue(NfcUid.isRandom("08123456"))
        assertFalse(NfcUid.isRandom("04123456"))
        assertFalse(NfcUid.isRandom("08123456AABBCC"))
    }

    @Test
    fun fromBytes() {
        assertEquals("04A2FF", NfcUid.fromBytes(byteArrayOf(0x04, 0xA2.toByte(), 0xFF.toByte())))
        assertEquals("", NfcUid.fromBytes(null))
    }
}
