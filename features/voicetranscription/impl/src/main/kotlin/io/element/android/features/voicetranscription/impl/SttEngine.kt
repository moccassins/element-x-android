/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.voicetranscription.impl

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import io.element.android.features.voicetranscription.api.SttModel
import io.element.android.features.voicetranscription.api.SttModelDescriptor
import timber.log.Timber
import java.io.File

/**
 * Thin, stateful wrapper around the sherpa-onnx [OfflineRecognizer]. The
 * native engine is not thread-safe, so every method must be called while
 * holding the owner's native lock. At most one model is resident at a time.
 */
class SttEngine {
    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var loadedModel: SttModel? = null
    @Volatile private var loadedLanguage: String? = null

    fun isLoaded(model: SttModel, language: String): Boolean =
        loadedModel == model && recognizer != null && loadedLanguage == language

    fun isModelLoaded(model: SttModel): Boolean = loadedModel == model && recognizer != null

    /** Loads [descriptor], releasing any previously resident model. */
    fun load(descriptor: SttModelDescriptor, modelDir: File, language: String) {
        if (isLoaded(descriptor.model, language)) return
        release()
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = file(modelDir, descriptor.encoder.localName),
                    decoder = file(modelDir, descriptor.decoder.localName),
                    language = language,
                    task = TASK_TRANSCRIBE,
                    tailPaddings = TAIL_PADDINGS,
                ),
                tokens = file(modelDir, descriptor.tokens.localName),
                modelType = MODEL_TYPE,
                numThreads = NUM_THREADS,
                debug = false,
            ),
        )
        recognizer = OfflineRecognizer(config = config)
        loadedModel = descriptor.model
        loadedLanguage = language
        Timber.d("STT engine loaded model %s (language %s)", descriptor.model.id, language)
    }

    /**
     * Transcribes [samples] (mono float in `[-1, 1]` at [sampleRate] Hz).
     * Audio longer than 30 seconds is split into chunks (the offline Whisper
     * models only decode 30 s per stream) and the texts are joined.
     * [onProgress] is invoked with `0f..1f` as chunks complete.
     */
    fun transcribe(samples: FloatArray, sampleRate: Int, onProgress: (Float) -> Unit = {}): String {
        val chunkSamples = CHUNK_SECONDS * sampleRate
        if (samples.size <= chunkSamples) {
            val text = decodeChunk(samples, sampleRate)
            onProgress(1f)
            return text
        }
        val totalChunks = (samples.size + chunkSamples - 1) / chunkSamples
        val result = StringBuilder()
        var start = 0
        var completed = 0
        while (start < samples.size) {
            val end = minOf(start + chunkSamples, samples.size)
            val text = decodeChunk(samples.copyOfRange(start, end), sampleRate)
            completed++
            onProgress(completed.toFloat() / totalChunks)
            if (text.isNotEmpty()) {
                if (result.isNotEmpty()) result.append(' ')
                result.append(text)
            }
            start = end
        }
        return result.toString()
    }

    private fun decodeChunk(samples: FloatArray, sampleRate: Int): String {
        val recognizer = recognizer ?: error("STT engine is not loaded")
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    /** Releases the resident model, freeing native memory. */
    fun release() {
        val current = recognizer ?: return
        try {
            current.release()
        } catch (e: Throwable) {
            Timber.w(e, "Failed to release STT recognizer")
        }
        recognizer = null
        loadedModel = null
        loadedLanguage = null
    }

    private fun file(modelDir: File, name: String): String = File(modelDir, name).absolutePath

    internal companion object {
        const val TASK_TRANSCRIBE = "transcribe"
        const val MODEL_TYPE = "whisper"
        const val TAIL_PADDINGS = 1000
        const val NUM_THREADS = 2
        const val CHUNK_SECONDS = 30
        const val FALLBACK_LANGUAGE = "en"

        /** Languages with a token in the multilingual Whisper vocabulary. */
        val SUPPORTED_LANGUAGES = setOf(
            "en",
            "de",
            "fr",
            "es",
            "it",
            "pt",
            "nl",
            "ru",
            "pl",
            "tr",
            "uk",
            "sv",
            "da",
            "fi",
            "cs",
            "el",
            "he",
            "hi",
            "id",
            "ja",
            "ko",
            "zh",
            "ar",
            "hu",
            "ro",
            "th",
            "vi",
            "no",
            "nb",
            "nn",
            "ca",
            "hr",
            "sk",
            "sl",
            "sr",
            "bg",
            "ms",
            "bn",
            "ta",
            "te",
            "ur",
            "fa",
        )

        /** Resolves the recognizer language: hint, then device locale, then English. */
        fun resolveLanguage(hint: String?): String {
            val candidates = listOfNotNull(hint, java.util.Locale.getDefault().language.lowercase())
            return candidates.firstOrNull { it in SUPPORTED_LANGUAGES } ?: FALLBACK_LANGUAGE
        }
    }
}
