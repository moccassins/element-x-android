/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.voicemessages.transcript

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.messages.impl.R
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.libraries.ui.utils.a11y.isTalkbackActive

/**
 * Transcript text rendered inside the bubble, below the voice player.
 * The transcribe trigger is the "→A" button at the end of the player row.
 */
@Composable
fun VoiceTranscriptBox(
    state: VoiceTranscriptState,
    modifier: Modifier = Modifier,
) {
    if (!state.visible) return
    val transcript = state.text
    when {
        transcript != null -> Text(
            text = transcript,
            style = ElementTheme.typography.fontBodyMdRegular,
            color = ElementTheme.colors.textPrimary,
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
        state.phase is VoiceTranscriptPhase.Preparing -> Unit // Progress is shown while transcribing
        state.phase is VoiceTranscriptPhase.Error -> ErrorRow(state)
        else -> IdleRow(state)
    }
}

@Composable
private fun ErrorRow(state: VoiceTranscriptState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.screen_room_voice_transcript_failed),
            style = ElementTheme.typography.fontBodySmMedium,
            color = ElementTheme.colors.textCriticalPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(CommonStrings.action_retry),
            style = ElementTheme.typography.fontBodySmMedium,
            color = ElementTheme.colors.textActionPrimary,
            modifier = Modifier
                .clickable { state.eventSink(VoiceTranscriptEvent.Transcribe) }
                .padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun IdleRow(state: VoiceTranscriptState) {
    Column {
        // The primary trigger is the transcribe button at the end of the player row,
        // but the voice message row clears its children's semantics, so keep a text
        // link for TalkBack users.
        if (isTalkbackActive()) {
            Text(
                text = stringResource(R.string.action_transcribe),
                style = ElementTheme.typography.fontBodySmMedium,
                color = if (state.canTranscribe) ElementTheme.colors.textActionPrimary else ElementTheme.colors.textDisabled,
                modifier = Modifier
                    .clickable(enabled = state.canTranscribe) { state.eventSink(VoiceTranscriptEvent.Transcribe) }
                    .padding(top = 4.dp),
            )
        }
        if (!state.canTranscribe) {
            Text(
                text = stringResource(R.string.screen_room_voice_transcript_no_model),
                style = ElementTheme.typography.fontBodySmRegular,
                color = ElementTheme.colors.textSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// ----- Previews -----

fun aVoiceTranscriptState(
    visible: Boolean = true,
    phase: VoiceTranscriptPhase = VoiceTranscriptPhase.Idle,
    text: String? = null,
    transcribedModelId: String? = "base",
    downloadProgress: Float? = null,
    transcribeProgress: Float? = null,
    canTranscribe: Boolean = true,
    eventSink: (VoiceTranscriptEvent) -> Unit = {},
) = VoiceTranscriptState(
    visible = visible,
    phase = phase,
    text = text,
    transcribedModelId = transcribedModelId,
    downloadProgress = downloadProgress,
    transcribeProgress = transcribeProgress,
    canTranscribe = canTranscribe,
    eventSink = eventSink,
)

class VoiceTranscriptStateProvider : PreviewParameterProvider<VoiceTranscriptState> {
    override val values: Sequence<VoiceTranscriptState> = sequenceOf(
        aVoiceTranscriptState(),
        aVoiceTranscriptState(text = "Hello world", transcribedModelId = "base"),
        aVoiceTranscriptState(phase = VoiceTranscriptPhase.Preparing, downloadProgress = 0.42f),
        aVoiceTranscriptState(phase = VoiceTranscriptPhase.Preparing, transcribeProgress = 0.5f),
        aVoiceTranscriptState(phase = VoiceTranscriptPhase.Preparing),
        aVoiceTranscriptState(phase = VoiceTranscriptPhase.Error("Transcription failed")),
        aVoiceTranscriptState(canTranscribe = false),
        aVoiceTranscriptState(visible = false),
    )
}

@PreviewsDayNight
@Composable
internal fun VoiceTranscriptBoxPreview(
    @PreviewParameter(VoiceTranscriptStateProvider::class) state: VoiceTranscriptState,
) = ElementPreview {
    VoiceTranscriptBox(state = state)
}
