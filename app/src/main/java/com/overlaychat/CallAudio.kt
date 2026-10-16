package com.overlaychat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Sesli arama motoru: 16 kHz mono 16-bit PCM, 40 ms'lik (640 örnek) çerçeveler.
 * Mikrofon -> onFrame(ByteArray 1280 bayt); gelen çerçeveler feed() ile verilir, karıştırılıp çalınır.
 */
class CallAudio(private val ctx: Context, private val onFrame: (ByteArray) -> Unit) {

    companion object {
        const val RATE = 16000
        const val FRAME = 640            // örnek sayısı (40 ms)
        const val MAX_QUEUE = 6
    }

    private class Jit {
        val q = ArrayDeque<ShortArray>()
        var primed = false
    }

    @Volatile private var running = false
    private val jits = ConcurrentHashMap<Int, Jit>()
    private var recThread: Thread? = null
    private var playThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        playThread = Thread { playLoop() }.also { it.isDaemon = true; it.start() }
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            recThread = Thread { recLoop() }.also { it.isDaemon = true; it.start() }
        }
    }

    fun stop() {
        running = false
        jits.clear()
    }

    fun feed(id: Int, bytes: ByteArray) {
        if (!running || bytes.size != FRAME * 2) return
        val shorts = ShortArray(FRAME)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        val j = jits.getOrPut(id) { Jit() }
        synchronized(j) {
            j.q.addLast(shorts)
            while (j.q.size > MAX_QUEUE) j.q.pollFirst()
        }
    }

    private fun recLoop() {
        var rec: AudioRecord? = null
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        try {
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, FRAME * 2 * 4)
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                ChatService.sysLine("Mikrofon başlatılamadı")
                return
            }
            try {
                if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true }
                if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true }
            } catch (e: Exception) {
            }
            rec.startRecording()
            val buf = ByteArray(FRAME * 2)
            var off = 0
            while (running) {
                val n = rec.read(buf, off, buf.size - off)
                if (n < 0) break
                off += n
                if (off >= buf.size) {
                    onFrame(buf.copyOf())
                    off = 0
                }
            }
        } catch (e: Exception) {
        } finally {
            try { aec?.release() } catch (e: Exception) {}
            try { ns?.release() } catch (e: Exception) {}
            try { rec?.stop() } catch (e: Exception) {}
            try { rec?.release() } catch (e: Exception) {}
        }
    }

    private fun playLoop() {
        var track: AudioTrack? = null
        try {
            val minOut = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minOut, FRAME * 2 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            val acc = IntArray(FRAME)
            val out = ShortArray(FRAME)
            while (running) {
                java.util.Arrays.fill(acc, 0)
                for (j in jits.values) {
                    var f: ShortArray? = null
                    synchronized(j) {
                        if (!j.primed && j.q.size >= 2) j.primed = true
                        if (j.primed) {
                            f = j.q.pollFirst()
                            if (f == null) j.primed = false
                        }
                    }
                    val fr = f ?: continue
                    for (i in 0 until FRAME) acc[i] += fr[i].toInt()
                }
                for (i in 0 until FRAME) {
                    val v = acc[i]
                    out[i] = (if (v > 32767) 32767 else if (v < -32768) -32768 else v).toShort()
                }
                val w = track.write(out, 0, FRAME)
                if (w < 0) break
            }
        } catch (e: Exception) {
        } finally {
            try { track?.stop() } catch (e: Exception) {}
            try { track?.release() } catch (e: Exception) {}
        }
    }
}
