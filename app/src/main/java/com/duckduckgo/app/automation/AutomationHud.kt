package com.duckduckgo.app.automation

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import com.duckduckgo.common.ui.compose.theme.DuckDuckGoTheme
import com.duckduckgo.mobile.android.R.drawable
import com.duckduckgo.app.browser.R
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
private fun HudContainer(
    title: String,
    subtitle: String,
    onClose: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val isTop = AutomationEngine.hudPositionTop

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(align = if (isTop) Alignment.Top else Alignment.Bottom)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = if (isTop) Alignment.TopCenter else Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp) // FIXED: Max height so it doesn't take over screen
                .clip(shape)
                .background(DuckDuckGoTheme.colors.backgrounds.surface, shape)
                .border(1.5.dp, DuckDuckGoTheme.colors.brand.accentBlue, shape)
                .clickable(enabled = true, onClick = {})
                .padding(14.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, color = DuckDuckGoTheme.colors.text.primary, fontFamily = VarelaRound, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(subtitle, color = DuckDuckGoTheme.colors.text.secondary, fontFamily = VarelaRound, fontSize = 12.sp)
                }
                
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(DuckDuckGoTheme.colors.brand.accentBlue.copy(alpha = 0.1f))
                        .clickable { AutomationEngine.toggleHudPosition() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(
                            id = if (isTop) R.drawable.arrow_cool_down_24px else R.drawable.arrow_warm_up_24px
                        ),
                        contentDescription = "Move HUD",
                        tint = DuckDuckGoTheme.colors.brand.accentBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                Spacer(Modifier.width(8.dp))

                if (onClose != null) {
                    IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                        Text("✕", color = DuckDuckGoTheme.colors.text.secondary, fontSize = 18.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun HudMenuPanel() {
    val context = LocalContext.current
    HudContainer(title = "Automation", subtitle = "Ready on this page", onClose = { AutomationEngine.closeHud() }) {
        Spacer(Modifier.height(8.dp))
        DdgPrimaryButton(text = "Create Automation", onClick = { AutomationEngine.startRecording() })
        Spacer(Modifier.height(8.dp))
        DdgSecondaryButton(text = "Run Automation", onClick = { AutomationEngine.openScriptList() })
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Manage Scripts & Data →",
            color = DuckDuckGoTheme.colors.brand.accentBlue,
            fontFamily = VarelaRound, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable {
                context.startActivity(Intent(context, AutomationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.padding(8.dp),
        )
    }
}

@Composable
private fun HudRecordingPanel() {
    val phase = AutomationEngine.hudRecordingPhase
    val steps = AutomationEngine.hudRecordedSteps
    val host = AutomationEngine.hudRecordingHost
    val isPaused = AutomationEngine.hudRecordingPaused

    HudContainer(
        title = if (isPaused) "⏸ Paused" else "● Recording",
        subtitle = if (isPaused) "Website unlocked." else "${steps.size} step(s) captured",
        onClose = null
    ) {
        if (phase == RecordingPhase.Saving) {
            RecordingSavePanel(host, steps.size)
        } else {
            RecordingCapturePanel(steps, isPaused)
        }
    }
}

@Composable
private fun RecordingCapturePanel(steps: List<RecordedStep>, isPaused: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (steps.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text("No steps yet — tap a field above.", color = DuckDuckGoTheme.colors.text.secondary, fontFamily = VarelaRound, fontSize = 12.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 150.dp)
            ) {
                items(steps, key = { it.id }) { step ->
                    RecordingStepRow(step)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DdgSecondaryButton(text = "Cancel", onClick = { AutomationEngine.cancelRecording() }, modifier = Modifier.weight(1f))
            if (isPaused) DdgPrimaryButton(text = "Resume", onClick = { AutomationEngine.resumeRecording() }, modifier = Modifier.weight(1f))
            else DdgSecondaryButton(text = "Pause", onClick = { AutomationEngine.pauseRecording() }, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        DdgPrimaryButton(text = "Stop & Save", onClick = { AutomationEngine.stopRecordingAndPromptSave() }, modifier = Modifier.fillMaxWidth(), enabled = steps.isNotEmpty())
    }
}

@Composable
private fun RecordingStepRow(step: RecordedStep) {
    var showPicker by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable(enabled = !step.verified) { showPicker = true }, verticalAlignment = Alignment.CenterVertically) {
        Text("${step.sequenceIndex}. ${step.displayLabel}", color = DuckDuckGoTheme.colors.text.primary, fontFamily = VarelaRound, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (step.isField && step.fieldType.isNotBlank()) { RecordingChip(step.fieldType, compact = true) {}; Spacer(Modifier.width(6.dp)) }
        else if (step.isField && !step.verified) { RecordingChip("Assign", compact = true) { showPicker = true }; Spacer(Modifier.width(6.dp)) }
        Text(if (step.verified) "✓" else "✗", color = if (step.verified) Color(0xFF2E7D32) else Color(0xFFD32F2F),
            fontFamily = VarelaRound, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp))
        Text("×", color = DuckDuckGoTheme.colors.text.secondary, fontSize = 16.sp, modifier = Modifier.clickable { AutomationEngine.removeRecordedStep(step.id) }.padding(4.dp))
    }
    if (showPicker) FieldTypePickerDialog(stepId = step.id, onDismiss = { showPicker = false })
}

@Composable
private fun FieldTypePickerDialog(stepId: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).clip(RoundedCornerShape(16.dp)).background(DuckDuckGoTheme.colors.backgrounds.surface).padding(16.dp)) {
            Text("Assign field type", color = DuckDuckGoTheme.colors.text.primary, fontFamily = VarelaRound, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                FieldTypeMapper.assignOptions.forEach { opt ->
                    Text(opt.label, color = DuckDuckGoTheme.colors.text.primary, fontFamily = VarelaRound, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clickable { AutomationEngine.assignFieldTypeToStep(stepId, opt.canonical); onDismiss() }.padding(vertical = 10.dp))
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
        OutlinedTextField(value = nameInput, onValueChange = { nameInput = it }, label = { Text("Script name", fontFamily = VarelaRound, fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DdgSecondaryButton(text = "Back", onClick = { AutomationEngine.cancelSaveRecording() }, modifier = Modifier.weight(1f))
            DdgPrimaryButton(text = "Save", onClick = { AutomationEngine.saveRecording(nameInput) }, modifier = Modifier.weight(1f), enabled = nameInput.trim().isNotBlank())
        }
    }
}

@Composable
private fun RecordingChip(text: String, compact: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(modifier = Modifier.clip(shape).background(DuckDuckGoTheme.colors.brand.accentBlue.copy(alpha = 0.14f), shape).clickable(onClick = onClick)
        .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 3.dp else 6.dp)) {
        Text(text, color = DuckDuckGoTheme.colors.brand.accentBlue, fontFamily = VarelaRound, fontSize = if (compact) 10.sp else 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HudScriptListPanel() {
    val scripts = AutomationEngine.hudAvailableScripts
    HudContainer(title = "Run Automation", subtitle = "Select a script for this page", onClose = { AutomationEngine.closeHud() }) {
        if (scripts.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text("No scripts found for this website. Create one first.", color = DuckDuckGoTheme.colors.text.secondary, fontFamily = VarelaRound, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 200.dp)
            ) {
                items(scripts, key = { it.id }) { script ->
                    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        DdgCard(onClick = { AutomationEngine.openRunSetup(script) }) {
                            Text(script.name, color = DuckDuckGoTheme.colors.text.primary, fontFamily = VarelaRound, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("${script.steps.size} steps", color = DuckDuckGoTheme.colors.text.secondary, fontFamily = VarelaRound, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            Text("Edit Script", color = DuckDuckGoTheme.colors.brand.accentBlue, fontFamily = VarelaRound, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { AutomationEngine.openScriptEditor(script) }.padding(4.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        DdgSecondaryButton(text = "Back", onClick = { AutomationEngine.closeHud() })
    }
}

@Composable
private fun HudScriptEditPanel() {
    val script = AutomationEngine.hudEditingScript ?: return
    var showWaitDialog by remember { mutableStateOf(false) }
    var waitInput by remember { mutableStateOf("2") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showCustomDialog by remember { mutableStateOf(false) }
    var customSelector by remember { mutableStateOf("") }
    var customValue by remember { mutableStateOf("") }
    
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    HudContainer(title = "Edit Script", subtitle = script.name, onClose = { AutomationEngine.openScriptList() }) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(max = 200.dp)
        ) {
            itemsIndexed(script.steps, key = { _, step -> step.id }) { index, step ->
                val isDragging = draggedId == step.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .zIndex(if (isDragging) 1f else 0f)
                        .offset { IntOffset(0, if (isDragging) dragOffset.roundToInt() else 0) }
                        .pointerInput(script.steps.size, index) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { draggedId = step.id; dragOffset = 0f },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffset += dragAmount.y
                                },
                                onDragEnd = {
                                    val threshold = 50.dp.toPx()
                                    val itemsMoved = (dragOffset / threshold).roundToInt()
                                    val targetIndex = (index + itemsMoved).coerceIn(0, script.steps.size - 1)
                                    if (targetIndex != index) {
                                        AutomationEngine.moveStepTo(step.id, targetIndex)
                                    }
                                    draggedId = null
                                    dragOffset = 0f
                                },
                                onDragCancel = { draggedId = null; dragOffset = 0f }
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("☰", color = DuckDuckGoTheme.colors.text.secondary, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 8.dp))
                    Text("${step.sequenceIndex}. ${step.displayLabel}", color = DuckDuckGoTheme.colors.text.primary, fontFamily = VarelaRound, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("×", color = DuckDuckGoTheme.colors.text.secondary, fontSize = 16.sp, modifier = Modifier.clickable { AutomationEngine.removeStepFromEditingScript(step.id) }.padding(8.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Add Step:", color = DuckDuckGoTheme.colors.text.secondary, fontFamily = VarelaRound, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RecordingChip("+ Wait") { waitInput = "2"; showWaitDialog = true }
            RecordingChip("+ Delete") { showDeleteDialog = true }
            RecordingChip("+ CAPTCHA") { AutomationEngine.addStepToEditingScript(RecordedStep(sequenceIndex = 0, kind = StepKind.CaptchaWait, displayLabel = "Wait CAPTCHA")) }
            RecordingChip("+ Custom") { customSelector = ""; customValue = ""; showCustomDialog = true }
        }
        Spacer(Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DdgSecondaryButton(text = "Cancel", onClick = { AutomationEngine.openScriptList() }, modifier = Modifier.weight(1f))
            DdgPrimaryButton(text = "Save Changes", onClick = { AutomationEngine.saveEditingScript() }, modifier = Modifier.weight(1f))
        }
    }

    if (showWaitDialog) {
        InAppInputDialog(
            title = "Wait Time (1-300 seconds)", value = waitInput,
            onValueChange = { waitInput = it.filter { c -> c.isDigit() }.take(3) },
            confirmLabel = "Add", cancelLabel = "Cancel",
            onConfirm = {
                val secs = (waitInput.toLongOrNull() ?: 2L).coerceIn(1L, 300L)
                AutomationEngine.addStepToEditingScript(RecordedStep(sequenceIndex = 0, kind = StepKind.Wait, waitMs = secs * 1000, displayLabel = "Wait ${secs}s"))
                showWaitDialog = false
            }, onDismiss = { showWaitDialog = false }
        )
    }

    if (showDeleteDialog) {
        Dialog(onDismissRequest = { showDeleteDialog = false }) {
            Column(modifier = Modifier.fillMaxWidth().background(DuckDuckGoTheme.colors.backgrounds.surface, RoundedCornerShape(16.dp)).padding(16.dp)) {
                Text("Delete Site Data", fontFamily = VarelaRound, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("This step clears cookies, cache, localStorage, IndexedDB and service workers.", fontFamily = VarelaRound, fontSize = 12.sp, color = DuckDuckGoTheme.colors.text.secondary)
                Spacer(Modifier.height(14.dp))
                DdgPrimaryButton(text = "Delete This Tab Data", onClick = {
                    AutomationEngine.addStepToEditingScript(RecordedStep(sequenceIndex = 0, kind = StepKind.DeleteCookies, fieldType = "delete_tab", displayLabel = "Delete This Tab Data"))
                    showDeleteDialog = false
                })
                Spacer(Modifier.height(8.dp))
                DdgSecondaryButton(text = "Delete All Data", onClick = {
                    AutomationEngine.addStepToEditingScript(RecordedStep(sequenceIndex = 0, kind = StepKind.DeleteCookies, fieldType = "delete_all", displayLabel = "Delete All Data"))
                    showDeleteDialog = false
                })
                Spacer(Modifier.height(8.dp))
                Text("Cancel", color = DuckDuckGoTheme.colors.brand.accentBlue, fontFamily = VarelaRound, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable { showDeleteDialog = false }.padding(8.dp))
            }
        }
    }

    if (showCustomDialog) {
        Dialog(onDismissRequest = { showCustomDialog = false }) {
            Column(modifier = Modifier.fillMaxWidth().background(DuckDuckGoTheme.colors.backgrounds.surface, RoundedCornerShape(16.dp)).padding(16.dp)) {
                Text("Custom Step", fontFamily = VarelaRound, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = customSelector, onValueChange = { customSelector = it }, label = { Text("CSS Selector") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = customValue, onValueChange = { customValue = it }, label = { Text("Value (blank = click)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DdgSecondaryButton(text = "Cancel", onClick = { showCustomDialog = false }, modifier = Modifier.weight(1f))
                    DdgPrimaryButton(text = "Add", onClick = {
                        if (customSelector.isNotBlank()) {
                            val hasValue = customValue.isNotBlank()
                            AutomationEngine.addStepToEditingScript(RecordedStep(
                                sequenceIndex = 0, kind = if (hasValue) StepKind.Field else StepKind.ClickNext,
                                cssSelector = customSelector, customValue = if (hasValue) customValue else "",
                                fieldType = if (hasValue) "custom" else "",
                                displayLabel = if (hasValue) "Fill: $customSelector" else "Click: $customSelector"
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
    val script = AutomationEngine.hudSelectedScript ?: return
    val selectedGroups = AutomationEngine.hudSelectedGroups
    val selectedDefaultGroup = AutomationEngine.hudSelectedDefaultGroup
    val repeatCount = AutomationEngine.hudRunRepeatCount

    HudContainer(title = "Setup Run", subtitle = script.name, onClose = { AutomationEngine.openScriptList() }) {        
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
            Text("Recurring Data", color = DuckDuckGoTheme.colors.text.primary, fontFamily = VarelaRound, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            if (selectedGroups.isEmpty()) {
                Text("Data not selected. Edit script to assign data.", color = Color.Red, fontSize = 13.sp)
            } else {
                Box(modifier = Modifier.fillMaxWidth().background(DuckDuckGoTheme.colors.backgrounds.background, RoundedCornerShape(8.dp)).padding(12.dp)) {
                    Text(selectedGroups.joinToString(", "), fontFamily = VarelaRound, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
            
            Spacer(Modifier.height(12.dp))
            
            Text("Default Data", color = DuckDuckGoTheme.colors.text.primary, fontFamily = VarelaRound, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Box(modifier = Modifier.fillMaxWidth().background(DuckDuckGoTheme.colors.backgrounds.background, RoundedCornerShape(8.dp)).padding(12.dp)) {
                Text(
                    text = if (selectedDefaultGroup.isBlank()) "None (Uses Recurring)" else selectedDefaultGroup,
                    fontFamily = VarelaRound, 
                    fontSize = 14.sp, 
                    color = if (selectedDefaultGroup.isBlank()) DuckDuckGoTheme.colors.text.secondary else DuckDuckGoTheme.colors.text.primary
                )
            }
            
            Spacer(Modifier.height(16.dp))
            
            Text("Repeat Count", color = DuckDuckGoTheme.colors.text.primary, fontFamily = VarelaRound, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                OutlinedIconButton(onClick = { AutomationEngine.updateRunRepeat(repeatCount - 1) }, modifier = Modifier.size(36.dp), shape = RoundedCornerShape(8.dp)) {
                    Text("−", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Text("$repeatCount", modifier = Modifier.padding(horizontal = 24.dp), fontFamily = VarelaRound, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DuckDuckGoTheme.colors.text.primary)
                OutlinedIconButton(onClick = { AutomationEngine.updateRunRepeat(repeatCount + 1) }, modifier = Modifier.size(36.dp), shape = RoundedCornerShape(8.dp)) {
                    Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        
        val missingGroups = selectedGroups.filter { VaultRepository.getGroup(it) == null }
        val hasNoUnusedData = selectedGroups.isNotEmpty() && !selectedGroups.any { VaultRepository.hasUnusedData(it) }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DdgSecondaryButton(text = "Edit Script", onClick = { AutomationEngine.openScriptEditor(script) }, modifier = Modifier.weight(1f))
            DdgPrimaryButton(
                text = "Run", 
                onClick = { AutomationEngine.startSelectedScriptRun() }, 
                modifier = Modifier.weight(1f), 
                enabled = selectedGroups.isNotEmpty() && selectedGroups.any { VaultRepository.hasUnusedData(it) }
            )
        }
        
        if (missingGroups.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("Group(s) ${missingGroups.joinToString(", ")} deleted. Edit script.", color = Color.Red, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        } else if (hasNoUnusedData) {
            Spacer(Modifier.height(6.dp))
            Text("All selected recurring groups are empty or used.", color = Color.Red, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun HudRunningPanel() {
    val state = AutomationEngine.hudState
    val completed = AutomationEngine.hudCompleted
    val total = AutomationEngine.hudTotal
    val status = AutomationEngine.hudStatus
    val unusedLeft = AutomationEngine.hudSelectedGroups.sumOf { VaultRepository.unusedProfileCount(it) }

    HudContainer(title = "Automation · ${AutomationEngine.hudGroupName}", subtitle = status.ifBlank { "Running..." }, onClose = null) {
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(progress = { if (total <= 0) 0f else (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)),
            color = DuckDuckGoTheme.colors.brand.accentBlue, trackColor = DuckDuckGoTheme.colors.backgrounds.background)
        Spacer(Modifier.height(8.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$unusedLeft unused profiles", color = DuckDuckGoTheme.colors.text.secondary, fontFamily = VarelaRound, fontSize = 11.sp)
            DdgStatusBadge("$completed / $total", state != AutomationState.WAITING_CAPTCHA_HUMAN && state != AutomationState.WAITING_DATA_EXHAUSTED)
        }
        Spacer(Modifier.height(10.dp))

        when (state) {
            AutomationState.WAITING_CAPTCHA_HUMAN -> {
                DdgPrimaryButton(text = "I Solved the CAPTCHA — Continue", onClick = { AutomationEngine.continueAfterCaptchaResolution() })
                Spacer(Modifier.height(6.dp))
                DdgSecondaryButton(text = "Stop Automation", onClick = { AutomationEngine.stopAutomation() })
            }
            AutomationState.WAITING_DATA_EXHAUSTED -> {
                Text(
                    text = "All selected recurring groups finished! Repeat with the same data or add new profiles?", 
                    fontFamily = VarelaRound, 
                    fontSize = 12.sp, 
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DdgPrimaryButton(
                        text = "Repeat Data", 
                        onClick = { AutomationEngine.handleDataExhaustedChoice("repeat_same") }, 
                        modifier = Modifier.weight(1f)
                    )
                    DdgSecondaryButton(
                        text = "Add Data", 
                        onClick = { AutomationEngine.handleDataExhaustedChoice("add_new") }, 
                        modifier = Modifier.weight(1f)
                    )
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