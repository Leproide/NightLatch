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

package io.github.leproide.nightlatch.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import io.github.leproide.nightlatch.nfc.NfcUid
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local persistence (private SharedPreferences, JSON-encoded lists).
 *
 * Everything is validated on read, so a corrupted or tampered file degrades
 * to safe defaults instead of crashing the alarm path.
 * All methods are synchronized: receivers, the ring service and the UI may
 * touch the store from different threads.
 */
object Store {
    private const val TAG = "NightLatch/Store"
    private const val PREFS = "nightlatch"
    private const val K_ALARMS = "alarms"
    private const val K_TAGS = "tags"
    private const val K_NEXT_ID = "next_alarm_id"
    private const val K_SETTINGS = "settings"

    const val MAX_ALARMS = 50
    const val MAX_TAGS = 50

    /** Bumped on every write; Compose screens read it to refresh. */
    val revision = mutableIntStateOf(0)

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun bump() {
        revision.intValue = revision.intValue + 1
    }

    // ---------------------------------------------------------------- alarms

    @Synchronized
    fun alarms(ctx: Context): List<Alarm> {
        val raw = prefs(ctx).getString(K_ALARMS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until minOf(arr.length(), MAX_ALARMS)).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val hour = o.optInt("h", -1)
                val minute = o.optInt("m", -1)
                if (hour !in 0..23 || minute !in 0..59) return@mapNotNull null
                Alarm(
                    id = o.optInt("id", -1).takeIf { it > 0 } ?: return@mapNotNull null,
                    hour = hour,
                    minute = minute,
                    days = o.optInt("d", 0) and Alarm.ALL_DAYS,
                    enabled = o.optBoolean("on", false),
                    label = Alarm.sanitiseLabel(o.optString("l", "")),
                    tagUid = NfcUid.normalise(o.optString("t", "")).takeIf { NfcUid.isValid(it) },
                )
            }.distinctBy { it.id }.sortedWith(compareBy({ it.hour }, { it.minute }))
        } catch (e: Exception) {
            Log.w(TAG, "Corrupted alarm list, ignoring", e)
            emptyList()
        }
    }

    @Synchronized
    fun alarm(ctx: Context, id: Int): Alarm? = alarms(ctx).firstOrNull { it.id == id }

    @Synchronized
    private fun saveAlarms(ctx: Context, list: List<Alarm>) {
        val arr = JSONArray()
        list.take(MAX_ALARMS).forEach { a ->
            arr.put(
                JSONObject()
                    .put("id", a.id).put("h", a.hour).put("m", a.minute)
                    .put("d", a.days).put("on", a.enabled).put("l", a.label)
                    .put("t", a.tagUid ?: "")
            )
        }
        prefs(ctx).edit().putString(K_ALARMS, arr.toString()).commit()
        bump()
    }

    /** Inserts or replaces an alarm. A new alarm is passed with id = 0. Returns the stored alarm, or null if the limit is reached. */
    @Synchronized
    fun upsertAlarm(ctx: Context, alarm: Alarm): Alarm? {
        val list = alarms(ctx).toMutableList()
        val clean = alarm.copy(label = Alarm.sanitiseLabel(alarm.label), days = alarm.days and Alarm.ALL_DAYS)
        val stored = if (clean.id <= 0) {
            if (list.size >= MAX_ALARMS) return null
            val p = prefs(ctx)
            val id = p.getInt(K_NEXT_ID, 1).coerceAtLeast(1)
            p.edit().putInt(K_NEXT_ID, if (id == Int.MAX_VALUE) 1 else id + 1).commit()
            clean.copy(id = id).also { list.add(it) }
        } else {
            val idx = list.indexOfFirst { it.id == clean.id }
            if (idx < 0) list.add(clean) else list[idx] = clean
            clean
        }
        saveAlarms(ctx, list)
        return stored
    }

    @Synchronized
    fun deleteAlarm(ctx: Context, id: Int) {
        saveAlarms(ctx, alarms(ctx).filterNot { it.id == id })
    }

    // ------------------------------------------------------------------ tags

    @Synchronized
    fun tags(ctx: Context): List<PairedTag> {
        val raw = prefs(ctx).getString(K_TAGS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until minOf(arr.length(), MAX_TAGS)).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val uid = NfcUid.normalise(o.optString("uid", ""))
                if (!NfcUid.isValid(uid)) return@mapNotNull null
                PairedTag(uid, PairedTag.sanitiseName(o.optString("name", "")).ifEmpty { "TAG" })
            }.distinctBy { it.uid }
        } catch (e: Exception) {
            Log.w(TAG, "Corrupted tag list, ignoring", e)
            emptyList()
        }
    }

    @Synchronized
    private fun saveTags(ctx: Context, list: List<PairedTag>) {
        val arr = JSONArray()
        list.take(MAX_TAGS).forEach { arr.put(JSONObject().put("uid", it.uid).put("name", it.name)) }
        prefs(ctx).edit().putString(K_TAGS, arr.toString()).commit()
        bump()
    }

    enum class AddTagResult { ADDED, ALREADY_PAIRED, LIMIT, INVALID }

    @Synchronized
    fun addTag(ctx: Context, uidRaw: String, name: String): AddTagResult {
        val uid = NfcUid.normalise(uidRaw)
        if (!NfcUid.isValid(uid)) return AddTagResult.INVALID
        val list = tags(ctx)
        if (list.any { it.uid == uid }) return AddTagResult.ALREADY_PAIRED
        if (list.size >= MAX_TAGS) return AddTagResult.LIMIT
        val clean = PairedTag.sanitiseName(name).ifEmpty { "TAG-${list.size + 1}" }
        saveTags(ctx, list + PairedTag(uid, clean))
        return AddTagResult.ADDED
    }

    @Synchronized
    fun renameTag(ctx: Context, uid: String, name: String) {
        val clean = PairedTag.sanitiseName(name)
        if (clean.isEmpty()) return
        saveTags(ctx, tags(ctx).map { if (it.uid == uid) it.copy(name = clean) else it })
    }

    @Synchronized
    fun removeTag(ctx: Context, uid: String) {
        saveTags(ctx, tags(ctx).filterNot { it.uid == uid })
        // Alarms bound to the removed tag fall back to "any paired tag".
        val alarms = alarms(ctx)
        if (alarms.any { it.tagUid == uid }) saveAlarms(ctx, alarms.map { if (it.tagUid == uid) it.copy(tagUid = null) else it })
    }

    /** Name of a paired tag, or null if it is not paired (any more). */
    @Synchronized
    fun tagName(ctx: Context, uid: String?): String? = uid?.let { u -> tags(ctx).firstOrNull { it.uid == u }?.name }

    @Synchronized
    fun isPaired(ctx: Context, uidRaw: String): Boolean {
        val uid = NfcUid.normalise(uidRaw)
        return NfcUid.isValid(uid) && tags(ctx).any { it.uid == uid }
    }

    // ------------------------------------------------------- ringing state

    private const val K_RINGING = "ringing_alarm_id"

    /**
     * Id of the alarm that is ringing and not yet dismissed (0 = none). Persisted so
     * ringing resumes after the process is killed or the phone reboots.
     */
    @Synchronized
    fun ringingAlarm(ctx: Context): Int = prefs(ctx).getInt(K_RINGING, 0).coerceAtLeast(0)

    private const val K_RINGING_SINCE = "ringing_since"

    /** Wall-clock start of the current ringing (kept across restarts for the auto-silence timer). */
    @Synchronized
    fun ringingSince(ctx: Context): Long = prefs(ctx).getLong(K_RINGING_SINCE, 0L)

    private const val K_RING_HEARTBEAT = "ring_heartbeat"

    /** Last time a live ring service reported in (every ~30 s while ringing). */
    @Synchronized
    fun lastRingHeartbeat(ctx: Context): Long = prefs(ctx).getLong(K_RING_HEARTBEAT, 0L)

    @Synchronized
    fun ringHeartbeat(ctx: Context) {
        prefs(ctx).edit().putLong(K_RING_HEARTBEAT, System.currentTimeMillis()).apply()
    }

    @Synchronized
    fun setRingingAlarm(ctx: Context, id: Int) {
        val p = prefs(ctx)
        val e = p.edit().putInt(K_RINGING, id.coerceAtLeast(0))
        e.putLong(K_RING_HEARTBEAT, if (id > 0) System.currentTimeMillis() else 0L)
        when {
            id <= 0 -> e.remove(K_RINGING_SINCE)
            // Keep the original start time if we are resuming the same ringing session.
            p.getInt(K_RINGING, 0) == 0 || p.getLong(K_RINGING_SINCE, 0L) == 0L ->
                e.putLong(K_RINGING_SINCE, System.currentTimeMillis())
        }
        e.commit()
    }

    // ------------------------------------------------------------ onboarding

    private const val K_ONBOARDED = "onboarded"

    /** True once the first-launch setup wizard has been completed or skipped. */
    @Synchronized
    fun onboarded(ctx: Context): Boolean = prefs(ctx).getBoolean(K_ONBOARDED, false)

    @Synchronized
    fun setOnboarded(ctx: Context) {
        prefs(ctx).edit().putBoolean(K_ONBOARDED, true).commit()
        bump()
    }

    // -------------------------------------------------------------- settings

    @Synchronized
    fun settings(ctx: Context): Settings {
        val raw = prefs(ctx).getString(K_SETTINGS, null) ?: return Settings()
        return try {
            val o = JSONObject(raw)
            Settings(
                mathDifficulty = runCatching { MathDifficulty.valueOf(o.optString("diff")) }
                    .getOrDefault(MathDifficulty.NORMAL),
                mathCount = o.optInt("count", 3).coerceIn(Settings.MIN_MATH_COUNT, Settings.MAX_MATH_COUNT),
                mathFallbackEnabled = o.optBoolean("mathFallback", true),
                vibrate = o.optBoolean("vibrate", true),
                lockVolume = o.optBoolean("lockVolume", true),
                ringGlitch = o.optBoolean("ringGlitch", true),
                mathWording = runCatching { MathWording.valueOf(o.optString("wording")) }.getOrDefault(MathWording.MATH),
                rampVolume = o.optBoolean("rampVolume", false),
                rampSeconds = o.optInt("rampSec", 30).takeIf { it in Settings.RAMP_CHOICES } ?: 30,
                wakeCheck = o.optBoolean("wakeCheck", false),
                wakeCheckMinutes = o.optInt("wakeCheckMin", 5).takeIf { it in Settings.WAKE_CHECK_CHOICES } ?: 5,
                // "hardOps" is the key used by the previous build.
                mathOps = (o.optInt("mathOps", o.optInt("hardOps", MathOp.ALL)) and MathOp.ALL)
                    .takeIf { it != 0 } ?: MathOp.ALL,
                ringtoneUri = o.optString("ringtone", "").takeIf { isAllowedRingtoneUri(it) },
            )
        } catch (e: Exception) {
            Log.w(TAG, "Corrupted settings, using defaults", e)
            Settings()
        }
    }

    @Synchronized
    fun saveSettings(ctx: Context, s: Settings) {
        val o = JSONObject()
            .put("diff", s.mathDifficulty.name)
            .put("count", s.mathCount.coerceIn(Settings.MIN_MATH_COUNT, Settings.MAX_MATH_COUNT))
            .put("mathFallback", s.mathFallbackEnabled)
            .put("vibrate", s.vibrate)
            .put("lockVolume", s.lockVolume)
            .put("ringGlitch", s.ringGlitch)
            .put("wording", s.mathWording.name)
            .put("rampVolume", s.rampVolume)
            .put("rampSec", s.rampSeconds.takeIf { it in Settings.RAMP_CHOICES } ?: 30)
            .put("wakeCheck", s.wakeCheck)
            .put("wakeCheckMin", s.wakeCheckMinutes.takeIf { it in Settings.WAKE_CHECK_CHOICES } ?: 5)
            .put("mathOps", (s.mathOps and MathOp.ALL).takeIf { it != 0 } ?: MathOp.ALL)
            .put("ringtone", s.ringtoneUri?.takeIf { isAllowedRingtoneUri(it) } ?: "")
        prefs(ctx).edit().putString(K_SETTINGS, o.toString()).commit()
        bump()
    }

    // ------------------------------------------------------ export / import

    const val MAX_IMPORT_BYTES = 64 * 1024
    private const val EXPORT_FORMAT = "nightlatch-backup"
    private const val EXPORT_VERSION = 1

    /** Alarms, tags and settings as one JSON document (no ringing state, no permissions). */
    @Synchronized
    fun exportJson(ctx: Context): String {
        val p = prefs(ctx)
        return JSONObject()
            .put("format", EXPORT_FORMAT)
            .put("version", EXPORT_VERSION)
            .put("alarms", JSONArray(p.getString(K_ALARMS, null) ?: "[]"))
            .put("tags", JSONArray(p.getString(K_TAGS, null) ?: "[]"))
            .put("settings", JSONObject(p.getString(K_SETTINGS, null) ?: "{}"))
            .toString(2)
    }

    /**
     * Replaces alarms, tags and settings with a backup. Everything goes through the same
     * validation used on normal reads (bad entries are dropped). Returns false, changing
     * nothing, if the document is not a NightLatch backup. The caller re-schedules alarms.
     */
    @Synchronized
    fun importJson(ctx: Context, json: String): Boolean {
        if (json.length > MAX_IMPORT_BYTES) return false
        val root = try { JSONObject(json) } catch (e: Exception) { return false }
        if (root.optString("format") != EXPORT_FORMAT || root.optInt("version", 0) !in 1..EXPORT_VERSION) return false
        val alarmsArr = root.optJSONArray("alarms") ?: JSONArray()
        val tagsArr = root.optJSONArray("tags") ?: JSONArray()
        val settingsObj = root.optJSONObject("settings") ?: JSONObject()
        val p = prefs(ctx)
        // Keep the old data to roll back if the parsed result is unusable.
        val old = Triple(p.getString(K_ALARMS, null), p.getString(K_TAGS, null), p.getString(K_SETTINGS, null))
        p.edit()
            .putString(K_ALARMS, alarmsArr.toString())
            .putString(K_TAGS, tagsArr.toString())
            .putString(K_SETTINGS, settingsObj.toString())
            .commit()
        return try {
            // Re-read through the validators and store the cleaned result.
            val cleanAlarms = alarms(ctx)
            val cleanTags = tags(ctx)
            val cleanSettings = settings(ctx)
            saveTags(ctx, cleanTags)
            saveAlarms(ctx, cleanAlarms)
            saveSettings(ctx, cleanSettings)
            val maxId = cleanAlarms.maxOfOrNull { it.id } ?: 0
            if (p.getInt(K_NEXT_ID, 1) <= maxId) p.edit().putInt(K_NEXT_ID, maxId + 1).commit()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Import failed, rolling back", e)
            p.edit().putString(K_ALARMS, old.first).putString(K_TAGS, old.second).putString(K_SETTINGS, old.third).commit()
            bump()
            false
        }
    }

    /** Only content:// and android.resource:// URIs are accepted (no file:// or http). */
    fun isAllowedRingtoneUri(uri: String?): Boolean =
        !uri.isNullOrBlank() && uri.length <= 512 &&
            (uri.startsWith("content://") || uri.startsWith("android.resource://"))
}
