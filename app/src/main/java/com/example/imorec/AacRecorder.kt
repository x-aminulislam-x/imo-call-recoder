package com.example.imorec

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import kotlin.math.sqrt

/**
 * Captures from the microphone and writes AAC/m4a.
 *
 * Two deliberate choices, both about acoustic capture:
 *
 * 1. Audio source. We never use VOICE_COMMUNICATION. That source enables the
 *    platform acoustic echo canceller, whose entire job is to subtract speaker
 *    output from the mic signal. Here that output is the remote party's voice,
 *    the half we most want to keep. UNPROCESSED and VOICE_RECOGNITION bypass
 *    AEC/AGC on nearly every device, so we prefer those and fall back to MIC.
 *
 * 2. Raw PCM. We read PCM ourselves instead of letting MediaRecorder own the
 *    pipeline, so we can measure signal level continuously. That is the only
 *    way to detect Android silencing our capture in favour of the call app:
 *    the read() calls keep succeeding, they just return zeroes.
 */
class AacRecorder(
    private val outFile: File,
    private val onLevel: (rms: Double) -> Unit
) {
    companion object {
        private const val TAG = "AacRecorder"
        const val SAMPLE_RATE = 44100
        private const val BIT_RATE = 64000

        private val SOURCE_PREFERENCE = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )

        fun sourceName(src: Int): String = when (src) {
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.MIC -> "MIC"
            else -> "source#" + src
        }
    }

    @Volatile
    private var running = false

    @Volatile
    var sourceUsed = -1
        private set

    @Volatile
    var samplesWritten = 0L
        private set

    @Volatile
    var failure: String? = null
        private set

    private var worker: Thread? = null

    fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            failure = "Device reports no usable mono 44.1 kHz capture buffer"
            return false
        }
        val bufSize = minBuf * 4
        val record = openRecord(bufSize)
        if (record == null) {
            failure = "Could not open the microphone (another app may hold it exclusively)"
            return false
        }
        running = true
        worker = Thread({ loop(record, bufSize) }, "AacRecorder").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
        return true
    }

    /** Blocks until the encoder has flushed and the file is playable. */
    fun stop() {
        running = false
        try {
            worker?.join(4000)
        } catch (ignored: InterruptedException) {
        }
        worker = null
    }

    private fun openRecord(bufSize: Int): AudioRecord? {
        for (src in SOURCE_PREFERENCE) {
            try {
                val r = AudioRecord(
                    src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize
                )
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    sourceUsed = src
                    Log.i(TAG, "using audio source " + sourceName(src))
                    return r
                }
                r.release()
            } catch (t: Throwable) {
                Log.w(TAG, "audio source " + sourceName(src) + " unavailable", t)
            }
        }
        return null
    }

    private fun ptsUs(samples: Long): Long = samples * 1_000_000L / SAMPLE_RATE

    private fun loop(record: AudioRecord, bufSize: Int) {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var muxerStarted = false

        try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1)
            fmt.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufSize)

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            record.startRecording()

            val pcm = ByteArray(bufSize)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf == null) {
                            codec.queueInputBuffer(inIdx, 0, 0, ptsUs(samplesWritten), 0)
                        } else {
                            inBuf.clear()
                            if (!running) {
                                codec.queueInputBuffer(
                                    inIdx, 0, 0, ptsUs(samplesWritten),
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                val cap = minOf(inBuf.capacity(), pcm.size)
                                val read = record.read(pcm, 0, cap)
                                if (read > 0) {
                                    onLevel(rms(pcm, read))
                                    inBuf.put(pcm, 0, read)
                                    codec.queueInputBuffer(
                                        inIdx, 0, read, ptsUs(samplesWritten), 0
                                    )
                                    samplesWritten += read / 2
                                } else {
                                    codec.queueInputBuffer(inIdx, 0, 0, ptsUs(samplesWritten), 0)
                                    if (read < 0) {
                                        failure = "Microphone read failed (code " + read + ")"
                                        running = false
                                    }
                                }
                            }
                        }
                    }
                }

                while (true) {
                    val outIdx = codec.dequeueOutputBuffer(info, 0L)
                    if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        continue
                    }
                    if (outIdx < 0) continue

                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted && outBuf != null) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, outBuf, info)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                        break
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "recording loop died", t)
            if (failure == null) failure = t.message ?: t.javaClass.simpleName
        } finally {
            try {
                record.stop()
            } catch (ignored: Throwable) {
            }
            record.release()
            try {
                codec?.stop()
            } catch (ignored: Throwable) {
            }
            codec?.release()
            if (muxerStarted) {
                try {
                    muxer?.stop()
                } catch (ignored: Throwable) {
                }
            }
            try {
                muxer?.release()
            } catch (ignored: Throwable) {
            }
            running = false
        }
    }

    /** Normalised 0..1 RMS of a little-endian 16-bit PCM chunk. */
    private fun rms(buf: ByteArray, len: Int): Double {
        val count = len / 2
        if (count == 0) return 0.0
        var sum = 0.0
        var i = 0
        while (i + 1 < len) {
            val lo = buf[i].toInt() and 0xFF
            val hi = buf[i + 1].toInt()
            val s = ((hi shl 8) or lo).toShort().toInt()
            sum += s.toDouble() * s.toDouble()
            i += 2
        }
        return sqrt(sum / count) / 32768.0
    }
}
