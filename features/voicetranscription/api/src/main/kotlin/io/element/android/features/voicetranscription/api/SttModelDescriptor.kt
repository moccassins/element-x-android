/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.voicetranscription.api

import androidx.compose.runtime.Immutable

/**
 * A single artifact of a [SttModel], downloadable on its own.
 *
 * @param minBytes Size of the remote file at the time of pinning. Used as the
 * total for download progress and as a *minimum* validation floor: a local
 * file smaller than this was truncated or corrupted and is re-fetched. A
 * larger file (e.g. after an upstream model update) still validates.
 */
@Immutable
data class SttModelFile(
    val url: String,
    val localName: String,
    val minBytes: Long,
)

/**
 * Describes the on-disk artifacts of a [SttModel].
 *
 * Artifacts are fetched individually from HuggingFace (the sherpa-onnx GitHub
 * release only ships a `tar.bz2` bundle). Note that for the int8 Whisper
 * exports the *decoder* is the large file, not the encoder.
 *
 * @param model The tier this descriptor describes.
 * @param dirName Sub-directory, under the models root, holding this model's files.
 * @param displaySizeMb Approximate total size, for the settings UI only.
 * @param recommendedRamMb Recommended total device RAM for this model, a
 * conservative heuristic (peak native usage is typically larger than the
 * on-disk size because the runtime keeps encoder, decoder and session state
 * in memory). Used to warn - never block - on low-end devices.
 * @param encoder Int8 encoder ONNX artifact.
 * @param decoder Int8 decoder ONNX artifact.
 * @param tokens Tokens artifact.
 */
@Immutable
data class SttModelDescriptor(
    val model: SttModel,
    val dirName: String,
    val displaySizeMb: Int,
    val recommendedRamMb: Int,
    val encoder: SttModelFile,
    val decoder: SttModelFile,
    val tokens: SttModelFile,
) {
    /** Sum of all artifact sizes — the total used for download progress. */
    val totalBytes: Long
        get() = encoder.minBytes + decoder.minBytes + tokens.minBytes
}

object SttModels {
    /**
     * Multilingual Whisper models, mirrored on HuggingFace by the sherpa-onnx
     * maintainers. Multilingual (non `.en`) variants are used so the same
     * model handles English and German (and more) voice messages.
     */
    val all: List<SttModelDescriptor> = listOf(
        descriptor(
            SttModel.TINY,
            displaySizeMb = 104,
            recommendedRamMb = 2_048,
            encoderBytes = 12_937_772L,
            decoderBytes = 89_855_401L,
        ),
        descriptor(
            SttModel.BASE,
            displaySizeMb = 161,
            recommendedRamMb = 2_560,
            encoderBytes = 29_120_534L,
            decoderBytes = 130_672_026L,
        ),
        descriptor(
            SttModel.SMALL,
            displaySizeMb = 375,
            recommendedRamMb = 4_096,
            encoderBytes = 112_442_483L,
            decoderBytes = 262_226_114L,
        ),
    )

    fun forModel(model: SttModel): SttModelDescriptor = all.first { it.model == model }

    private const val TOKENS_BYTES = 816_730L
    private const val HF_BASE = "https://huggingface.co/csukuangfj"

    private fun descriptor(
        model: SttModel,
        displaySizeMb: Int,
        recommendedRamMb: Int,
        encoderBytes: Long,
        decoderBytes: Long,
    ): SttModelDescriptor {
        val repo = "sherpa-onnx-whisper-${model.id}"
        val prefix = "$HF_BASE/$repo/resolve/main"
        return SttModelDescriptor(
            model = model,
            dirName = repo,
            displaySizeMb = displaySizeMb,
            recommendedRamMb = recommendedRamMb,
            encoder = SttModelFile("$prefix/${model.id}-encoder.int8.onnx", "${model.id}-encoder.int8.onnx", encoderBytes),
            decoder = SttModelFile("$prefix/${model.id}-decoder.int8.onnx", "${model.id}-decoder.int8.onnx", decoderBytes),
            tokens = SttModelFile("$prefix/${model.id}-tokens.txt", "${model.id}-tokens.txt", TOKENS_BYTES),
        )
    }
}
