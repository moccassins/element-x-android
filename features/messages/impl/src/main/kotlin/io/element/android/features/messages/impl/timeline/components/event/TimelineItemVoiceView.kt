/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components.event

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemVoiceContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemVoiceContentPreviewParam
import io.element.android.features.messages.impl.voicemessages.transcript.VoiceTranscriptEvent
import io.element.android.features.messages.impl.voicemessages.transcript.VoiceTranscriptState
import io.element.android.features.messages.impl.voicemessages.transcript.aVoiceTranscriptState
import io.element.android.libraries.designsystem.atomic.atoms.PlaybackSpeedButton
import io.element.android.libraries.designsystem.components.media.WaveformPlaybackView
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.ui.media.contentvalidation.ContentValidationValue
import io.element.android.libraries.ui.common.layout.ContentAvoidingLayoutData
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.libraries.ui.utils.a11y.isTalkbackActive
import io.element.android.libraries.voiceplayer.api.VoiceMessageEvent
import io.element.android.libraries.voiceplayer.api.VoiceMessageState
import io.element.android.libraries.voiceplayer.api.VoiceMessageStatePreviewParam
import kotlinx.coroutines.delay

@Composable
fun TimelineItemVoiceView(
    state: VoiceMessageState,
    content: TimelineItemVoiceContent,
    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit,
    contentValidationValue: ContentValidationValue,
    modifier: Modifier = Modifier,
    voiceTranscriptState: VoiceTranscriptState? = null,
) {
    fun playPause() {
        state.eventSink(VoiceMessageEvent.PlayPause)
    }

    val a11y = stringResource(CommonStrings.common_voice_message)
    val talkbackActive = isTalkbackActive()
    // Persistent, Telegram-style: starts the transcription and re-runs it once a
    // transcript exists. Only hidden for TalkBack users (the transcript box offers
    // a text link instead, since this row clears its children's semantics) and
    // while no model is available. Disabled while any transcription is running.
    val showTranscribeButton = voiceTranscriptState != null &&
        !talkbackActive &&
        voiceTranscriptState.visible &&
        voiceTranscriptState.canTranscribe
    val a11yActionLabel = stringResource(
        when (state.buttonType) {
            VoiceMessageState.ButtonType.Play -> CommonStrings.a11y_play
            VoiceMessageState.ButtonType.Pause -> CommonStrings.a11y_pause
            VoiceMessageState.ButtonType.Downloading -> CommonStrings.common_downloading
            VoiceMessageState.ButtonType.Retry -> CommonStrings.action_retry
            VoiceMessageState.ButtonType.Disabled -> CommonStrings.error_unknown
        }
    )
    Row(
        modifier = modifier
            .clearAndSetSemantics {
                contentDescription = a11y
                if (state.buttonType == VoiceMessageState.ButtonType.Disabled) {
                    disabled()
                } else if (state.buttonType in listOf(VoiceMessageState.ButtonType.Play, VoiceMessageState.ButtonType.Pause)) {
                    onClick(label = a11yActionLabel) {
                        playPause()
                        true
                    }
                }
            }
            .onSizeChanged {
                onContentLayoutChange(
                    ContentAvoidingLayoutData(
                        contentWidth = it.width,
                        contentHeight = it.height,
                    )
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!talkbackActive) {
            if (contentValidationValue.isValid()) {
                when (state.buttonType) {
                    VoiceMessageState.ButtonType.Play -> PlayButton(onClick = ::playPause)
                    VoiceMessageState.ButtonType.Pause -> PauseButton(onClick = ::playPause)
                    VoiceMessageState.ButtonType.Downloading -> ProgressButton()
                    VoiceMessageState.ButtonType.Retry -> RetryButton(onClick = ::playPause)
                    VoiceMessageState.ButtonType.Disabled -> PlayButton(onClick = {}, enabled = false)
                }
            } else {
                ProgressButton(displayImmediately = true)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            PlaybackSpeedButton(
                speed = state.playbackSpeed,
                onClick = { state.eventSink(VoiceMessageEvent.ChangePlaybackSpeed) },
            )
            Text(
                text = state.time,
                color = ElementTheme.colors.textSecondary,
                style = ElementTheme.typography.fontBodySmMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        WaveformPlaybackView(
            showCursor = state.showCursor,
            playbackProgress = state.progress,
            waveform = content.waveform,
            modifier = Modifier
                .weight(1f)
                .height(34.dp),
            isPlaying = state.isPlaying,
            durationMs = state.durationMs,
            playbackSpeed = state.playbackSpeed,
            seekEnabled = !talkbackActive,
            onSeek = { state.eventSink(VoiceMessageEvent.Seek(it)) },
        )
        if (voiceTranscriptState != null && showTranscribeButton) {
            Spacer(Modifier.width(8.dp))
            TranscribeButton(
                // Tapping again once a transcript exists re-runs it with the current model.
                onClick = {
                    if (voiceTranscriptState.text != null) {
                        voiceTranscriptState.eventSink(VoiceTranscriptEvent.Retranscribe)
                    } else {
                        voiceTranscriptState.eventSink(VoiceTranscriptEvent.Transcribe)
                    }
                },
                enabled = !voiceTranscriptState.isTranscribing,
            )
        }
    }
}

@Composable
private fun PlayButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    CustomIconButton(
        onClick = onClick,
        enabled = enabled,
    ) {
        ControlIcon(
            imageVector = CompoundIcons.PlaySolid(),
            contentDescription = stringResource(id = CommonStrings.a11y_play),
        )
    }
}

@Composable
private fun PauseButton(
    onClick: () -> Unit,
) {
    CustomIconButton(
        onClick = onClick,
    ) {
        ControlIcon(
            imageVector = CompoundIcons.PauseSolid(),
            contentDescription = stringResource(id = CommonStrings.a11y_pause),
        )
    }
}

@Composable
private fun RetryButton(
    onClick: () -> Unit,
) {
    CustomIconButton(
        onClick = onClick,
    ) {
        ControlIcon(
            imageVector = CompoundIcons.Restart(),
            contentDescription = stringResource(id = CommonStrings.action_retry),
        )
    }
}

@Composable
private fun ControlIcon(
    imageVector: ImageVector,
    contentDescription: String?,
) {
    Icon(
        modifier = Modifier.padding(vertical = 10.dp),
        imageVector = imageVector,
        contentDescription = contentDescription,
    )
}

/**
 * Progress button is shown when the voice message is being downloaded.
 *
 * The progress indicator is optimistic and displays a pause button (which
 * indicates the audio is playing) for 2 seconds before revealing the
 * actual progress indicator.
 */
@Composable
private fun ProgressButton(
    displayImmediately: Boolean = false,
) {
    var canDisplay by remember { mutableStateOf(displayImmediately) }
    LaunchedEffect(Unit) {
        delay(2000L)
        canDisplay = true
    }
    CustomIconButton(
        onClick = {},
        enabled = false,
    ) {
        if (canDisplay) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(2.dp)
                    .size(16.dp),
                color = ElementTheme.colors.iconSecondary,
                strokeWidth = 2.dp,
            )
        } else {
            ControlIcon(
                imageVector = CompoundIcons.PauseSolid(),
                contentDescription = stringResource(id = CommonStrings.a11y_pause),
            )
        }
    }
}

@Composable
private fun CustomIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .background(color = ElementTheme.colors.bgCanvasDefault, shape = CircleShape)
            .size(36.dp),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = ElementTheme.colors.iconSecondary,
            disabledContentColor = ElementTheme.colors.iconDisabled,
        ),
        content = content,
    )
}

/**
 * Telegram-style speech-to-text affordance rendered at the end of the player row.
 * Mirrors [PlaybackSpeedButton]'s pill style so it blends into the player and
 * follows theme changes. Starts the transcription; the resulting text is shown
 * by [VoiceTranscriptBox] between the player and the timestamp, progress and
 * attribution in the timestamp row. Disabled while another transcription runs.
 */
@Composable
private fun TranscribeButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val contentColor = if (enabled) ElementTheme.colors.iconSecondary else ElementTheme.colors.iconDisabled
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color = ElementTheme.colors.bgCanvasDefault)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = CompoundIcons.ArrowRight(),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = "A",
            style = ElementTheme.typography.fontBodyXsMedium,
            color = contentColor,
        )
    }
}

open class TimelineItemVoiceViewParametersPreviewParam : PreviewParameterProvider<TimelineItemVoiceViewParameters> {
    private val voiceMessageStateProvider = VoiceMessageStatePreviewParam()
    private val timelineItemVoiceContentProvider = TimelineItemVoiceContentPreviewParam()
    override val values: Sequence<TimelineItemVoiceViewParameters>
        get() = timelineItemVoiceContentProvider.values.flatMap { content ->
            voiceMessageStateProvider.values.map { state ->
                TimelineItemVoiceViewParameters(
                    state = state,
                    content = content,
                )
            }
        }
}

data class TimelineItemVoiceViewParameters(
    val state: VoiceMessageState,
    val content: TimelineItemVoiceContent,
)

@PreviewsDayNight
@Composable
internal fun TimelineItemVoiceViewPreview(
    @PreviewParameter(TimelineItemVoiceViewParametersPreviewParam::class) timelineItemVoiceViewParameters: TimelineItemVoiceViewParameters,
) = ElementPreview {
    TimelineItemVoiceView(
        state = timelineItemVoiceViewParameters.state,
        content = timelineItemVoiceViewParameters.content,
        onContentLayoutChange = {},
        contentValidationValue = ContentValidationValue.Valid,
    )
}

@PreviewsDayNight
@Composable
internal fun TimelineItemVoiceViewUnifiedPreview() = ElementPreview {
    val timelineItemVoiceViewParametersProvider = TimelineItemVoiceViewParametersPreviewParam()
    Column {
        timelineItemVoiceViewParametersProvider.values.forEach {
            TimelineItemVoiceView(
                state = it.state,
                content = it.content,
                onContentLayoutChange = {},
                contentValidationValue = ContentValidationValue.Valid,
            )
        }
    }
}

@PreviewsDayNight
@Composable
internal fun ProgressButtonPreview() = ElementPreview {
    Row {
        ProgressButton(displayImmediately = true)
        ProgressButton(displayImmediately = false)
    }
}

@PreviewsDayNight
@Composable
internal fun TimelineItemVoiceViewWithTranscribeButtonPreview() = ElementPreview {
    TimelineItemVoiceView(
        state = aVoiceMessageState(),
        content = TimelineItemVoiceContentProvider().values.first(),
        onContentLayoutChange = {},
        contentValidationValue = ContentValidationValue.Valid,
        voiceTranscriptState = aVoiceTranscriptState(),
    )
}
