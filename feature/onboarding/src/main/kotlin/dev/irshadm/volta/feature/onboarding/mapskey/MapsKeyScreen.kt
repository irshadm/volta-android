package dev.irshadm.volta.feature.onboarding.mapskey

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.irshadm.volta.core.designsystem.NoteTone
import dev.irshadm.volta.core.designsystem.VoltaButton
import dev.irshadm.volta.core.designsystem.VoltaButtonStyle
import dev.irshadm.volta.core.designsystem.VoltaColors
import dev.irshadm.volta.core.designsystem.VoltaIcons
import dev.irshadm.volta.core.designsystem.VoltaNote
import dev.irshadm.volta.core.designsystem.VoltaTextButton
import dev.irshadm.volta.core.designsystem.VoltaTheme
import dev.irshadm.volta.core.maps.ApiCheck
import dev.irshadm.volta.core.maps.AppIdentity
import dev.irshadm.volta.core.maps.KeyCheckReport
import dev.irshadm.volta.core.maps.KeyProblem
import dev.irshadm.volta.core.maps.MapsApi

/** Step-by-step guide to creating a key and setting a budget, in the repository's docs. */
const val MAPS_KEY_GUIDE_URL = "https://github.com/irshadm/volta-android/blob/main/docs/GOOGLE-API-KEY.md"

/**
 * The Google key screen. [MapsKeyMode.FirstRun] is step 2 of first run (with "Skip for now");
 * [MapsKeyMode.Settings] shows the saved key and lets you replace or remove it. [onClose] leaves the screen.
 */
@Composable
fun MapsKeyRoute(mode: MapsKeyMode, onClose: () -> Unit = {}, vm: MapsKeyViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val leave = { if (mode == MapsKeyMode.FirstRun) vm.finishFirstRun() else onClose() }
    MapsKeyScreen(
        mode = mode,
        state = state,
        identity = vm.identity,
        onInputChange = vm::onInputChange,
        onCheck = vm::check,
        onDone = leave,
        onSkip = leave,
        onRemove = vm::removeKey,
    )
}

@Composable
fun MapsKeyScreen(
    mode: MapsKeyMode,
    state: MapsKeyUiState,
    identity: AppIdentity,
    onInputChange: (String) -> Unit,
    onCheck: () -> Unit,
    onDone: () -> Unit,
    onSkip: () -> Unit,
    onRemove: () -> Unit,
) {
    val uri = LocalUriHandler.current
    val report = (state.phase as? KeyPhase.Checked)?.report
    Column(
        Modifier.fillMaxSize().background(VoltaColors.Bg).statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (mode == MapsKeyMode.Settings) Icon(VoltaIcons.Back, "Back", tint = VoltaColors.Text, modifier = Modifier.size(24.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onDone))

        Text(if (state.passed) "Key works" else "Add your Google Maps key", style = MaterialTheme.typography.headlineSmall, color = VoltaColors.Text)
        if (!state.passed) {
            Text(
                "Trip plans, charger search and food stops use Google's Routes, Places and Elevation APIs. Volta has no server and no key of its own, so you bring yours. Google's monthly free usage normally covers one person.",
                style = MaterialTheme.typography.bodyMedium,
                color = VoltaColors.TextSoft,
            )
        }

        if (mode == MapsKeyMode.Settings && state.savedKey != null && report == null) {
            VoltaNote(NoteTone.Success, "Saved on this phone: ${maskKey(state.savedKey)}. Paste a new key below to replace it.", icon = VoltaIcons.Key)
        }

        if (!state.passed) {
            OutlinedTextField(
                value = state.input, onValueChange = onInputChange,
                label = { Text("API key") }, placeholder = { Text("AIza…") }, singleLine = true,
                isError = state.phase == KeyPhase.BadFormat || (report != null && !report.passed),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                supportingText = if (state.phase == KeyPhase.BadFormat) {
                    { Text("Google keys start with AIza and are 39 characters long.") }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { onCheck() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { uri.openUri(MAPS_KEY_GUIDE_URL) }.padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("How to create a key and set a budget", style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline), color = VoltaColors.Gold)
                Icon(VoltaIcons.ExternalLink, null, tint = VoltaColors.Gold, modifier = Modifier.size(14.dp))
            }
        }

        when {
            state.phase == KeyPhase.Checking -> ApiList(report = null)

            report != null -> {
                ApiList(report)
                if (report.passed) {
                    VoltaNote(NoteTone.Success, "Saved on this phone. Change or remove it any time in Settings → Google Maps key.", icon = VoltaIcons.Check)
                } else {
                    report.firstFailure?.let { (api, failure) ->
                        val (title, fix) = failure.problem.explain(api, identity.packageName, identity.sha1Colons)
                        VoltaNote(NoteTone.Error, title = title, body = fix)
                        if (failure.problem == KeyProblem.Other) {
                            Text(failure.googleMessage, style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        when {
            state.passed -> VoltaButton(if (mode == MapsKeyMode.FirstRun) "Open Volta" else "Done", onDone, Modifier.fillMaxWidth())

            else -> VoltaButton(
                text = when {
                    state.phase == KeyPhase.Checking -> "Checking with Google…"
                    report != null -> "Check again"
                    else -> "Check key"
                },
                onClick = onCheck,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canCheck || state.phase == KeyPhase.Checking,
                busy = state.phase == KeyPhase.Checking,
            )
        }
        if (!state.passed && mode == MapsKeyMode.FirstRun) VoltaTextButton("Skip for now", onSkip, Modifier.align(Alignment.CenterHorizontally))
        if (mode == MapsKeyMode.Settings && state.savedKey != null && !state.passed) {
            VoltaButton("Remove key", onRemove, Modifier.fillMaxWidth(), style = VoltaButtonStyle.Secondary)
        }
        Text(
            "Stored encrypted on this phone. Sent only to Google.",
            style = MaterialTheme.typography.bodySmall,
            color = VoltaColors.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** One row per API: its name and how the check went. [report] null means the check is still running. */
@Composable
private fun ApiList(report: KeyCheckReport?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MapsApi.entries.forEachIndexed { i, api ->
            val check = report?.results?.get(api)
            val (label, color) = when (check) {
                ApiCheck.Passed -> "OK" to VoltaColors.Green
                is ApiCheck.Failed -> check.problem.shortLabel() to VoltaColors.Red
                null -> (if (i == 0) "checking…" else "waiting") to VoltaColors.Muted
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(api.label, style = MaterialTheme.typography.bodyMedium, color = VoltaColors.TextSoft)
                Text(label, style = MaterialTheme.typography.labelMedium, color = color)
            }
        }
    }
}

private val previewIdentity = AppIdentity("dev.irshadm.volta.oss", "0A1B2C3D4E5F60718293A4B5C6D7E8F901234567")
private const val PREVIEW_KEY = "AIzaSyB0000000000000000000000000000w4Qk"

@Preview
@Composable
private fun MapsKeyEmptyPreview() = VoltaTheme {
    MapsKeyScreen(MapsKeyMode.FirstRun, MapsKeyUiState(), previewIdentity, {}, {}, {}, {}, {})
}

@Preview
@Composable
private fun MapsKeyFailedPreview() = VoltaTheme {
    val report = KeyCheckReport(mapOf(MapsApi.Places to ApiCheck.Passed, MapsApi.Routes to ApiCheck.Failed(KeyProblem.ApiNotEnabled, "Routes API has not been used in project 123")))
    MapsKeyScreen(MapsKeyMode.FirstRun, MapsKeyUiState(input = PREVIEW_KEY, phase = KeyPhase.Checked(report)), previewIdentity, {}, {}, {}, {}, {})
}
