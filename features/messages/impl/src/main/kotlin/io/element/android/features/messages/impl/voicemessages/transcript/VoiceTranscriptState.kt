/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.voicemessages.transcript

import androidx.compose.runtime.Immutable

/**
 * UI state for the on-device transcript of a single voice message.
 *
 * When [phase] is [VoiceTranscriptPhase.Idle] and [text] is non-null, a
 * previously-computed transcript is displayed. [transcribedModelId] is the id
 * of the model that produced the displayed text — it only changes when a new
 * transcription is started, not when the user switches the active model.
 * [downloadProgress] is non-null (in `0f..1f`) while the active model is
 * downloading, [transcribeProgress] while chunks of this message are being
 * transcribed (one step per 30 s of audio). [isTranscribing] is true while
 * any transcription is running, disabling the transcribe button until the
 * run finishes or fails.
 */
@Immutable
data class VoiceTranscriptState(
    val visible: Boolean,
    val phase: VoiceTranscriptPhase,
    val text: String?,
    val transcribedModelId: String?,
    val downloadProgress: Float?,
    val transcribeProgress: Float?,
    val isTranscribing: Boolean,
    val canTranscribe: Boolean,
    val eventSink: (VoiceTranscriptEvent) -> Unit,
)
