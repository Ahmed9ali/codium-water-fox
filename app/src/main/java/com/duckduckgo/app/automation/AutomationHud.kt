package com.duckduckgo.app.automation

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import com.duckduckgo.common.ui.compose.theme.DuckDuckGoTheme
import kotlin.math.roundToInt

@Composable
internal fun BrowserTabAutomationHud() {
    if (!AutomationEngine.hudVisible) return
    when (AutomationEngine.hudMode) {
        HudMode.Hidden -> Unit
        HudMode.Menu -> HudMenuPanel()
        HudMode.Recording -> HudRecordingPanel()
        HudMode.Running -> HudRunningPanel()
        HudMode.ScriptList -> HudScriptListPanel()
        HudMode.RunSetup -> HudRunSetupPanel()
        HudMode.EditScript -> HudScriptEditPanel()
    }
}

@Composable
private fun HudMenuPanel() {
    val context = LocalContext.current
    val shape = RoundedCornerShape(16.dp)

    Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().clip(shape)
                .background(DuckDuckGoTheme.colors.backgrounds.surface, shape)
                .border(1.5.dp, DuckDuckGoTheme.colors.brand.accentBlue, shape)
                .padding(14.dp),
        ) {
            HudHeaderRow("Automation", "Ready on this page") { AutomationEngine.closeHud() }
            Spacer(Modifier.height(12.dp))
            
            DdgPrimaryButton(
                text = "Create Automation",
                onClick = { AutomationEngine.startRecording() }
            )
            Spacer(Modifier.height(8.dp))
            
            DdgSecondaryButton(
                text = "Run Automation",
                onClick = { AutomationEngine.openScriptList() }
            )
            Spacer(Modifier.height(14.dp))
            
            Text(
                text = "Manage Scripts & Data →",
                color = DuckDuckGoTheme.colors.brand.accentBlue,
                fontFamily = VarelaRound,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable {
                    context.startActivity(Intent(context, AutomationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.padding(8.dp),
            )
        }
    }
}

@Composable
private fun HudRecordingPanel() {
    val shape = RoundedCornerShape(16.dp)
    val phase = AutomationEngine.hudRecordingPhase
    val steps = AutomationEngine.hudRecordedSteps
    val host = AutomationEngine.hudRecordingHost

    Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().clip(shape)
                .background(DuckDuckGoTheme.colors.backgrounds.surface, shape)
                .border(1.5.dp, DuckDuckGoTheme.colors.brand.accentBlue, shape)
                .padding(14.dp),
        ) {
            if (phase == RecordingPhase.Saving) {
                RecordingSavePanel(host, steps.size)
            } else {
                RecordingCapturePanel(steps)
            }
        }
    }
}

@Composable
private fun RecordingCapturePanel(steps: List<RecordedStep>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("● Recording", color = Color(0xFFD32F2F), fontFamily = VarelaRound, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Text("${steps.size} step(s) captured", color = DuckDuckGoTheme.colors.text.secondary, fontFamily = VarelaRound, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text("Tap form fields on the page. Each tap becomes a step.", color = DuckDuckGoTheme.colors.text.secondary, fontFamily = VarelaRound, fontSize = 11.sp)
        Spacer(Modifier.height(12.dp))

        if (steps.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Text("No steps yet — tap a field above.", color = DuckDuckGoTheme.colors.text.secondary, fontFamily = VarelaRound, fontSize = 12.sp)
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                steps.forEach { step -> RecordingStepRow(step) }
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DdgSecondaryButton(
                text = "Cancel",
                onClick = { AutomationEngine.cancelRecording() },
                modifier = Modifier.weight(1f)
            )
            DdgPrimaryButton(
                text = "Stop & Save",
                onClick = { AutomationEngine.stopRecordingAndPromptSave() },
                modifier = Modifier.weight(1f),
                enabled = steps.isNotEmpty()
            )
        }
    }
}

@Composable
private fun RecordingStepRow(step: RecordedStep) {
    var showPicker by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable(enabled = !step.verified) { showPicker = true },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${step.sequenceIndex}. ${step.displayLabel}",
            color = DuckDuckGoTheme.colors.text.primary,
            fontFamily = VarelaRound,
            fontSize = 13.sp,
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
            text = if (step.verified) "✓" else "✗",
            color = if (step.verified) Color(0xFF2E7D32) else Color(0xFFD32F2F),
            fontFamily = VarelaRound,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        
        Text(
            text = "×",
            color = DuckDuckGoTheme.colors.text.secondary,
            fontSize = 16.sp,
            modifier = Modifier.clickable { AutomationEngine.removeRecordedStep(step.id) }.padding(4.dp),
        )
    }

    if (showPicker) FieldTypePickerDialog(stepId = step.id, onDismiss = { showPicker = false })
}

@Composable
private fun FieldTypePickerDialog(stepId: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)
                .clip(RoundedCornerShape(16.dp)).background(DuckDuckGoTheme.colors.backgrounds.surface).padding(16.dp),
        ) {
            Text("Assign field type", color = DuckDuckGoTheme.colors.text.primary, fontFamily = VarelaRound, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                FieldTypeMapper.assignOptions.forEach { opt ->
                    Text(
                        text = opt.label,
                        color = DuckDuckGoTheme.colors.text.primary,
                        fontFamily = VarelaRound,
                        fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clickable {
                            AutomationEngine.assignFieldTypeToStep(stepId, opt.canonical)
                            onDismiss()
                        }.padding(vertical = 10.dp),
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
        Text("Save Automation", color = DuckDuckGoTheme.colors.text.primary, fontFamily = VarelaRound, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("$stepCount step(s) captured for $host", color = DuckDuckGoTheme.colors.text.secondary, fontFamily = VarelaRound, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = nameInput, onValueChange = { nameInput = it },
            label = { Text("Script name", fontFamily = VarelaRound, fontSize = 12.sp) },
            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
        )
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DdgSecondaryButton(
                text = "Back",
                onClick = { AutomationEngine.cancelSaveRecording() },
                modifier = Modifier.weight(1f)
            )
            DdgPrimaryButton(
                text = "Save",
                onClick = { AutomationEngine.saveRecording(nameInput) },
                modifier = Modifier.weight(1f),
                enabled = nameInput.trim().isNotBlank()
            )
        }
    }
}

@Composable
private fun RecordingChip(text: String, compact: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier.clip(shape).background(DuckDuckGoTheme.colors.brand.accentBlue.copy(alpha = 0.14f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 3.dp else 6.dp),
    ) {
        Text(text, color = DuckDuckGoTheme.colors.brand.accentBlue, fontFamily = VarelaRound, fontSize = if (compact) 10.sp else 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HudScriptListPanel() {
    val shape = RoundedCornerShape(16.dp)
    val scripts = AutomationEngine.hudAvailableScripts

    Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().clip(shape).background(DuckDuckGoTheme.colors.backgrounds.surface, shape)
                .border(1.5.dp, DuckDuckGoTheme.colors.brand.accentBlue, shape).padding(14.dp),
        ) {
            HudHeaderRow("Run Automation", "Select a script for this page") { AutomationEngine.closeHud() }
            Spacer(Modifier.height(12.dp))

            if (scripts.isEmpty()) {
                Text("No scripts found for this website. Create one first.", color = DuckDuckGoTheme.colors.text.secondary, fontFamily = VarelaRound, fontSize = 13.sp)
            } else {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp).verticalScroll(rememberScrollState())) {
                    scripts.forEach { script ->
                        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            DdgCard(onClick = { AutomationEngine.openRunSetup(script) }) {
                                Text(script.name, color = DuckDuckGoTheme.colors.text.primary, fontFamily = VarelaRound, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text("${script.steps.size} steps", color = DuckDuckGoTheme.colors.text.secondary, fontFamily = VarelaRound, fontSize = 12.sp)
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Edit Script",
                                    color = DuckDuckGoTheme.colors.brand.accentBlue,
                                    fontFamily = VarelaRound,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { AutomationEngine.openScriptEditor(script) }.padding(4.dp)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            DdgSecondaryButton(text = "Back", onClick = { AutomationEngine.closeHud() })
        }
    }
}

@Composable
private fun HudScriptEditPanel() {
    val shape = RoundedCornerShape(16.dp)
    val script = AutomationEngine.hudEditingScript ?: return

    var showWaitDialog by remember { mutableStateOf(false) }
    var waitInput by remember { mutableStateOf("2") }
    
    var showCustomDialog by remember { mutableStateOf(false) }
    var customSelector by remember { mutableStateOf("") }
    var customValue by remember { mutableStateOf("") }

    // DRAG AND DROP STATE
    var draggedIndex by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Column(modifier = Modifier.fillMaxWidth().clip(shape).background(DuckDuckGoTheme.colors.backgrounds.surface, shape).border(1.5.dp, DuckDuckGoTheme.colors.brand.accentBlue, shape).padding(14.dp)) {
            HudHeaderRow("Edit Script", script.name) { AutomationEngine.openScriptList() }
            Spacer(Modifier.height(12.dp))

            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp).verticalScroll(rememberScrollState())) {
                script.steps.forEachIndexed { index, step ->
                    val isDragging = draggedIndex == index
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .zIndex(if (isDragging) 1f else 0f)
                            .offset { IntOffset(0, if (isDragging) dragOffset.roundToInt() else 0) }
                            .pointerInput(script.steps.size) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { draggedIndex = index; dragOffset = 0f },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffset += dragAmount.y
                                        val threshold = 60.dp.toPx()
                                        if (dragOffset > threshold && index < script.steps.size - 1) {
                                            AutomationEngine.moveStep(step.id, up = false)
                                            draggedIndex = index + 1
                                            dragOffset -= threshold
                                        } else if (dragOffset < -threshold && index > 0) {
                                            AutomationEngine.moveStep(step.id, up = true)
                                            draggedIndex = index - 1
                                            dragOffset += threshold
                                        }
                                    },
                                    onDragEnd = { draggedIndex = -1; dragOffset = 0f },
                                    onDragCancel = { draggedIndex = -1; dragOffset = 0f }
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Drag Handle Icon
                        Text("☰", color = DuckDuckGoTheme.colors.text.secondary, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 8.dp))
                        
                        Text("${step.sequenceIndex}. ${step.displayLabel}", color = DuckDuckGoTheme.colors.text.primary, fontFamily = VarelaRound, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        
                        Text("×", color = DuckDuckGoTheme.colors.text.secondary, fontSize = 16.sp, modifier = Modifier.clickable { AutomationEngine.removeStepFromEditingScript(step.id) }.padding(8.dp))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Add Step:", color = DuckDuckGoTheme.colors.text.secondary, fontFamily = VarelaRound, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RecordingChip("+ Wait") { showWaitDialog = true }
                RecordingChip("+ Clear Data") { AutomationEngine.addStepToEditingScript(RecordedStep(sequenceIndex = 0, kind = StepKind.DeleteCookies, displayLabel = "Clear Site Data")) }
                RecordingChip("+ CAPTCHA") { AutomationEngine.addStepToEditingScript(RecordedStep(sequenceIndex = 0, kind = StepKind.CaptchaWait, displayLabel = "Wait CAPTCHA")) }
                RecordingChip("+ Custom") { showCustomDialog = true }
            }

            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DdgSecondaryButton(text = "Cancel", onClick = { AutomationEngine.openScriptList() }, modifier = Modifier.weight(1f))
                DdgPrimaryButton(text = "Save Changes", onClick = { AutomationEngine.saveEditingScript() }, modifier = Modifier.weight(1f))
            }
        }
    }

    if (showWaitDialog) {
        InAppInputDialog(
            title = "Wait Time (seconds)",
            value = waitInput,
            onValueChange = { waitInput = it.filter { c -> c.isDigit() } },
            confirmLabel = "Add",
            cancelLabel = "Cancel",
            onConfirm = {
                val secs = waitInput.toLongOrNull() ?: 2L
                AutomationEngine.addStepToEditingScript(RecordedStep(sequenceIndex = 0, kind = StepKind.Wait, waitMs = secs * 1000, displayLabel = "Wait ${secs}s"))
                showWaitDialog = false
            },
            onDismiss = { showWaitDialog = false }
        )
    }

    if (showCustomDialog) {
        Dialog(onDismissRequest = { showCustomDialog = false }) {
            Column(modifier = Modifier.fillMaxWidth().background(DuckDuckGoTheme.colors.backgrounds.surface, RoundedCornerShape(16.dp)).padding(16.dp)) {
                Text("Add Custom Function", fontFamily = VarelaRound, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = customSelector, onValueChange = { customSelector = it }, label = { Text("CSS Selector (e.g. #submit)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = customValue, onValueChange = { customValue = it }, label = { Text("Value (blank if click)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DdgSecondaryButton(text = "Cancel", onClick = { showCustomDialog = false }, modifier = Modifier.weight(1f))
                    DdgPrimaryButton(text = "Add", onClick = {
                        if (customSelector.isNotBlank()) {
                            val kind = if (customValue.isBlank()) StepKind.ClickNext else StepKind.Field
                            AutomationEngine.addStepToEditingScript(RecordedStep(
                                sequenceIndex = 0, 
                                kind = kind, 
                                cssSelector = customSelector, 
                                fieldType = "text", 
                                displayLabel = "Custom: $customSelector"
                            ))
                        }
                        showCustomDialog = false
                    }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HudRunSetupPanel() {
    val shape = RoundedCornerShape(16.dp)
    val script = AutomationEngine.hudSelectedScript ?: return
    val groups = VaultRepository.groups.value
    val selectedGroup = AutomationEngine.hudSelectedGroup
    val repeatCount = AutomationEngine.hudRunRepeatCount

    Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Column(modifier = Modifier.fillMaxWidth().clip(shape).background(DuckDuckGoTheme.colors.backgrounds.surface, shape).border(1.5.dp, DuckDuckGoTheme.colors.brand.accentBlue, shape).padding(14.dp)) {
            HudHeaderRow("Setup Run", script.name) { AutomationEngine.openScriptList() }
            Spacer(Modifier.height(12.dp))

            Text("Select Data Group", color = DuckDuckGoTheme.colors.text.primary, fontFamily = VarelaRound, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            if (groups.isEmpty()) {
                Text("No groups available. Create one first.", color = DuckDuckGoTheme.colors.text.secondary, fontFamily = VarelaRound, fontSize = 12.sp)
            } else {
                groups.forEach { group ->
                    Row(modifier = Modifier.fillMaxWidth().clickable { AutomationEngine.updateRunGroup(group.name) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selectedGroup == group.name, onClick = { AutomationEngine.updateRunGroup(group.name) })
                        Text(group.name, fontFamily = VarelaRound, fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Repeat Count", color = DuckDuckGoTheme.colors.text.primary, fontFamily = VarelaRound, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DdgSecondaryButton(text = "-1", onClick = { AutomationEngine.updateRunRepeat(repeatCount - 1) }, modifier = Modifier.width(60.dp))
                Text("$repeatCount", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontFamily = VarelaRound, fontSize = 16.sp)
                DdgSecondaryButton(text = "+1", onClick = { AutomationEngine.updateRunRepeat(repeatCount + 1) }, modifier = Modifier.width(60.dp))
            }

            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DdgSecondaryButton(text = "Back", onClick = { AutomationEngine.openScriptList() }, modifier = Modifier.weight(1f))
                DdgPrimaryButton(text = "Run", onClick = { AutomationEngine.startSelectedScriptRun() }, modifier = Modifier.weight(1f), enabled = selectedGroup.isNotBlank())
            }
        }
    }
}

@Composable
private fun HudRunningPanel() {
    val shape = RoundedCornerShape(16.dp)
    val state = AutomationEngine.hudState
    val completed = AutomationEngine.hudCompleted
    val total = AutomationEngine.hudTotal
    val status = AutomationEngine.hudStatus
    val unusedLeft = VaultRepository.unusedProfileCount(AutomationEngine.hudGroupName)

    Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Column(modifier = Modifier.fillMaxWidth().clip(shape).background(DuckDuckGoTheme.colors.backgrounds.surface, shape).border(1.5.dp, DuckDuckGoTheme.colors.brand.accentBlue, shape).padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Automation · ${AutomationEngine.hudGroupName}", color = DuckDuckGoTheme.colors.text.primary, fontFamily = VarelaRound, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(status.ifBlank { "Running..." }, color = DuckDuckGoTheme.colors.text.secondary, fontFamily = VarelaRound, fontSize = 12.sp)
                }
                DdgStatusBadge("$completed / $total", state != AutomationState.WAITING_CAPTCHA_HUMAN && state != AutomationState.WAITING_DATA_EXHAUSTED)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { if (total <= 0) 0f else (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)), color = DuckDuckGoTheme.colors.brand.accentBlue, trackColor = DuckDuckGoTheme.colors.backgrounds.background)
            Spacer(Modifier.height(6.dp))
            Text("$unusedLeft unused profile(s) remaining", color = DuckDuckGoTheme.colors.text.secondary, fontFamily = VarelaRound, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))

            when (state) {
                AutomationState.WAITING_CAPTCHA_HUMAN -> {
                    DdgPrimaryButton(text = "I Solved the CAPTCHA — Continue", onClick = { AutomationEngine.continueAfterCaptchaResolution() })
                    Spacer(Modifier.height(6.dp))
                    DdgSecondaryButton(text = "Stop Automation", onClick = { AutomationEngine.stopAutomation() })
                }
                AutomationState.WAITING_DATA_EXHAUSTED -> {
                    Text("Group data finished! Repeat with same data or add new?", fontFamily = VarelaRound, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DdgPrimaryButton(text = "Repeat Data", onClick = { AutomationEngine.handleDataExhaustedChoice("repeat_same") }, modifier = Modifier.weight(1f))
                        DdgSecondaryButton(text = "Add Data", onClick = { AutomationEngine.handleDataExhaustedChoice("add_new") }, modifier = Modifier.weight(1f))
                    }
                }
                AutomationState.COMPLETED -> DdgPrimaryButton(text = "Automation Finished", onClick = { AutomationEngine.stopAutomation() })
                else -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state == AutomationState.PAUSED) DdgPrimaryButton(text = "Resume", onClick = { AutomationEngine.resumeAutomation() }, modifier = Modifier.weight(1f))
                        else DdgSecondaryButton(text = "Pause", onClick = { AutomationEngine.pauseAutomation() }, modifier = Modifier.weight(1f))
                        DdgSecondaryButton(text = "Stop", onClick = { AutomationEngine.stopAutomation() }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun HudHeaderRow(title: String, subtitle: String, onClose: (() -> Unit)?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = DuckDuckGoTheme.colors.text.primary, fontFamily = VarelaRound, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = DuckDuckGoTheme.colors.text.secondary, fontFamily = VarelaRound, fontSize = 12.sp)
        }
        if (onClose != null) {
            Text("✕", color = DuckDuckGoTheme.colors.text.secondary, fontSize = 18.sp, modifier = Modifier.clickable(onClick = onClose).padding(6.dp))
        }
    }
}