/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.voicetranscription.impl

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Decodes a decrypted voice attachment (Matrix voice is Opus in an Ogg
 * container) into 16 kHz mono float samples for the Whisper engine, using
 * the platform [MediaExtractor] + [MediaCodec]. Ogg/Opus extraction via
 * [MediaExtractor] requires API 27+; failures are surfaced by the caller.
 */
class SttAudioDecoder {
    /** Mono float samples in the `[-1, 1]` range at [sampleRate] Hz. */
    data class Decoded(val samples: FloatArray, val sampleRate: Int)

    fun decode(source: File): Decoded {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track found in voice attachment")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Audio track has no mime type")
            val rawSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                DEFAULT_OPUS_SAMPLE_RATE
            }
            // Container metadata is untrusted; clamp so a crafted value cannot
            // make the resampler allocate a huge buffer.
            val sourceSampleRate = rawSampleRate.coerceIn(MIN_SAMPLE_RATE, MAX_SAMPLE_RATE)
            val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                1
            }
            val pcmBytes = decodeToPcm(extractor, format, mime)
            val mono = downMixToMono(pcmBytes, channelCount)
            val resampled = resampleLinear(mono, sourceSampleRate, TARGET_SAMPLE_RATE)
            return Decoded(resampled, TARGET_SAMPLE_RATE)
        } finally {
            try {
                extractor.release()
            } catch (e: Exception) {
                Timber.w(e, "Failed to release MediaExtractor")
            }
        }
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun decodeToPcm(extractor: MediaExtractor, format: MediaFormat, mime: String): ByteArray {
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        val output = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        try {
            var sawInputEos = false
            while (true) {
                if (!sawInputEos) {
                    val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
                if (outputIndex >= 0) {
                    val outBuffer = codec.getOutputBuffer(outputIndex)
                    if (outBuffer != null && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        outBuffer.get(bytes)
                        output.write(bytes)
                        if (output.size() > MAX_DECODE_BYTES) break
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            try {
                codec.stop()
            } catch (e: Exception) {
                Timber.w(e, "Failed to stop MediaCodec")
            }
            codec.release()
        }
        return output.toByteArray()
    }

    /** Interleaved 16-bit PCM → mono float samples. */
    private fun downMixToMono(pcm: ByteArray, channelCount: Int): FloatArray {
        if (channelCount <= 1) return bytesToFloats(pcm)
        val frames = pcm.size / (2 * channelCount)
        val mono = FloatArray(frames)
        var src = 0
        for (i in 0 until frames) {
            var sum = 0
            repeat(channelCount) {
                val lo = pcm[src++].toInt() and 0xFF
                val hi = pcm[src++].toInt()
                sum += hi shl 8 or lo
            }
            mono[i] = sum / channelCount / 32_768f
        }
        return mono
    }

    private fun bytesToFloats(pcm: ByteArray): FloatArray {
        val frames = pcm.size / 2
        val out = FloatArray(frames)
        var src = 0
        for (i in 0 until frames) {
            val lo = pcm[src++].toInt() and 0xFF
            val hi = pcm[src++].toInt()
            out[i] = (hi shl 8 or lo) / 32_768f
        }
        return out
    }

    private fun resampleLinear(input: FloatArray, sourceRate: Int, targetRate: Int): FloatArray {
        if (sourceRate == targetRate || input.isEmpty()) return input
        val ratio = sourceRate.toDouble() / targetRate.toDouble()
        val outLength = (input.size / ratio).toInt().coerceIn(1, MAX_SAMPLES)
        val out = FloatArray(outLength)
        for (i in 0 until outLength) {
            val srcPos = i * ratio
            val left = srcPos.toInt()
            val right = (left + 1).coerceAtMost(input.size - 1)
            val frac = srcPos - left
            out[i] = (input[left] * (1 - frac) + input[right] * frac).toFloat()
        }
        return out
    }

    private companion object {
        const val TARGET_SAMPLE_RATE = 16_000
        const val DEFAULT_OPUS_SAMPLE_RATE = 48_000
        const val MIN_SAMPLE_RATE = 8_000
        const val MAX_SAMPLE_RATE = 48_000

        // ~10 minutes of 16 kHz mono float samples.
        const val MAX_SAMPLES = 10 * 60 * TARGET_SAMPLE_RATE

        // ~20 MB of decoded 16-bit PCM.
        const val MAX_DECODE_BYTES = 20 * 1024 * 1024
        const val INPUT_TIMEOUT_US = 10_000L
        const val OUTPUT_TIMEOUT_US = 10_000L
    }
}
