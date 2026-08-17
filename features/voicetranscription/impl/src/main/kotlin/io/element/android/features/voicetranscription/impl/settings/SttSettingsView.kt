/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.voicetranscription.impl.settings

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.voicetranscription.api.SttModel
import io.element.android.features.voicetranscription.api.SttModelStatus
import io.element.android.features.voicetranscription.api.SttModels
import io.element.android.features.voicetranscription.impl.R
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.components.list.SwitchListItem
import io.element.android.libraries.designsystem.components.preferences.PreferencePage
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.Text
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** Total device RAM in MB; [Int.MAX_VALUE] when it cannot be determined. */
internal fun totalRamMb(context: Context): Int {
    val activityManager = context.getSystemService(ActivityManager::class.java) ?: return Int.MAX_VALUE
    val info = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(info)
    return (info.totalMem / (1024L * 1024L)).toInt()
}

@Composable
fun SttSettingsView(
    state: SttSettingsState,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    deviceRamMb: Int = Int.MAX_VALUE,
) {
    PreferencePage(
        modifier = modifier,
        onBackClick = onBackClick,
        title = stringResource(R.string.screen_voice_transcription_title),
    ) {
        SwitchListItem(
            headline = stringResource(R.string.screen_voice_transcription_enable_title),
            supportingText = stringResource(R.string.screen_voice_transcription_enable_description),
            value = state.enabled,
            onChange = { state.eventSink(SttSettingsEvent.SetEnabled(it)) },
        )
        SttModelListContent(state = state, deviceRamMb = deviceRamMb)
    }
}

/**
 * The model list, shared between the settings screen and the in-chat picker.
 * Downloading or selecting a model the device may not have enough RAM for
 * (per [SttModels] heuristics) asks for confirmation first.
 */
@Composable
internal fun SttModelListContent(
    state: SttSettingsState,
    modifier: Modifier = Modifier,
    deviceRamMb: Int = Int.MAX_VALUE,
) {
    var pendingEvent by remember { mutableStateOf<SttSettingsEvent?>(null) }
    Column(modifier = modifier.fillMaxWidth()) {
        state.models.forEach { model ->
            val enoughRam = deviceRamMb >= SttModels.forModel(model.model).recommendedRamMb
            // The list is also disabled while a transcription is running, since
            // the resident model must not change mid-run.
            SttModelRow(model = model, enabled = state.enabled && !state.isTranscribing) { event ->
                if (event is SttSettingsEvent.Delete || enoughRam) {
                    state.eventSink(event)
                } else {
                    pendingEvent = event
                }
            }
        }
    }
    pendingEvent?.let { event ->
        ConfirmationDialog(
            title = stringResource(R.string.screen_voice_transcription_ram_warning_title),
            content = stringResource(R.string.screen_voice_transcription_ram_warning_body),
            submitText = stringResource(R.string.action_continue_anyway),
            onSubmitClick = {
                state.eventSink(event)
                pendingEvent = null
            },
            onDismiss = { pendingEvent = null },
        )
    }
}

@Composable
private fun SttModelRow(
    model: SttModelUiState,
    enabled: Boolean,
    eventSink: (SttSettingsEvent) -> Unit,
) {
    ListItem(
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        leadingContent = ListItemContent.RadioButton(selected = model.isActive, enabled = enabled),
        trailingContent = ListItemContent.Custom { StatusActions(model = model, enabled = enabled, eventSink = eventSink) },
        onClick = { eventSink(SttSettingsEvent.Select(model.model)) },
        content = { Text(text = titleFor(model.model)) },
        supportingContent = {
            Text(
                text = descriptionFor(model.model) + " · " + stringResource(R.string.screen_voice_transcription_size_mb, model.displaySizeMb),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
private fun StatusActions(
    model: SttModelUiState,
    enabled: Boolean,
    eventSink: (SttSettingsEvent) -> Unit,
) {
    when (val status = model.status) {
        is SttModelStatus.Downloading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = ElementTheme.colors.iconSecondary,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "${(status.progress * 100).toInt()}%",
                style = ElementTheme.typography.fontBodyXsMedium,
                color = ElementTheme.colors.textSecondary,
            )
        }
        SttModelStatus.Ready -> {
            if (model.isActive) {
                Icon(
                    imageVector = CompoundIcons.Check(),
                    contentDescription = stringResource(R.string.screen_voice_transcription_active),
                    tint = ElementTheme.colors.iconPrimary,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                TextButton(enabled = enabled, onClick = { eventSink(SttSettingsEvent.Delete(model.model)) }) {
                    Text(text = stringResource(R.string.action_delete_model))
                }
            }
        }
        SttModelStatus.NotDownloaded -> TextButton(enabled = enabled, onClick = { eventSink(SttSettingsEvent.Download(model.model)) }) {
            Text(text = stringResource(R.string.action_download_model))
        }
    }
}

@Composable
private fun titleFor(model: SttModel): String = when (model) {
    SttModel.TINY -> stringResource(R.string.screen_voice_transcription_model_tiny)
    SttModel.BASE -> stringResource(R.string.screen_voice_transcription_model_base)
    SttModel.SMALL -> stringResource(R.string.screen_voice_transcription_model_small)
}

@Composable
private fun descriptionFor(model: SttModel): String = when (model) {
    SttModel.TINY -> stringResource(R.string.screen_voice_transcription_model_tiny_desc)
    SttModel.BASE -> stringResource(R.string.screen_voice_transcription_model_base_desc)
    SttModel.SMALL -> stringResource(R.string.screen_voice_transcription_model_small_desc)
}

// ----- Previews -----

class SttSettingsStateProvider : PreviewParameterProvider<SttSettingsState> {
    override val values: Sequence<SttSettingsState> = sequenceOf(
        aSttSettingsState(enabled = false),
        aSttSettingsState(enabled = true),
        aSttSettingsState(enabled = true, active = SttModel.BASE),
        aSttSettingsState(enabled = true, isTranscribing = true),
    )
}

fun aSttSettingsState(
    enabled: Boolean = true,
    active: SttModel? = null,
    isTranscribing: Boolean = false,
): SttSettingsState {
    val models: ImmutableList<SttModelUiState> = SttModel.entries.map { model ->
        SttModelUiState(
            model = model,
            displaySizeMb = SttModels.forModel(model).displaySizeMb,
            status = when {
                model == active -> SttModelStatus.Ready
                model == SttModel.TINY -> SttModelStatus.Downloading(0.42f)
                else -> SttModelStatus.NotDownloaded
            },
            isActive = model == active,
        )
    }.toImmutableList()
    return SttSettingsState(enabled = enabled, isTranscribing = isTranscribing, models = models) { }
}

@PreviewsDayNight
@Composable
internal fun SttSettingsViewPreview(
    @PreviewParameter(SttSettingsStateProvider::class) state: SttSettingsState,
) = ElementPreview {
    SttSettingsView(state = state, onBackClick = {})
}
