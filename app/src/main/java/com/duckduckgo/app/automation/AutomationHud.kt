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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duckduckgo.common.ui.compose.theme.DuckDuckGoTheme

/**
 * Top-level dispatcher mounted in `AutomationEngine.mountHudOverlayIfNeeded`.
 * Only renders when `hudVisible` is true; picks the correct panel by `hudMode`.
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
// MENU  (spec §3)
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
                onClick = { AutomationEngine.startRecordingScaffold() },
            )

            Spacer(Modifier.height(8.dp))

            DdgSecondaryButton(
                text = "Run Automation",
                onClick = {
                    // Slice 3 fills this in — opens the Run script picker panel.
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
// RECORDING  (Slice 1 = scaffold; Slice 2 replaces this body with a step feed)
// ============================================================================

@Composable
private fun HudRecordingPanel() {
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
                title = "Recording mode",
                subtitle = "Step capture wired up in Slice 2",
                onClose = null, // Not closable via X — Stop is the only exit.
            )

            Spacer(Modifier.height(12.dp))

            DdgSecondaryButton(
                text = "Stop Recording",
                onClick = { AutomationEngine.closeHud() },
            )
        }
    }
}

// ============================================================================
// RUNNING  (existing panel, body unchanged from earlier AutomationEngine.kt)
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
// Shared small composables
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