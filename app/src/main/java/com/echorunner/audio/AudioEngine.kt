package com.echorunner.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.echorunner.core.AudioController
import com.echorunner.core.SoundCue
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Procedural audio: every [SoundCue] is synthesised into a 16-bit mono PCM buffer at
 * [Synth.SAMPLE_RATE] and cached by ordinal for the life of the process. No audio assets ship
 * with the game, and [AudioController] is the seam — a later move to recorded audio replaces
 * [Synth] and nothing else.
 *
 * **Threading contract.** [play] is called from the 60 Hz simulation loop, so it does nothing
 * but copy two primitives into a preallocated ring buffer and signal a worker. It never
 * touches an `AudioTrack`, never synthesises, and allocates nothing.
 *
 * - The *sfx* thread owns the cue cache and the one-shot track pool. Because that cache is
 *   confined to one thread it needs no locking at all. It also uses its idle time to warm the
 *   remaining cues, so the first jump of a session is not the thing that pays for synthesis.
 * - The *ambient* thread owns the streaming pad track and parks when music is off.
 *
 * Every `AudioTrack` call is funnelled through [guarded]. Real devices throw
 * `IllegalStateException` out of the audio stack when a track's output is torn down underneath
 * it — focus loss, a route change, a device suspend — and dropping a sound effect is always
 * preferable to taking the game down with it.
 */
class AudioEngine(context: Context) : AudioController {

    private companion object {
        const val TAG = "AudioEngine"
        const val BYTES_PER_SAMPLE = 2

        /** Enough voices that overlapping cues do not cut each other off in practice. */
        const val VOICE_COUNT = 6

        /**
         * Per-voice buffer length. Sized so a whole cue lands in one non-blocking write; any
         * remainder is topped up by the sfx thread instead of blocking it.
         */
        const val VOICE_BUFFER_SECONDS = 0.35f
        const val DEFAULT_BURST_FRAMES = 256

        /** Depth of the pending-cue ring. A backlog deeper than this means audio is starved. */
        const val QUEUE_CAPACITY = 24

        const val SFX_MASTER_GAIN = 0.85f
        const val AMBIENT_TRACK_GAIN = 0.42f

        /**
         * Loop length of the ambient bed. Every partial and LFO in the pad is an integer
         * multiple of `1 / AMBIENT_LOOP_SECONDS`, which is what makes the seam phase-exact.
         */
        const val AMBIENT_LOOP_SECONDS = 8f
        const val AMBIENT_CROSSFADE_SECONDS = 0.6f
        const val AMBIENT_CHUNK = 2048
        const val AMBIENT_FADE_STEPS = 12
        const val AMBIENT_FADE_STEP_MILLIS = 25L

        /** How often to revisit a voice that still has PCM waiting for buffer space. */
        const val TOP_UP_POLL_NANOS = 4_000_000L

        /** How often to revisit voices that are merely draining, to park them afterwards. */
        const val DRAIN_POLL_NANOS = 400_000_000L

        /**
         * A track left PLAYING while starved keeps the output path awake for no benefit, so
         * park voices that have been silent this long. They restart on the next cue.
         */
        const val VOICE_IDLE_PAUSE_NANOS = 1_500_000_000L

        const val THREAD_JOIN_MILLIS = 120L
    }

    /** One slot in the one-shot pool: a track plus how much of its cue is still unwritten. */
    private class Voice(val track: AudioTrack) {
        /** Non-null while PCM remains to be pushed into the track buffer. */
        var pending: ShortArray? = null
        var offset = 0

        /** Wall clock at which everything already written will have been heard. */
        var finishAtNanos = 0L
        var started = false
        var playing = false
        var closed = false
    }

    // ---- Device-derived sizing --------------------------------------------------------

    /**
     * The device's native output burst. Sizing track buffers to a whole number of bursts is
     * the documented way to avoid needless underruns, and is the one thing the Android
     * [Context] is actually needed for here.
     */
    private val burstFrames: Int = queryBurstFrames(context)
    private val voiceBufferBytes: Int = computeVoiceBufferBytes()

    // ---- State shared across threads -------------------------------------------------

    @Volatile private var sfxEnabled = true
    @Volatile private var musicEnabled = true
    @Volatile private var released = false
    private val releaseGuard = AtomicBoolean(false)

    // ---- Pending one-shot ring (producer: game loop, consumer: sfx thread) ------------

    private val sfxLock = ReentrantLock()
    private val sfxSignal = sfxLock.newCondition()
    private val pendingCue = IntArray(QUEUE_CAPACITY)
    private val pendingVolume = FloatArray(QUEUE_CAPACITY)
    private var pendingHead = 0
    private var pendingTail = 0
    private var pendingCount = 0

    // ---- sfx-thread-confined state (no synchronisation needed) ------------------------

    private val cache = arrayOfNulls<ShortArray>(SoundCue.entries.size)
    private var outCue = 0
    private var outVolume = 1f
    private var streamingPending = false
    private var voicesSounding = false

    // ---- Ambient ---------------------------------------------------------------------

    private val ambientLock = ReentrantLock()
    private val ambientSignal = ambientLock.newCondition()
    private var ambientRequested = false
    private var ambientThread: Thread? = null

    /**
     * Started last on purpose: `Thread.start()` publishes every field written before it, so
     * the worker cannot observe a half-constructed engine.
     */
    private val sfxThread: Thread = Thread(::sfxLoop, "echo-sfx").apply {
        isDaemon = true
        priority = Thread.NORM_PRIORITY + 1
        start()
    }

    // ---- AudioController -------------------------------------------------------------

    /**
     * Enqueue a cue. Returns after two array stores and a condition signal — no synthesis, no
     * `AudioTrack` call, no allocation — because the caller is the fixed-timestep game loop.
     *
     * The lock is held by the sfx thread only long enough to copy a request out (it releases
     * it while waiting), so contention here is a few nanoseconds and never I/O.
     */
    override fun play(cue: SoundCue, volume: Float) {
        if (released || !sfxEnabled || volume <= 0f) return
        sfxLock.withLock {
            if (pendingCount == QUEUE_CAPACITY) {
                // Overflow means the mixer is starved; the newest events matter more than the
                // oldest, so drop the head rather than the incoming cue.
                pendingHead = (pendingHead + 1) % QUEUE_CAPACITY
                pendingCount--
            }
            pendingCue[pendingTail] = cue.ordinal
            pendingVolume[pendingTail] = volume
            pendingTail = (pendingTail + 1) % QUEUE_CAPACITY
            pendingCount++
            sfxSignal.signal()
        }
    }

    override fun startAmbient() {
        if (released) return
        ambientLock.withLock {
            ambientRequested = true
            ensureAmbientThreadLocked()
            ambientSignal.signalAll()
        }
    }

    override fun stopAmbient() {
        ambientLock.withLock {
            ambientRequested = false
            ambientSignal.signalAll()
        }
    }

    override fun setMusicEnabled(enabled: Boolean) {
        musicEnabled = enabled
        if (released) return
        ambientLock.withLock {
            if (enabled) ensureAmbientThreadLocked()
            ambientSignal.signalAll()
        }
    }

    override fun setSfxEnabled(enabled: Boolean) {
        sfxEnabled = enabled
        if (!enabled) {
            // Drop the backlog too, or muting still lets a queued burst through.
            sfxLock.withLock {
                pendingHead = 0
                pendingTail = 0
                pendingCount = 0
            }
        }
    }

    /** Idempotent: the guard makes a second call a no-op rather than a double release. */
    override fun release() {
        if (!releaseGuard.compareAndSet(false, true)) return
        released = true
        sfxLock.withLock {
            pendingHead = 0
            pendingTail = 0
            pendingCount = 0
            sfxSignal.signalAll()
        }
        val ambient = ambientLock.withLock {
            ambientRequested = false
            ambientSignal.signalAll()
            ambientThread
        }
        sfxThread.interrupt()
        ambient?.interrupt()
        // Each worker releases the tracks it owns in its own finally block. Join briefly so a
        // caller tearing down an Activity is not racing a still-open output device.
        joinQuietly(sfxThread)
        joinQuietly(ambient)
    }

    // ---- sfx worker ------------------------------------------------------------------

    private fun sfxLoop() {
        val cues = SoundCue.entries
        val voices = createVoices()
        var roundRobin = 0
        var warmupIndex = 0
        try {
            while (!released) {
                while (dequeueInto()) {
                    if (voices.isEmpty()) continue
                    val ordinal = outCue
                    val data = cache[ordinal] ?: Synth.cue(cues[ordinal]).also { cache[ordinal] = it }
                    val now = System.nanoTime()
                    val voice = pickVoice(voices, roundRobin, now)
                    roundRobin = (roundRobin + 1) % voices.size
                    startVoice(voice, data, outVolume, now)
                }

                maintain(voices)

                // Idle time is warmup time: synthesise one cue per pass so the queue is still
                // checked between cues and an early sound is never stuck behind the whole set.
                if (!streamingPending && warmupIndex < cues.size) {
                    val ordinal = warmupIndex++
                    if (cache[ordinal] == null) cache[ordinal] = Synth.cue(cues[ordinal])
                    continue
                }

                sfxLock.withLock {
                    if (!released && pendingCount == 0) {
                        when {
                            streamingPending -> sfxSignal.awaitNanos(TOP_UP_POLL_NANOS)
                            voicesSounding -> sfxSignal.awaitNanos(DRAIN_POLL_NANOS)
                            else -> sfxSignal.await()
                        }
                    }
                }
            }
        } catch (e: InterruptedException) {
            // release() interrupts us; fall through to teardown.
            Log.d(TAG, "sfx thread interrupted for shutdown")
        } catch (e: RuntimeException) {
            // Never let an audio fault kill the process; the game continues in silence.
            Log.e(TAG, "sfx thread stopped on an unexpected fault", e)
        } finally {
            for (i in voices.indices) releaseVoice(voices[i])
        }
    }

    /**
     * Moves one request out of the ring into [outCue]/[outVolume].
     *
     * @return false when the ring is empty.
     */
    private fun dequeueInto(): Boolean = sfxLock.withLock {
        if (pendingCount == 0) return@withLock false
        outCue = pendingCue[pendingHead]
        outVolume = pendingVolume[pendingHead]
        pendingHead = (pendingHead + 1) % QUEUE_CAPACITY
        pendingCount--
        true
    }

    /**
     * Round-robin from [startAt], preferring a voice that has finished sounding so a new cue
     * does not truncate an audible one. When every voice is busy the one closest to finishing
     * is stolen — with [VOICE_COUNT] voices that only happens under a genuine pile-up.
     */
    private fun pickVoice(voices: Array<Voice>, startAt: Int, nowNanos: Long): Voice {
        var oldest = voices[startAt % voices.size]
        for (k in voices.indices) {
            val voice = voices[(startAt + k) % voices.size]
            if (voice.pending == null && nowNanos >= voice.finishAtNanos) return voice
            if (voice.finishAtNanos < oldest.finishAtNanos) oldest = voice
        }
        return oldest
    }

    private fun startVoice(voice: Voice, data: ShortArray, volume: Float, nowNanos: Long) {
        voice.pending = data
        voice.offset = 0
        voice.finishAtNanos = nowNanos + data.size.toLong() * 1_000_000_000L / Synth.SAMPLE_RATE
        guarded("setVolume") {
            voice.track.setVolume(min(1f, max(0f, volume * SFX_MASTER_GAIN)))
        }
        if (voice.started) {
            // Discard whatever the track still holds, otherwise the new cue is appended behind
            // the tail of the old one instead of replacing it. flush() requires a paused track.
            guarded("pause") { voice.track.pause() }
            guarded("flush") { voice.track.flush() }
        }
        guarded("play") { voice.track.play() }
        voice.started = true
        voice.playing = true
        topUp(voice)
    }

    /** Pushes as much of a voice's remaining PCM as the track will accept without blocking. */
    private fun topUp(voice: Voice) {
        val data = voice.pending ?: return
        while (voice.offset < data.size) {
            val written = guardedWrite(voice.track, data, voice.offset, data.size - voice.offset)
            if (written == 0) return // buffer full: the rest goes in on a later pass
            if (written < 0) {
                // The track rejected the write (route change, uninitialised); abandon the cue.
                voice.pending = null
                voice.finishAtNanos = System.nanoTime()
                return
            }
            voice.offset += written
        }
        voice.pending = null // fully queued; the track drains on its own
    }

    /**
     * Tops up partially written voices and parks long-idle ones. Publishes what remains to do
     * via [streamingPending]/[voicesSounding] so the loop knows how long it may sleep.
     */
    private fun maintain(voices: Array<Voice>) {
        val now = System.nanoTime()
        var streaming = false
        var sounding = false
        for (i in voices.indices) {
            val voice = voices[i]
            if (voice.pending != null) {
                topUp(voice)
                if (voice.pending != null) streaming = true
            }
            if (now < voice.finishAtNanos) {
                sounding = true
            } else if (voice.playing && now - voice.finishAtNanos > VOICE_IDLE_PAUSE_NANOS) {
                guarded("pause idle") { voice.track.pause() }
                guarded("flush idle") { voice.track.flush() }
                voice.playing = false
            }
        }
        streamingPending = streaming
        voicesSounding = sounding
    }

    private fun createVoices(): Array<Voice> {
        val voices = ArrayList<Voice>(VOICE_COUNT)
        for (i in 0 until VOICE_COUNT) {
            val track = createTrack(voiceBufferBytes, AudioAttributes.CONTENT_TYPE_SONIFICATION)
                ?: break // fewer voices, or none, is survivable; a crash is not
            voices.add(Voice(track))
        }
        if (voices.isEmpty()) Log.w(TAG, "no output tracks available; sound effects disabled")
        return voices.toTypedArray()
    }

    private fun releaseVoice(voice: Voice) {
        if (voice.closed) return
        voice.closed = true
        voice.pending = null
        if (voice.started) guarded("stop") { voice.track.stop() }
        guarded("release") { voice.track.release() }
        voice.playing = false
    }

    // ---- ambient worker --------------------------------------------------------------

    /** Must hold [ambientLock]. The thread is created on first need and parks when idle. */
    private fun ensureAmbientThreadLocked() {
        if (released || ambientThread != null) return
        ambientThread = Thread(::ambientLoop, "echo-ambient").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Streams the generated pad in a continuous blocking write, which is what keeps it gapless.
     * The buffer wraps at its loop point and [Synth.ambientPad] built that point to be
     * phase-continuous, so the wrap is inaudible.
     */
    private fun ambientLoop() {
        var pad: ShortArray? = null
        var track: AudioTrack? = null
        var cursor = 0
        try {
            while (!released) {
                val wanted = ambientLock.withLock { ambientRequested && musicEnabled }
                if (!wanted) {
                    val open = track
                    if (open != null) {
                        fadeOutAndRelease(open)
                        track = null
                        cursor = 0
                    }
                    ambientLock.withLock {
                        if (!released && !(ambientRequested && musicEnabled)) ambientSignal.await()
                    }
                    continue
                }

                val buffer: ShortArray
                val cachedPad = pad
                if (cachedPad != null) {
                    buffer = cachedPad
                } else {
                    buffer = Synth.ambientPad(AMBIENT_LOOP_SECONDS, AMBIENT_CROSSFADE_SECONDS)
                    pad = buffer
                }

                val existing = track
                val active: AudioTrack
                if (existing != null) {
                    active = existing
                } else {
                    val created = createTrack(ambientBufferBytes(), AudioAttributes.CONTENT_TYPE_MUSIC)
                    if (created == null) {
                        // No music output on this device or route; stop asking for one.
                        Log.w(TAG, "ambient track unavailable; continuing without music")
                        ambientLock.withLock { ambientRequested = false }
                        continue
                    }
                    guarded("ambient volume") { created.setVolume(AMBIENT_TRACK_GAIN) }
                    guarded("ambient play") { created.play() }
                    track = created
                    active = created
                }

                val chunk = min(AMBIENT_CHUNK, buffer.size - cursor)
                val written = guardedBlockingWrite(active, buffer, cursor, chunk)
                if (written < 0) {
                    releaseTrackQuietly(active)
                    track = null
                    cursor = 0
                    continue
                }
                cursor += written
                if (cursor >= buffer.size) cursor = 0
            }
        } catch (e: InterruptedException) {
            Log.d(TAG, "ambient thread interrupted for shutdown")
        } catch (e: RuntimeException) {
            Log.e(TAG, "ambient thread stopped on an unexpected fault", e)
        } finally {
            track?.let { releaseTrackQuietly(it) }
        }
    }

    /** Ramps the pad down before tearing it out, so stopping music is not a click. */
    private fun fadeOutAndRelease(track: AudioTrack) {
        for (step in AMBIENT_FADE_STEPS downTo 1) {
            val gain = AMBIENT_TRACK_GAIN * step / AMBIENT_FADE_STEPS
            guarded("ambient fade") { track.setVolume(gain) }
            try {
                Thread.sleep(AMBIENT_FADE_STEP_MILLIS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        releaseTrackQuietly(track)
    }

    private fun ambientBufferBytes(): Int {
        val min = AudioTrack.getMinBufferSize(
            Synth.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val wanted = (Synth.SAMPLE_RATE * 0.4f).toInt() * BYTES_PER_SAMPLE
        return alignToBurst(max(wanted, if (min > 0) min else wanted))
    }

    // ---- AudioTrack plumbing ---------------------------------------------------------

    private fun createTrack(bufferBytes: Int, contentType: Int): AudioTrack? = try {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(contentType)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(Synth.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            releaseTrackQuietly(track)
            null
        } else {
            track
        }
    } catch (e: RuntimeException) {
        Log.w(TAG, "could not open an AudioTrack; continuing silently", e)
        null
    }

    private fun releaseTrackQuietly(track: AudioTrack) {
        guarded("stop") { track.stop() }
        guarded("release") { track.release() }
    }

    private fun guardedWrite(track: AudioTrack, data: ShortArray, offset: Int, size: Int): Int =
        try {
            track.write(data, offset, size, AudioTrack.WRITE_NON_BLOCKING)
        } catch (e: RuntimeException) {
            Log.w(TAG, "non-blocking write failed", e)
            -1
        }

    private fun guardedBlockingWrite(track: AudioTrack, data: ShortArray, offset: Int, size: Int): Int =
        try {
            track.write(data, offset, size)
        } catch (e: RuntimeException) {
            Log.w(TAG, "blocking write failed", e)
            -1
        }

    /**
     * Wraps every `AudioTrack` transition. The audio stack throws `IllegalStateException` (and
     * on some OEM builds other runtime exceptions) when a track's output goes away underneath
     * it; a lost sound is acceptable, a crashed game is not.
     */
    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            Log.w(TAG, "AudioTrack.$what failed", e)
        }
    }

    private fun joinQuietly(thread: Thread?) {
        if (thread == null) return
        try {
            thread.join(THREAD_JOIN_MILLIS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun queryBurstFrames(context: Context): Int = try {
        val manager = context.applicationContext
            .getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        manager?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toIntOrNull()
            ?.takeIf { it in 16..4096 }
            ?: DEFAULT_BURST_FRAMES
    } catch (e: RuntimeException) {
        DEFAULT_BURST_FRAMES
    }

    private fun computeVoiceBufferBytes(): Int {
        val min = AudioTrack.getMinBufferSize(
            Synth.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val wanted = (Synth.SAMPLE_RATE * VOICE_BUFFER_SECONDS).toInt() * BYTES_PER_SAMPLE
        return alignToBurst(max(wanted, if (min > 0) min else wanted))
    }

    private fun alignToBurst(bytes: Int): Int {
        val granularity = max(BYTES_PER_SAMPLE, burstFrames * BYTES_PER_SAMPLE)
        return (bytes + granularity - 1) / granularity * granularity
    }
}

/**
 * The synthesis half of the module: pure functions from a [SoundCue] to PCM, plus the ambient
 * pad. Deliberately free of any playback concern so swapping in recorded audio touches only
 * [Synth.cue].
 *
 * Noise sources use a fixed-seed [Random]. Audio is not part of the deterministic simulation,
 * but a fixed seed keeps a cue's timbre identical across launches (and keeps `Math.random` out
 * of the codebase entirely).
 */
private object Synth {

    const val SAMPLE_RATE = 44100

    private const val TWO_PI = (2.0 * PI).toFloat()

    /** ~1.5 ms of fade at both ends of a cue. A buffer that starts or ends off zero pops. */
    private const val DECLICK = 64

    /**
     * Upper limit for the state-variable band-pass centre. The filter is only unconditionally
     * stable while `2·sin(π·fc/SR) + 1/Q < 2`; SR/8 keeps the first term at 0.77.
     */
    private const val MAX_BAND_FC = SAMPLE_RATE / 8f

    /** Root of the Echo dyad: G4. Low enough to sit under the mix, high enough to shimmer. */
    private const val ECHO_ROOT = 392f

    fun cue(cue: SoundCue): ShortArray = when (cue) {
        SoundCue.FOOTSTEP -> footstep()
        SoundCue.JUMP -> jump()
        SoundCue.LAND -> land()
        SoundCue.DASH -> dash()
        SoundCue.SWITCH_ON -> switchClick(600f, 940f, up = true)
        SoundCue.SWITCH_OFF -> switchClick(940f, 600f, up = false)
        SoundCue.PLATE_PRESS -> platePress()
        SoundCue.DOOR_OPEN -> doorSlide(opening = true)
        SoundCue.DOOR_CLOSE -> doorSlide(opening = false)
        SoundCue.ECHO_CREATED -> echoCreated()
        SoundCue.ECHO_EXPIRE -> echoExpire()
        SoundCue.ENEMY_ALERT -> enemyAlert()
        SoundCue.TURRET_FIRE -> turretFire()
        SoundCue.LASER_ON -> laserOn()
        SoundCue.TELEPORT -> teleport()
        SoundCue.DEATH -> death()
        SoundCue.LEVEL_COMPLETE -> levelComplete()
        SoundCue.UI_TAP -> uiBlip(520f, 660f, 0.032f, 2600f, 0.30f)
        SoundCue.UI_BACK -> uiBlip(560f, 400f, 0.040f, 2200f, 0.28f)
        SoundCue.COUNTDOWN_TICK -> countdownTick()
    }

    // ---- Cues ------------------------------------------------------------------------

    /** Fires ~4×/s while running, so it is a short filtered tick that sits under everything. */
    private fun footstep(): ShortArray {
        val b = buffer(0.05f)
        noiseBand(b, Random(101), 2200f, 1500f, 1.1f, 1f) { t -> ad(t, 0.0005f, 0.010f) }
        tone(b, 150f, 120f, 0.5f, { sineW(it) }) { t -> ad(t, 0.001f, 0.018f) }
        lowpass(b, 5200f)
        return finish(b, 0.17f)
    }

    /** Quick upward sweep on a soft triangle, with a little sub for weight. */
    private fun jump(): ShortArray {
        val b = buffer(0.24f)
        tone(b, 300f, 760f, 1f, { triW(it) }) { t -> ad(t, 0.005f, 0.075f) }
        tone(b, 95f, 150f, 0.45f, { sineW(it) }) { t -> ad(t, 0.003f, 0.050f) }
        lowpass(b, 4200f)
        return finish(b, 0.42f)
    }

    /** Low thud plus a bright noise transient — the transient is what sells the impact. */
    private fun land(): ShortArray {
        val b = buffer(0.30f)
        tone(b, 165f, 58f, 1f, { sineW(it) }) { t -> ad(t, 0.004f, 0.100f) }
        tone(b, 82f, 45f, 0.5f, { triW(it) }) { t -> ad(t, 0.004f, 0.140f) }
        noiseBand(b, Random(7), 1100f, 400f, 0.9f, 0.5f) { t -> ad(t, 0.0008f, 0.022f) }
        lowpass(b, 2600f)
        return finish(b, 0.50f)
    }

    /** Descending band-passed noise: the falling band centre is the whoosh. */
    private fun dash(): ShortArray {
        val b = buffer(0.32f)
        noiseBand(b, Random(23), 5400f, 620f, 1.6f, 1f) { t -> ad(t, 0.020f, 0.160f) }
        tone(b, 620f, 180f, 0.25f, { triW(it) }) { t -> ad(t, 0.012f, 0.120f) }
        highpass(b, 180f)
        return finish(b, 0.45f)
    }

    /** Crisp two-tone click. [up] only changes the decay balance; the pitch order is the cue. */
    private fun switchClick(first: Float, second: Float, up: Boolean): ShortArray {
        val b = buffer(0.13f)
        val a = buffer(0.040f)
        tone(a, first, first * 1.06f, 1f, { sqrW(it) }) { t -> ad(t, 0.001f, 0.012f) }
        lowpass(a, 5000f)
        val c = buffer(0.060f)
        tone(c, second, second * 1.06f, 1f, { sqrW(it) }) { t -> ad(t, 0.001f, if (up) 0.018f else 0.024f) }
        lowpass(c, 6000f)
        mixAt(b, a, 0f, 1f)
        mixAt(b, c, 0.035f, 0.9f)
        noiseBand(b, Random(41), 3200f, 2600f, 1.4f, 0.35f) { t -> ad(t, 0.0004f, 0.006f) }
        return finish(b, 0.40f)
    }

    /** A boot on a sprung pad: muffled body, no ring. */
    private fun platePress(): ShortArray {
        val b = buffer(0.22f)
        tone(b, 220f, 190f, 1f, { triW(it) }) { t -> ad(t, 0.012f, 0.060f) }
        tone(b, 110f, 96f, 0.6f, { sineW(it) }) { t -> ad(t, 0.008f, 0.090f) }
        noiseBand(b, Random(59), 520f, 300f, 0.8f, 0.4f) { t -> ad(t, 0.004f, 0.030f) }
        lowpass(b, 1500f)
        return finish(b, 0.40f)
    }

    /** A slab grinding on its rail: band sweep in the travel direction plus a slow ramp. */
    private fun doorSlide(opening: Boolean): ShortArray {
        val dur = 0.90f
        val b = buffer(dur)
        val from = if (opening) 95f else 340f
        val to = if (opening) 340f else 95f
        noiseBand(b, Random(71), from, to, 1.2f, 1f) { t -> asr(t, dur, 0.22f, 0.30f) }
        tone(b, 52f, if (opening) 64f else 44f, 0.7f, { sineW(it) }) { t -> asr(t, dur, 0.25f, 0.35f) }
        tone(b, 104f, if (opening) 128f else 88f, 0.25f, { triW(it) }) { t -> asr(t, dur, 0.30f, 0.35f) }
        tremolo(b, 7f, 0.18f)
        lowpass(b, 900f)
        if (!opening) {
            // Closing lands: a terminal thunk gives the door a definite end.
            val thunk = buffer(0.16f)
            tone(thunk, 150f, 70f, 1f, { sineW(it) }) { t -> ad(t, 0.003f, 0.055f) }
            mixAt(b, thunk, 0.74f, 0.8f)
        }
        return finish(b, 0.40f)
    }

    /**
     * The signature sound. Two detuned copies of a root+fifth dyad beat slowly against each
     * other, everything drifts *up* a fraction of a semitone, and the envelope is a curved
     * swell with a hard cut — which is what makes the ear read it as a recording played
     * backwards rather than a note being struck.
     */
    private fun echoCreated(): ShortArray {
        val dur = 1.10f
        val cut = 0.13f
        val b = buffer(dur)
        for (voice in 0 until 2) {
            val detune = if (voice == 0) 1f else 1.0055f
            val root = ECHO_ROOT * detune
            tone(b, root, root * 1.02f, 0.55f, { sineW(it) }) { t -> swell(t, dur, cut) }
            tone(b, root * 1.5f, root * 1.5f * 1.02f, 0.40f, { sineW(it) }) { t -> swell(t, dur, cut) }
        }
        tone(b, ECHO_ROOT * 2f, ECHO_ROOT * 2.04f, 0.16f, { triW(it) }) { t -> swell(t, dur, cut) }
        noiseBand(b, Random(97), 1100f, 5400f, 2.4f, 0.5f) { t -> swell(t, dur, cut) }
        tremolo(b, 5.5f, 0.16f)
        return finish(b, 0.55f)
    }

    /** The same dyad, forward-decaying and sagging in pitch: the recording letting go. */
    private fun echoExpire(): ShortArray {
        val b = buffer(0.95f)
        for (voice in 0 until 2) {
            val detune = if (voice == 0) 1f else 1.0055f
            val root = ECHO_ROOT * detune
            tone(b, root, root * 0.92f, 0.55f, { sineW(it) }) { t -> ad(t, 0.020f, 0.340f) }
            tone(b, root * 1.5f, root * 1.5f * 0.92f, 0.35f, { sineW(it) }) { t -> ad(t, 0.030f, 0.280f) }
        }
        noiseBand(b, Random(103), 4200f, 700f, 2.2f, 0.4f) { t -> ad(t, 0.010f, 0.300f) }
        tremolo(b, 4.5f, 0.20f)
        return finish(b, 0.42f)
    }

    /** Two rising blips, each sweeping up inside itself so it reads as urgency. */
    private fun enemyAlert(): ShortArray {
        val b = buffer(0.36f)
        val first = buffer(0.10f)
        tone(first, 660f, 760f, 1f, { sqrW(it) }) { t -> ad(t, 0.004f, 0.040f) }
        lowpass(first, 3200f)
        val second = buffer(0.12f)
        tone(second, 980f, 1120f, 1f, { sqrW(it) }) { t -> ad(t, 0.004f, 0.050f) }
        lowpass(second, 3600f)
        mixAt(b, first, 0f, 1f)
        mixAt(b, second, 0.13f, 1f)
        return finish(b, 0.45f)
    }

    /** Dry snap: no tail at all, so rapid fire reads as separate shots. */
    private fun turretFire(): ShortArray {
        val b = buffer(0.15f)
        noiseBand(b, Random(131), 3000f, 1800f, 1.8f, 1f) { t -> ad(t, 0.0004f, 0.016f) }
        tone(b, 190f, 120f, 0.55f, { sqrW(it) }) { t -> ad(t, 0.0006f, 0.024f) }
        highpass(b, 220f)
        return finish(b, 0.40f)
    }

    /** Sustained buzz. The 8 Hz detune beats, the 24 Hz tremolo makes it feel electrified. */
    private fun laserOn(): ShortArray {
        val dur = 0.55f
        val b = buffer(dur)
        tone(b, 1180f, 1180f, 0.60f, { sqrW(it) }) { t -> asr(t, dur, 0.02f, 0.12f) }
        tone(b, 1188f, 1188f, 0.50f, { sawW(it) }) { t -> asr(t, dur, 0.02f, 0.12f) }
        tone(b, 2360f, 2360f, 0.16f, { sqrW(it) }) { t -> asr(t, dur, 0.03f, 0.14f) }
        lowpass(b, 4200f)
        tremolo(b, 24f, 0.28f)
        return finish(b, 0.32f)
    }

    /** Fast rising sweep across more than three octaves, with noise shimmering up behind it. */
    private fun teleport(): ShortArray {
        val b = buffer(0.45f)
        tone(b, 190f, 2400f, 0.80f, { sineW(it) }) { t -> ad(t, 0.008f, 0.140f) }
        tone(b, 285f, 3600f, 0.35f, { triW(it) }) { t -> ad(t, 0.010f, 0.110f) }
        noiseBand(b, Random(151), 700f, 5400f, 2.2f, 0.45f) { t -> ad(t, 0.010f, 0.120f) }
        return finish(b, 0.45f)
    }

    /** Descending fall with a tritone against the root: dissonant on purpose. */
    private fun death(): ShortArray {
        val b = buffer(0.70f)
        tone(b, 330f, 92f, 0.80f, { triW(it) }) { t -> ad(t, 0.004f, 0.260f) }
        tone(b, 466f, 122f, 0.60f, { triW(it) }) { t -> ad(t, 0.004f, 0.220f) }
        tone(b, 165f, 46f, 0.40f, { sineW(it) }) { t -> ad(t, 0.006f, 0.300f) }
        noiseBand(b, Random(179), 1400f, 240f, 1.1f, 0.35f) { t -> ad(t, 0.001f, 0.050f) }
        lowpass(b, 3000f)
        return finish(b, 0.50f)
    }

    /** C major triad plus an octave sparkle, entered in sequence. No noise: this one is clean. */
    private fun levelComplete(): ShortArray {
        val dur = 1.20f
        val b = buffer(dur)
        val notes = floatArrayOf(523.25f, 659.25f, 783.99f, 1046.50f)
        val gains = floatArrayOf(1f, 0.90f, 0.85f, 0.40f)
        val stagger = 0.12f
        for (i in notes.indices) {
            val voice = buffer(dur - i * stagger)
            tone(voice, notes[i], notes[i], 1f, { sineW(it) }) { t -> ad(t, 0.012f, 0.450f) }
            tone(voice, notes[i] * 2f, notes[i] * 2f, 0.18f, { triW(it) }) { t -> ad(t, 0.012f, 0.300f) }
            mixAt(b, voice, i * stagger, gains[i])
        }
        return finish(b, 0.55f)
    }

    /** Muted UI blip; direction of the sweep encodes forward vs back. */
    private fun uiBlip(from: Float, to: Float, tau: Float, cutoff: Float, peak: Float): ShortArray {
        val b = buffer(tau * 2.5f + 0.02f)
        tone(b, from, to, 1f, { sineW(it) }) { t -> ad(t, 0.003f, tau) }
        tone(b, from * 2f, to * 2f, 0.20f, { triW(it) }) { t -> ad(t, 0.002f, tau * 0.6f) }
        lowpass(b, cutoff)
        return finish(b, peak)
    }

    private fun countdownTick(): ShortArray {
        val b = buffer(0.07f)
        noiseBand(b, Random(211), 2400f, 1900f, 1.6f, 1f) { t -> ad(t, 0.0004f, 0.011f) }
        tone(b, 900f, 860f, 0.5f, { sineW(it) }) { t -> ad(t, 0.0008f, 0.020f) }
        return finish(b, 0.35f)
    }

    // ---- Ambient pad -----------------------------------------------------------------

    /**
     * Builds the looping ambient bed: three detuned low partials that beat against each other,
     * breathing under two slow LFOs, over a filtered noise wash.
     *
     * **Seamless by construction, two mechanisms combined.** Every partial and LFO frequency is
     * an integer multiple of `1/loopSeconds`, so sample `i` and sample `i + loopN` sit at
     * identical phase — the tonal layer joins itself exactly. Filtered noise cannot be made
     * periodic that way, so the buffer is generated [fadeSeconds] longer than the loop and its
     * overrun is cross-faded onto the head (the classic tape-splice). Because the tonal layer
     * is bit-identical across that splice, cross-fading it with itself is a no-op and only the
     * noise is actually blended.
     *
     * Deliberately no end-fades here: a de-clicked edge would be the seam.
     */
    fun ambientPad(loopSeconds: Float, fadeSeconds: Float): ShortArray {
        val loopN = (loopSeconds * SAMPLE_RATE).toInt()
        val fadeN = (fadeSeconds * SAMPLE_RATE).toInt().coerceIn(1, loopN / 2)
        val total = loopN + fadeN
        val ext = FloatArray(total)

        // Harmonic numbers of the loop fundamental: 55.000, 55.375 and 82.500 Hz at an 8 s
        // loop. The 0.375 Hz gap between the first two is the slow beat that keeps the drone
        // moving without ever becoming a melody.
        val base = 1.0 / loopSeconds
        val partials = intArrayOf(440, 443, 660)
        val partialGain = doubleArrayOf(0.55, 0.45, 0.26)
        // Double precision for the pad only: 350k samples of accumulated float phase error
        // would smear the very seam this is built to protect.
        for (i in 0 until total) {
            val t = i.toDouble() / SAMPLE_RATE
            val breath = 0.72 +
                0.20 * sin(2.0 * PI * (1 * base) * t) +
                0.08 * sin(2.0 * PI * (3 * base) * t + 1.1)
            var sum = 0.0
            for (p in partials.indices) {
                sum += partialGain[p] * sin(2.0 * PI * (partials[p] * base) * t)
            }
            ext[i] = (sum * breath).toFloat()
        }

        // Air, not hiss: two one-pole passes, then normalised so the mix level does not depend
        // on the filter's arbitrary gain.
        val wash = FloatArray(total)
        val rnd = Random(9001)
        for (i in 0 until total) wash[i] = rnd.nextFloat() * 2f - 1f
        lowpass(wash, 240f)
        lowpass(wash, 240f)
        normalizePeak(wash, 0.30f)
        for (i in 0 until total) {
            val t = i.toDouble() / SAMPLE_RATE
            val slow = 0.5 + 0.5 * sin(2.0 * PI * (2 * base) * t + 0.7)
            ext[i] += (wash[i] * (0.35 + 0.65 * slow)).toFloat()
        }

        for (i in 0 until fadeN) {
            val w = i.toFloat() / fadeN
            ext[i] = ext[loopN + i] * (1f - w) + ext[i] * w
        }

        var peak = 0f
        for (i in 0 until loopN) {
            val a = abs(ext[i])
            if (a > peak) peak = a
        }
        val gain = if (peak > 1e-6f) 0.55f / peak else 0f
        val out = ShortArray(loopN)
        for (i in 0 until loopN) out[i] = toPcm(softClip(ext[i] * gain))
        return out
    }

    // ---- Primitives ------------------------------------------------------------------

    private fun buffer(seconds: Float) =
        FloatArray((seconds * SAMPLE_RATE).toInt().coerceAtLeast(8))

    // Waveforms take a normalised phase in [0,1) and return [-1,1].
    private fun sineW(p: Float) = sin(TWO_PI * p)
    private fun triW(p: Float) = 4f * abs(p - 0.5f) - 1f
    private fun sqrW(p: Float) = if (p < 0.5f) 1f else -1f
    private fun sawW(p: Float) = 2f * p - 1f

    /**
     * Adds a swept oscillator. Frequency moves exponentially from [f0] to [f1] (musical rather
     * than linear) and the *phase is integrated*, which is the whole point: evaluating
     * `wave(f(t)·t)` instead produces a discontinuity every time the frequency changes.
     *
     * @param env envelope in seconds, so decay constants read directly.
     */
    private inline fun tone(
        buf: FloatArray,
        f0: Float,
        f1: Float,
        gain: Float,
        crossinline wave: (Float) -> Float,
        crossinline env: (Float) -> Float,
    ) {
        if (f0 <= 0f) return
        val n = buf.size
        val invSpan = 1f / max(1, n - 1)
        val lnRatio = ln((f1 / f0).coerceAtLeast(1e-4f))
        var phase = 0f
        for (i in 0 until n) {
            val u = i * invSpan
            val f = f0 * exp(lnRatio * u)
            buf[i] += wave(phase) * gain * env(i.toFloat() / SAMPLE_RATE)
            phase += f / SAMPLE_RATE
            if (phase >= 1f) phase -= 1f
        }
    }

    /**
     * Adds band-limited noise whose band centre sweeps [fc0] → [fc1]. A falling sweep is a
     * whoosh, a rising one a shimmer; that sweep is what distinguishes DASH from TELEPORT.
     *
     * Chamberlin state-variable filter: two multiplies per sample and stable while the centre
     * stays under [MAX_BAND_FC]. The states are clamped anyway — a filter that blows up would
     * be a full-scale square wave in the player's ear.
     */
    private inline fun noiseBand(
        buf: FloatArray,
        rnd: Random,
        fc0: Float,
        fc1: Float,
        q: Float,
        gain: Float,
        crossinline env: (Float) -> Float,
    ) {
        val n = buf.size
        val invSpan = 1f / max(1, n - 1)
        val start = fc0.coerceIn(20f, MAX_BAND_FC)
        val end = fc1.coerceIn(20f, MAX_BAND_FC)
        val lnRatio = ln(end / start)
        val damp = (1f / q).coerceIn(0.10f, 1.0f)
        var low = 0f
        var band = 0f
        for (i in 0 until n) {
            val fc = start * exp(lnRatio * (i * invSpan))
            val f = 2f * sin(PI.toFloat() * fc / SAMPLE_RATE)
            val x = rnd.nextFloat() * 2f - 1f
            low += f * band
            val high = x - low - damp * band
            band += f * high
            if (low > 4f) low = 4f else if (low < -4f) low = -4f
            if (band > 4f) band = 4f else if (band < -4f) band = -4f
            buf[i] += band * gain * env(i.toFloat() / SAMPLE_RATE)
        }
    }

    /** One-pole low-pass over the whole buffer; takes the edge off squares and noise. */
    private fun lowpass(buf: FloatArray, fc: Float) {
        val a = 1f - exp(-TWO_PI * fc / SAMPLE_RATE)
        var y = 0f
        for (i in buf.indices) {
            y += a * (buf[i] - y)
            buf[i] = y
        }
    }

    /** One-pole high-pass (signal minus its low-pass); strips mud from percussive cues. */
    private fun highpass(buf: FloatArray, fc: Float) {
        val a = 1f - exp(-TWO_PI * fc / SAMPLE_RATE)
        var y = 0f
        for (i in buf.indices) {
            y += a * (buf[i] - y)
            buf[i] -= y
        }
    }

    /** In-place tremolo: the laser's electrified wobble and the door's rail grind. */
    private fun tremolo(buf: FloatArray, rateHz: Float, depth: Float) {
        for (i in buf.indices) {
            val t = i.toFloat() / SAMPLE_RATE
            buf[i] *= 1f - depth + depth * (0.5f + 0.5f * sin(TWO_PI * rateHz * t))
        }
    }

    /** Mixes [src] into [dst] at [atSeconds]; used by the multi-blip and arpeggiated cues. */
    private fun mixAt(dst: FloatArray, src: FloatArray, atSeconds: Float, gain: Float) {
        val start = (atSeconds * SAMPLE_RATE).toInt()
        var i = 0
        while (i < src.size && start + i < dst.size) {
            if (start + i >= 0) dst[start + i] += src[i] * gain
            i++
        }
    }

    /** Percussive envelope: linear attack into an exponential tail. */
    private fun ad(t: Float, attack: Float, tau: Float): Float =
        if (t < attack) (if (attack <= 0f) 1f else t / attack) else exp(-(t - attack) / tau)

    /** Attack / sustain / release, for cues with a body: the laser buzz, the door slide. */
    private fun asr(t: Float, dur: Float, attack: Float, release: Float): Float = when {
        t < attack -> if (attack <= 0f) 1f else t / attack
        t > dur - release -> ((dur - t) / release).coerceIn(0f, 1f)
        else -> 1f
    }

    /** Curved rise into a hard cut — reads to the ear as a sound played in reverse. */
    private fun swell(t: Float, dur: Float, cut: Float): Float {
        val rise = dur - cut
        if (rise <= 0f) return 1f
        if (t < rise) {
            val u = t / rise
            return u * u
        }
        return ((dur - t) / cut).coerceIn(0f, 1f)
    }

    private fun normalizePeak(buf: FloatArray, peak: Float) {
        var maxAbs = 0f
        for (i in buf.indices) {
            val a = abs(buf[i])
            if (a > maxAbs) maxAbs = a
        }
        val k = if (maxAbs > 1e-6f) peak / maxAbs else 0f
        for (i in buf.indices) buf[i] *= k
    }

    /**
     * Peak-normalises to [peak] (this is how relative cue loudness is controlled — a footstep
     * is quiet because its target is), soft-clips, de-clicks both ends and converts to PCM16.
     */
    private fun finish(buf: FloatArray, peak: Float): ShortArray {
        normalizePeak(buf, peak)
        val n = buf.size
        val fade = min(DECLICK, n / 4)
        val out = ShortArray(n)
        for (i in 0 until n) {
            var v = buf[i]
            if (fade > 0) {
                if (i < fade) v *= i.toFloat() / fade
                val tail = n - 1 - i
                if (tail < fade) v *= tail.toFloat() / fade
            }
            out[i] = toPcm(softClip(v))
        }
        return out
    }

    /** Soft knee above 0.7 so partials that happen to line up bend instead of clipping. */
    private fun softClip(x: Float): Float {
        val a = abs(x)
        if (a <= 0.7f) return x
        val s = if (x > 0f) 1f else -1f
        return s * (0.7f + 0.3f * (1f - exp(-(a - 0.7f) / 0.3f)))
    }

    private fun toPcm(v: Float): Short =
        (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
}
