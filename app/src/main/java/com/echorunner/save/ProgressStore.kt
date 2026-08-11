package com.echorunner.save

import android.content.Context
import android.util.Log
import com.echorunner.core.EndlessRecord
import com.echorunner.core.GameProgress
import com.echorunner.core.GameSettings
import com.echorunner.core.LevelRecord
import com.echorunner.core.ProgressRepository
import com.echorunner.core.Rating
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * JSON-file implementation of [ProgressRepository].
 *
 * Three properties drive the whole design:
 *
 * 1. **A save is never half-written.** Every write goes to `progress.json.tmp`, is flushed and
 *    fsync'd, and only then renamed over `progress.json`. A mobile game is killed mid-write
 *    routinely (task swipe, low memory, battery), and `rename(2)` is the one operation that
 *    guarantees a reader sees either the whole old file or the whole new one.
 * 2. **A bad save can never brick the app.** Any failure to read or parse degrades to a fresh
 *    default [GameProgress] instead of throwing out of `load()`. Losing progress is bad; an
 *    app that cannot launch is worse.
 * 3. **The game loop never touches the disk.** Reads come from an in-memory cache, writes are
 *    handed to a single background thread and coalesced, so finishing a level cannot stutter
 *    a frame.
 *
 * The file name is fixed: `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`
 * both name `progress.json` explicitly, so renaming it would silently drop the player's save
 * out of cloud backup and device transfer.
 */
class ProgressStore(context: Context) : ProgressRepository {

    /**
     * Only the directory is retained, never the [Context] itself — this store outlives any
     * Activity that happens to construct it.
     */
    private val dir: File = context.filesDir
    private val file = File(dir, FILE_NAME)
    private val tmpFile = File(dir, TMP_NAME)
    private val corruptFile = File(dir, CORRUPT_NAME)

    /** Guards read-modify-write on [cached]. Held only for in-memory work, never for I/O. */
    private val stateLock = Any()

    /** Serialises the background writer against a caller-thread [flush]. */
    private val writeLock = Any()

    private val scheduleLock = Any()

    @Volatile
    private var cached: GameProgress? = null

    /** Newest un-written snapshot. Coalescing is just "last writer wins" on this slot. */
    private val pending = AtomicReference<GameProgress?>(null)

    private var scheduledWrite: ScheduledFuture<*>? = null

    private val io: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(
            ThreadFactory { runnable ->
                Thread(runnable, "echo-progress-io").apply {
                    // Daemon: a queued save must never keep the process alive after exit.
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY - 1
                }
            },
        )

    // ---- ProgressRepository ----------------------------------------------------------

    /**
     * Returns the cached progress, reading the file once on first call.
     *
     * Cheap and allocation-free after that first call, so UI and the game loop can read it
     * freely. Call it once during startup so the single disk read does not land mid-frame.
     */
    override fun load(): GameProgress = cached ?: synchronized(stateLock) {
        cached ?: readOrDefault().also { cached = it }
    }

    /**
     * Final backstop around [readFromDisk]. `load()` sits on the launch path, so *nothing* —
     * not a parse failure, not a `SecurityException` from a stat, not an unreadable directory —
     * may escape it. Starting fresh loses progress; throwing here loses the app.
     */
    private fun readOrDefault(): GameProgress = try {
        readFromDisk()
    } catch (t: Throwable) {
        Log.w(TAG, "Progress load failed outright; starting fresh", t)
        GameProgress()
    }

    /**
     * Replaces the whole progress verbatim and schedules a write.
     *
     * Deliberately *not* merged: this is the "I have the authoritative state" path. Anything
     * that must not regress a record goes through [updateLevel], which applies [bestOf].
     */
    override fun save(progress: GameProgress) = mutate { progress }

    /**
     * Reads the current record (or a fresh one), applies [transform], then folds the result in
     * through [bestOf] so a worse run can never overwrite a better one.
     */
    override fun updateLevel(levelId: String, transform: (LevelRecord) -> LevelRecord) =
        mutate { current ->
            val existing = current.levels[levelId] ?: LevelRecord(levelId)
            val merged = bestOf(existing, transform(existing).copy(levelId = levelId))
            if (merged == existing) return@mutate current // no-op: don't dirty the file
            val levels = HashMap<String, LevelRecord>(current.levels.size + 1)
            levels.putAll(current.levels)
            levels[levelId] = merged
            current.copy(levels = levels)
        }

    override fun updateSettings(transform: (GameSettings) -> GameSettings) =
        mutate { current -> current.copy(settings = transform(current.settings)) }

    // ---- The one addition to the interface --------------------------------------------

    /**
     * Writes any pending snapshot synchronously on the calling thread.
     *
     * Call from `onPause`/`onStop`: the 250 ms coalescing window is the difference between a
     * smooth frame and a stutter during play, but it is also a window in which the process can
     * be killed with the save still only in memory. Blocks until the in-flight background
     * write (if any) has finished. Safe to call repeatedly and when nothing is pending.
     */
    fun flush() {
        synchronized(scheduleLock) {
            scheduledWrite?.cancel(false)
            scheduledWrite = null
        }
        // Blocks on writeLock, so a background write already in progress completes first;
        // it will have drained `pending` inside the same monitor, leaving nothing to redo.
        drainAndWrite()
    }

    // ---- Mutation plumbing ------------------------------------------------------------

    private fun mutate(block: (GameProgress) -> GameProgress) {
        val updated: GameProgress
        synchronized(stateLock) {
            val current = load()
            updated = block(current)
            // Equal state means an equal file; skip the write rather than churning the disk.
            if (updated == current) return
            cached = updated
        }
        scheduleWrite(updated)
    }

    private fun scheduleWrite(snapshot: GameProgress) {
        pending.set(snapshot)
        synchronized(scheduleLock) {
            // A write is already due inside the window — it will pick up this newer snapshot.
            // Not re-armed on every call, so a burst of saves still lands within 250 ms
            // instead of being starved by a sliding deadline. A non-null slot always means
            // "armed but not yet started", because [writeTask] clears it before it drains.
            if (scheduledWrite != null) return
            scheduledWrite = try {
                // Explicit Runnable: schedule() is overloaded on Runnable and Callable, and a
                // bare lambda leaves that ambiguous.
                io.schedule(writeTask, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
            } catch (e: RejectedExecutionException) {
                // Should not happen (the executor is never shut down), but dropping a save is
                // worse than one slow call, so write it here instead.
                Log.w(TAG, "Progress writer rejected a task; writing inline", e)
                drainAndWrite()
                null
            }
        }
    }

    /**
     * The debounced write, hoisted so it is allocated once rather than per save.
     *
     * It releases its slot **before** draining. That ordering is the whole point: a save that
     * lands while this write is in flight would otherwise see a non-null slot, assume this task
     * will still observe it, and be dropped — `pending` may already have been drained. Clearing
     * first means such a save arms a fresh task, which blocks on `writeLock` until this one
     * finishes and then writes it.
     */
    private val writeTask = Runnable {
        synchronized(scheduleLock) { scheduledWrite = null }
        drainAndWrite()
    }

    private fun drainAndWrite() {
        synchronized(writeLock) {
            // Loop rather than write once: a save landing mid-write is published by this same
            // task instead of waiting out another debounce window.
            while (true) {
                val snapshot = pending.getAndSet(null) ?: return
                writeToDisk(snapshot)
            }
        }
    }

    // ---- Disk ------------------------------------------------------------------------

    /** Atomic save: fully materialise the temp file, fsync it, then rename it into place. */
    private fun writeToDisk(progress: GameProgress) {
        try {
            if (!dir.exists()) dir.mkdirs()
            val bytes = encode(progress).toByteArray(Charsets.UTF_8)
            if (tmpFile.exists()) tmpFile.delete()
            FileOutputStream(tmpFile).use { out ->
                out.write(bytes)
                out.flush()
                // Without the fsync the rename can reach the disk before the bytes do, so a
                // power loss would publish an empty file over a perfectly good save.
                out.fd.sync()
            }
            if (replaceAtomically(tmpFile, file)) {
                syncDirEntry()
            } else {
                Log.w(TAG, "Could not publish $TMP_NAME over $FILE_NAME; previous save kept")
            }
        } catch (t: Throwable) {
            // A failed save must not propagate into gameplay; the cache still holds the state
            // and the next mutation (or flush) retries.
            Log.w(TAG, "Failed to write $FILE_NAME", t)
            runCatching { tmpFile.delete() }
        }
    }

    /**
     * fsyncs the containing directory so the *rename* is durable, not just the file's bytes.
     * `out.fd.sync()` guarantees the data reached the disk; only this guarantees the directory
     * entry pointing at it did too, so a power loss cannot leave a published save unreachable.
     * Best-effort — there is no portable Java API for it, so it goes through `android.system.Os`
     * and is swallowed if the platform refuses. Never a reason to fail a save.
     */
    private fun syncDirEntry() {
        runCatching {
            val fd = android.system.Os.open(
                dir.absolutePath,
                android.system.OsConstants.O_RDONLY,
                0,
            )
            try {
                android.system.Os.fsync(fd)
            } finally {
                android.system.Os.close(fd)
            }
        }
    }

    private fun replaceAtomically(tmp: File, target: File): Boolean {
        // rename(2) within one filesystem: atomically replaces the destination.
        if (tmp.renameTo(target)) return true
        try {
            // Some filesystems refuse to clobber an existing destination via renameTo.
            // java.nio.file lands at API 26, which is our minSdk.
            java.nio.file.Files.move(
                tmp.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "Atomic move fallback failed", t)
        }
        // Last resort, and the only non-atomic path: unlink then rename. A kill in this gap
        // leaves the complete save in the temp file, which readFromDisk() recovers.
        return target.delete() && tmp.renameTo(target)
    }

    private fun readFromDisk(): GameProgress {
        val hasMain = file.exists() && file.length() > 0L
        val hasTmp = tmpFile.exists() && tmpFile.length() > 0L
        if (!hasMain && !hasTmp) return GameProgress() // first launch, not an error

        if (hasMain) decodeOrNull(file)?.let { return it }

        if (hasTmp) {
            // Either the non-atomic replace path above was interrupted, or the temp file is a
            // torn write. A torn write fails to parse and is ignored; a complete one is real
            // progress and is worth promoting.
            decodeOrNull(tmpFile)?.let { recovered ->
                Log.w(TAG, "Recovered progress from $TMP_NAME; republishing as $FILE_NAME")
                // Goes through the normal writer so it takes writeLock rather than racing a
                // background write, and so the republish stays off the caller's thread.
                scheduleWrite(recovered)
                return recovered
            }
        }

        quarantineCorrupt()
        return GameProgress()
    }

    private fun decodeOrNull(source: File): GameProgress? = try {
        val text = source.readText(Charsets.UTF_8)
        if (text.isBlank()) null else decode(text)
    } catch (t: Throwable) {
        // Truncated JSON, wrong types, unreadable file — all the same outcome: unusable.
        Log.w(TAG, "Unreadable save at ${source.name}", t)
        null
    }

    /**
     * Moves an unparseable save aside instead of leaving it to be re-read (and re-logged) on
     * every launch, and instead of letting the next successful write destroy the evidence.
     * One fixed name, so at most one quarantined file ever exists.
     */
    private fun quarantineCorrupt() {
        runCatching {
            if (file.exists()) {
                if (corruptFile.exists()) corruptFile.delete()
                if (file.renameTo(corruptFile)) {
                    Log.w(TAG, "Corrupt save quarantined as $CORRUPT_NAME; starting fresh")
                } else {
                    file.delete()
                }
            }
            if (tmpFile.exists()) tmpFile.delete()
        }
    }

    // ---- Serialisation ---------------------------------------------------------------

    /**
     * Every field of [GameProgress] round-trips. Collections are written sorted so an
     * unchanged state produces byte-identical output, which makes a save easy to eyeball and
     * diff while debugging.
     */
    private fun encode(progress: GameProgress): String {
        val root = JSONObject()
        root.put(KEY_VERSION, SCHEMA_VERSION)

        val levels = JSONObject()
        for (id in progress.levels.keys.sorted()) {
            val record = progress.levels[id] ?: continue
            val obj = JSONObject()
            // The map key is authoritative on read; levelId is written to keep the file
            // self-describing.
            obj.put("levelId", record.levelId)
            obj.put("completed", record.completed)
            obj.put("bestTakes", record.bestTakes)
            obj.put("bestSeconds", finite(record.bestSeconds, 0f).toDouble())
            obj.put("deaths", record.deaths)
            obj.put("bestStars", record.bestStars)
            levels.put(id, obj)
        }
        root.put("levels", levels)

        val endless = JSONObject()
        endless.put("bestScore", progress.endless.bestScore)
        endless.put("bestRooms", progress.endless.bestRooms)
        root.put("endless", endless)

        val challenges = JSONArray()
        for (id in progress.challengesCompleted.sorted()) challenges.put(id)
        root.put("challengesCompleted", challenges)

        val settings = JSONObject()
        val s = progress.settings
        settings.put("musicEnabled", s.musicEnabled)
        settings.put("sfxEnabled", s.sfxEnabled)
        settings.put("hapticsEnabled", s.hapticsEnabled)
        settings.put("leftHanded", s.leftHanded)
        // JSONObject.put rejects NaN/Infinity with a JSONException, which would turn one bad
        // float into a permanently failing save. Sanitise on the way out.
        settings.put("controlScale", finite(s.controlScale, DEFAULT_SETTINGS.controlScale).toDouble())
        settings.put("showEchoTrails", s.showEchoTrails)
        settings.put("reducedEffects", s.reducedEffects)
        root.put("settings", settings)

        val cinematics = JSONArray()
        for (chapter in progress.seenCinematics.sorted()) cinematics.put(chapter)
        root.put("seenCinematics", cinematics)

        return root.toString()
    }

    /**
     * Missing keys fall back to the data-class defaults, so a save written by an older build
     * still loads after a field is added; unknown keys are ignored, so a save written by a
     * newer build still loads after a downgrade. [KEY_VERSION] is recorded to make a future
     * breaking change detectable.
     */
    private fun decode(json: String): GameProgress {
        val root = JSONObject(json)
        val version = root.optInt(KEY_VERSION, SCHEMA_VERSION)
        if (version > SCHEMA_VERSION) {
            Log.w(TAG, "Save schema v$version is newer than v$SCHEMA_VERSION; reading best-effort")
        }

        val levels = HashMap<String, LevelRecord>()
        root.optJSONObject("levels")?.let { levelsJson ->
            val keys = levelsJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val obj = levelsJson.optJSONObject(key) ?: continue
                levels[key] = LevelRecord(
                    levelId = key,
                    completed = obj.optBoolean("completed", false),
                    bestTakes = obj.optInt("bestTakes", 0).coerceAtLeast(0),
                    bestSeconds = finite(obj.optDouble("bestSeconds", 0.0).toFloat(), 0f)
                        .coerceAtLeast(0f),
                    deaths = obj.optInt("deaths", 0).coerceAtLeast(0),
                    bestStars = obj.optInt("bestStars", 0).coerceIn(0, Rating.MAX_STARS),
                )
            }
        }

        val endlessJson = root.optJSONObject("endless")
        val endless = if (endlessJson == null) {
            EndlessRecord()
        } else {
            EndlessRecord(
                bestScore = endlessJson.optInt("bestScore", 0).coerceAtLeast(0),
                bestRooms = endlessJson.optInt("bestRooms", 0).coerceAtLeast(0),
            )
        }

        val challenges = LinkedHashSet<String>()
        root.optJSONArray("challengesCompleted")?.let { arr ->
            for (i in 0 until arr.length()) {
                val id = arr.optString(i, "")
                if (id.isNotEmpty()) challenges.add(id)
            }
        }

        val d = DEFAULT_SETTINGS
        val settingsJson = root.optJSONObject("settings")
        val settings = if (settingsJson == null) {
            d
        } else {
            GameSettings(
                musicEnabled = settingsJson.optBoolean("musicEnabled", d.musicEnabled),
                sfxEnabled = settingsJson.optBoolean("sfxEnabled", d.sfxEnabled),
                hapticsEnabled = settingsJson.optBoolean("hapticsEnabled", d.hapticsEnabled),
                leftHanded = settingsJson.optBoolean("leftHanded", d.leftHanded),
                controlScale = finite(
                    settingsJson.optDouble("controlScale", d.controlScale.toDouble()).toFloat(),
                    d.controlScale,
                ),
                showEchoTrails = settingsJson.optBoolean("showEchoTrails", d.showEchoTrails),
                reducedEffects = settingsJson.optBoolean("reducedEffects", d.reducedEffects),
            )
        }

        val cinematics = LinkedHashSet<Int>()
        root.optJSONArray("seenCinematics")?.let { arr ->
            for (i in 0 until arr.length()) {
                val chapter = arr.optInt(i, Int.MIN_VALUE)
                if (chapter != Int.MIN_VALUE) cinematics.add(chapter)
            }
        }

        return GameProgress(
            levels = levels,
            endless = endless,
            challengesCompleted = challenges,
            settings = settings,
            seenCinematics = cinematics,
        )
    }

    companion object {
        private const val TAG = "ProgressStore"

        /** Named verbatim by the manifest's backup and data-extraction rules. Do not rename. */
        private const val FILE_NAME = "progress.json"
        private const val TMP_NAME = "$FILE_NAME.tmp"
        private const val CORRUPT_NAME = "$FILE_NAME.corrupt"

        private const val SCHEMA_VERSION = 1
        private const val KEY_VERSION = "version"

        /** Long enough to swallow a burst of saves, short enough to survive a task swipe. */
        private const val DEBOUNCE_MS = 250L

        private val DEFAULT_SETTINGS = GameSettings()

        /**
         * Folds [candidate] into [existing] so a record only ever improves. Callers can rely on
         * this: replaying a level badly cannot damage a good result.
         *
         * - `completed` latches — once solved, always solved.
         * - `bestTakes` is the primary score and **lower is better** (fewer Echoes is a better
         *   solve). `0` means "no result yet", so the merge takes the smaller *positive* value
         *   rather than a plain `min`, which would otherwise treat "unset" as a perfect score.
         * - `bestSeconds` follows the same rule: lower is better, `0f` means unset.
         * - `deaths` is monotonic. Since the transform passed to
         *   [updateLevel] receives the live record, `it.copy(deaths = it.deaths + 1)`
         *   accumulates naturally, and a transform that returns a freshly built record cannot
         *   erase the count.
         * - `bestStars` takes the maximum: a worse replay cannot demote an earned rating.
         */
        fun bestOf(existing: LevelRecord, candidate: LevelRecord): LevelRecord = LevelRecord(
            levelId = existing.levelId.ifEmpty { candidate.levelId },
            completed = existing.completed || candidate.completed,
            bestTakes = betterCount(existing.bestTakes, candidate.bestTakes),
            bestSeconds = betterTime(existing.bestSeconds, candidate.bestSeconds),
            deaths = maxOf(existing.deaths, candidate.deaths),
            bestStars = maxOf(existing.bestStars, candidate.bestStars),
        )

        /** Smaller positive wins; 0 (and anything negative) means "no record". */
        private fun betterCount(existing: Int, candidate: Int): Int = when {
            existing <= 0 -> candidate.coerceAtLeast(0)
            candidate <= 0 -> existing
            else -> minOf(existing, candidate)
        }

        /** Faster wins; 0f means "no record". Non-finite input is discarded, not stored. */
        private fun betterTime(existing: Float, candidate: Float): Float {
            val a = finite(existing, 0f).coerceAtLeast(0f)
            val b = finite(candidate, 0f).coerceAtLeast(0f)
            return when {
                a <= 0f -> b
                b <= 0f -> a
                else -> minOf(a, b)
            }
        }

        private fun finite(value: Float, fallback: Float): Float =
            if (value.isFinite()) value else fallback
    }
}
