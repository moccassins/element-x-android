/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.voicemessages.transcript

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Bridge that exposes the voice transcript state created deep inside the event
 * content to the timestamp row rendered around it. Each event row provides its
 * own holder; the content writes to it, the timestamp row reads from it.
 */
@Stable
class TimelineVoiceTranscriptHolder {
    var state: VoiceTranscriptState? by mutableStateOf(null)
}

val LocalTimelineVoiceTranscriptHolder = staticCompositionLocalOf { TimelineVoiceTranscriptHolder() }
