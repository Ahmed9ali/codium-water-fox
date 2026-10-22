/*
 * Copyright (c) 2025 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app.automation

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.duckduckgo.common.ui.compose.theme.DuckDuckGoTheme

// ============================================================================
// DISPATCHER
// ============================================================================

/**
 * Top-level overlay entry. Mounted once per tab (lazily) by
 * `AutomationEngine.mountHudOverlayIfNeeded`. Picks the panel to render
 * based on `AutomationEngine.hudMode`.
 */
@Composable
internal fun BrowserTabAutomationHud() {
    if (!AutomationEngine.hudVisible) return
    when (AutomationEngine.hudMode) {
        HudMode.Hidden -> Unit
        HudMode.Menu -> HudMenuPanel()
        HudMode.Recording -> HudRecordingPanel()
        HudMode.Running -> HudRunningPanel()
    }
}

// ============================================================================
// MENU PANEL  (spec §3)
// ============================================================================

@Composable
private fun HudMenuPanel() {
    val context = LocalContext.current
    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(DuckDuckGoTheme.colors.backgrounds.surface, shape)
                .border(1.5.dp, DuckDuckGoTheme.colors.brand.accentBlue, shape)
                .padding(14.dp),
        ) {
            HudHeaderRow(
                title = "Automation",
                subtitle = "Ready on this page",
                onClose = { AutomationEngine.closeHud() },
            )

            Spacer(Modifier.height(12.dp))

            DdgPrimaryButton(
                text = "Create Automation",
                onClick = { AutomationEngine.startRecording() },
            )

            Spacer(Modifier.height(8.dp))

            DdgSecondaryButton(
                text = "Run Automation",
                onClick = {
                    // Slice 3 wires this to the script picker + run panel.
                },
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = "Manage Scripts & Data →",
                color = DuckDuckGoTheme.colors.brand.accentBlue,
                fontFamily = VarelaRound,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        context.startActivity(
                            Intent(context, AutomationActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    .padding(vertical = 6.dp),
            )
        }
    }
}

// ============================================================================
// RECORDING PANEL  (spec §4)
// ============================================================================

@Composable
private fun HudRecordingPanel() {
    val shape = RoundedCornerShape(16.dp)
    val phase = AutomationEngine.hudRecordingPhase
    val steps = AutomationEngine.hudRecordedSteps
    val host = AutomationEngine.hudRecordingHost

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(DuckDuckGoTheme.colors.backgrounds.surface, shape)
                .border(1.5.dp, DuckDuckGoTheme.colors.brand.accentBlue, shape)
                .padding(14.dp),
        ) {
            when (phase) {
                RecordingPhase.Saving -> RecordingSavePanel(host = host, stepCount = steps.size)
                RecordingPhase.Capturing -> RecordingCapturePanel(steps = steps)
            }
        }
    }
}

@Composable
private fun RecordingCapturePanel(steps: List<RecordedStep>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "● Recording",
                color = Color(0xFFD32F2F),
                fontFamily = VarelaRound,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "${steps.size} step(s) captured",
                color = DuckDuckGoTheme.colors.text.secondary,
                fontFamily = VarelaRound,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Tap form fields on the page. Each tap becomes a step.",
            color = DuckDuckGoTheme.colors.text.secondary,
            fontFamily = VarelaRound,
            fontSize = 11.sp,
        )

        Spacer(Modifier.height(10.dp))

        if (steps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No steps yet — tap a field above.",
                    color = DuckDuckGoTheme.colors.text.secondary,
                    fontFamily = VarelaRound,
                    fontSize = 12.sp,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                steps.forEach { step -> RecordingStepRow(step) }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RecordingChip("+ Wait 2s") { AutomationEngine.addWaitStep(2) }
            RecordingChip("+ CAPTCHA") { AutomationEngine.addCaptchaWaitStep() }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DdgSecondaryButton(
                text = "Cancel",
                modifier = Modifier.weight(1f),
                onClick = { AutomationEngine.cancelRecording() },
            )
            DdgPrimaryButton(
                text = "Stop & Save",
                modifier = Modifier.weight(1f),
                enabled = steps.isNotEmpty(),
                onClick = { AutomationEngine.stopRecordingAndPromptSave() },
            )
        }
    }
}

@Composable
private fun RecordingStepRow(step: RecordedStep) {
    var showPicker by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(enabled = !step.verified) { showPicker = true },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (step.verified) "✓" else "✗",
            color = if (step.verified) Color(0xFF2E7D32) else Color(0xFFD32F2F),
            fontFamily = VarelaRound,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(18.dp),
        )
        Text(
            text = "${step.sequenceIndex}. ${step.displayLabel}",
            color = DuckDuckGoTheme.colors.text.primary,
            fontFamily = VarelaRound,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        if (step.isField && step.fieldType.isNotBlank()) {
            RecordingChip(step.fieldType, compact = true) {}
            Spacer(Modifier.width(6.dp))
        } else if (step.isField && !step.verified) {
            RecordingChip("Assign", compact = true) { showPicker = true }
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = "×",
            color = DuckDuckGoTheme.colors.text.secondary,
            fontSize = 15.sp,
            modifier = Modifier
                .clickable { AutomationEngine.removeRecordedStep(step.id) }
                .padding(4.dp),
        )
    }

    if (showPicker) {
        FieldTypePickerDialog(stepId = step.id, onDismiss = { showPicker = false })
    }
}

@Composable
private fun FieldTypePickerDialog(stepId: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(DuckDuckGoTheme.colors.backgrounds.surface)
                .padding(16.dp),
        ) {
            Text(
                text = "Assign field type",
                color = DuckDuckGoTheme.colors.text.primary,
                fontFamily = VarelaRound,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                FieldTypeMapper.assignOptions.forEach { opt ->
                    Text(
                        text = opt.label,
                        color = DuckDuckGoTheme.colors.text.primary,
                        fontFamily = VarelaRound,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                AutomationEngine.assignFieldTypeToStep(stepId, opt.canonical)
                                onDismiss()
                            }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingSavePanel(host: String, stepCount: Int) {
    var nameInput by remember { mutableStateOf("$host Script") }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Save Automation",
            color = DuckDuckGoTheme.colors.text.primary,
            fontFamily = VarelaRound,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "$stepCount step(s) captured for $host",
            color = DuckDuckGoTheme.colors.text.secondary,
            fontFamily = VarelaRound,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = nameInput,
            onValueChange = { nameInput = it },
            label = {
                Text(
                    text = "Script name",
                    fontFamily = VarelaRound,
                    fontSize = 12.sp,
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DdgSecondaryButton(
                text = "Back",
                modifier = Modifier.weight(1f),
                onClick = { AutomationEngine.cancelSaveRecording() },
            )
            DdgPrimaryButton(
                text = "Save",
                modifier = Modifier.weight(1f),
                enabled = nameInput.trim().isNotBlank(),
                onClick = { AutomationEngine.saveRecording(nameInput) },
            )
        }
    }
}

@Composable
private fun RecordingChip(
    text: String,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(DuckDuckGoTheme.colors.brand.accentBlue.copy(alpha = 0.14f), shape)
            .clickable(onClick = onClick)
            .padding(
                horizontal = if (compact) 8.dp else 10.dp,
                vertical = if (compact) 3.dp else 6.dp,
            ),
    ) {
        Text(
            text = text,
            color = DuckDuckGoTheme.colors.brand.accentBlue,
            fontFamily = VarelaRound,
            fontSize = if (compact) 10.sp else 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ============================================================================
// RUNNING PANEL  (spec §10, §14)
// ============================================================================

@Composable
private fun HudRunningPanel() {
    val state = AutomationEngine.hudState
    val completed = AutomationEngine.hudCompleted
    val total = AutomationEngine.hudTotal
    val status = AutomationEngine.hudStatus
    val groupName = AutomationEngine.hudGroupName
    val unusedLeft = if (groupName.isNotBlank()) VaultRepository.unusedProfileCount(groupName) else 0

    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(DuckDuckGoTheme.colors.backgrounds.surface, shape)
                .border(1.5.dp, DuckDuckGoTheme.colors.brand.accentBlue, shape)
                .padding(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (groupName.isNotBlank()) "Automation · $groupName" else "Browser Automation",
                        color = DuckDuckGoTheme.colors.text.primary,
                        fontFamily = VarelaRound,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = status.ifBlank { "Running..." },
                        color = DuckDuckGoTheme.colors.text.secondary,
                        fontFamily = VarelaRound,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                DdgStatusBadge(
                    text = "$completed / $total",
                    isPositive = state != AutomationState.WAITING_CAPTCHA_HUMAN &&
                        state != AutomationState.WAITING_DATA_EXHAUSTED,
                )
            }

            Spacer(Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = {
                    if (total <= 0) 0f else (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = DuckDuckGoTheme.colors.brand.accentBlue,
                trackColor = DuckDuckGoTheme.colors.backgrounds.background,
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = "$unusedLeft unused profile(s) remaining in group",
                color = DuckDuckGoTheme.colors.text.secondary,
                fontFamily = VarelaRound,
                fontSize = 11.sp,
            )

            Spacer(Modifier.height(10.dp))

            when (state) {
                AutomationState.WAITING_CAPTCHA_HUMAN -> HudRunCaptchaBlock()
                AutomationState.WAITING_DATA_EXHAUSTED -> HudRunDataExhaustedBlock()
                AutomationState.COMPLETED -> HudRunCompletedBlock()
                else -> HudRunControlsBlock(state)
            }
        }
    }
}

@Composable
private fun HudRunCaptchaBlock() {
    DdgPrimaryButton(
        text = "I Solved the CAPTCHA — Continue",
        onClick = { AutomationEngine.continueAfterCaptchaResolution() },
    )
    Spacer(Modifier.height(6.dp))
    DdgSecondaryButton(
        text = "Stop Automation",
        onClick = { AutomationEngine.stopAutomation() },
    )
}

@Composable
private fun HudRunDataExhaustedBlock() {
    Text(
        text = "Group data finished! Repeat with same data or add new data?",
        color = DuckDuckGoTheme.colors.text.primary,
        fontFamily = VarelaRound,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DdgPrimaryButton(
            text = "Repeat Data",
            modifier = Modifier.weight(1f),
            onClick = { AutomationEngine.handleDataExhaustedChoice("repeat_same") },
        )
        DdgSecondaryButton(
            text = "Add Data",
            modifier = Modifier.weight(1f),
            onClick = { AutomationEngine.handleDataExhaustedChoice("add_new") },
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = "Dismiss & Finish",
        color = DuckDuckGoTheme.colors.text.secondary,
        fontFamily = VarelaRound,
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { AutomationEngine.stopAutomation() }
            .padding(4.dp),
    )
}

@Composable
private fun HudRunCompletedBlock() {
    DdgPrimaryButton(
        text = "Automation Finished",
        onClick = { AutomationEngine.stopAutomation() },
    )
}

@Composable
private fun HudRunControlsBlock(state: AutomationState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state == AutomationState.PAUSED) {
            DdgPrimaryButton(
                text = "Resume",
                modifier = Modifier.weight(1f),
                onClick = { AutomationEngine.resumeAutomation() },
            )
        } else {
            DdgSecondaryButton(
                text = "Pause",
                modifier = Modifier.weight(1f),
                onClick = { AutomationEngine.pauseAutomation() },
            )
        }
        DdgSecondaryButton(
            text = "Stop",
            modifier = Modifier.weight(1f),
            onClick = { AutomationEngine.stopAutomation() },
        )
    }
}

// ============================================================================
// SHARED SMALL COMPOSABLES
// ============================================================================

@Composable
private fun HudHeaderRow(
    title: String,
    subtitle: String,
    onClose: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = DuckDuckGoTheme.colors.text.primary,
                fontFamily = VarelaRound,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = DuckDuckGoTheme.colors.text.secondary,
                fontFamily = VarelaRound,
                fontSize = 12.sp,
            )
        }
        if (onClose != null) {
            Text(
                text = "✕",
                color = DuckDuckGoTheme.colors.text.secondary,
                fontSize = 18.sp,
                modifier = Modifier
                    .clickable(onClick = onClose)
                    .padding(6.dp),
            )
        }
    }
}