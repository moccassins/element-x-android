/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.voicemessages.transcript

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.messages.impl.R
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Text

/**
 * Voice-transcript extras rendered inline in the timestamp row, start-aligned:
 * the transcription progress while running, and the model attribution once a
 * transcript exists. Re-transcribing is done via the persistent "→A" button
 * at the end of the player row, so there is no extra action here.
 */
@Composable
fun VoiceTranscriptTimestampExtras(
    modifier: Modifier = Modifier,
) {
    val state = LocalTimelineVoiceTranscriptHolder.current.state ?: return
    if (!state.visible) return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            state.phase is VoiceTranscriptPhase.Preparing -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    color = ElementTheme.colors.iconSecondary,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(6.dp))
                val label = when {
                    state.downloadProgress != null -> stringResource(R.string.screen_room_voice_transcript_preparing_model) +
                        " " + (state.downloadProgress * 100).toInt() + "%"
                    state.transcribeProgress != null -> stringResource(R.string.screen_room_voice_transcript_transcribing) +
                        " " + (state.transcribeProgress * 100).toInt() + "%"
                    else -> stringResource(R.string.screen_room_voice_transcript_transcribing)
                }
                Text(
                    text = label,
                    style = ElementTheme.typography.fontBodyXsRegular,
                    color = ElementTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            state.text != null -> Text(
                text = stringResource(R.string.screen_room_voice_transcript_on_device, state.transcribedModelId ?: ""),
                style = ElementTheme.typography.fontBodyXsRegular,
                color = ElementTheme.colors.textSecondary,
            )
        }
    }
}

@PreviewsDayNight
@Composable
internal fun VoiceTranscriptTimestampExtrasPreview() = ElementPreview {
    CompositionLocalProvider(LocalTimelineVoiceTranscriptHolder provides TimelineVoiceTranscriptHolder().apply {
        state = aVoiceTranscriptState(text = "Hello world", transcribedModelId = "small")
    }) {
        Row {
            VoiceTranscriptTimestampExtras()
            Text("12:34")
        }
    }
}
