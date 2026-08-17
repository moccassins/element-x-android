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
 * Lifecycle of a single message's transcript.
 */
@Immutable
sealed interface VoiceTranscriptPhase {
    /** Nothing transcribed yet (the "Transcribe" button is shown). */
    data object Idle : VoiceTranscriptPhase

    /** Busy: downloading the model, decoding audio or transcribing. */
    data object Preparing : VoiceTranscriptPhase

    /** Failed; [cause] is only for logging, the UI shows a localized message. */
    data class Error(val cause: String) : VoiceTranscriptPhase
}
