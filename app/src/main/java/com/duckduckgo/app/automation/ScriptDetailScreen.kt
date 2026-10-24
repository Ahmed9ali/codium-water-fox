package com.duckduckgo.app.automation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import com.duckduckgo.app.browser.R
import com.duckduckgo.common.ui.compose.theme.DuckDuckGoTheme
import com.duckduckgo.mobile.android.R.drawable
import kotlin.math.roundToInt

@Composable
fun ScriptDetailScreen(
    scriptId: String,
    onBack: () -> Unit,
) {
    val originalScript = AutomationScriptRepository.getScript(scriptId)
    if (originalScript == null) {
        onBack()
        return
    }

    var scriptName by remember { mutableStateOf(originalScript.name) }
    var selectedGroup by remember { mutableStateOf(originalScript.groupName) }
    var humanize by remember { mutableStateOf(originalScript.humanize) }
    var steps by remember { mutableStateOf(originalScript.steps) }

    var showAddStepDialog by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }

    // Drag State
    var draggedItemId by remember { mutableStateOf<String?>(null) }
    var draggedOffset by remember { mutableFloatStateOf(0f) }

    val groups = VaultRepository.groups.value

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DuckDuckGoTheme.colors.backgrounds.background),
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DuckDuckGoTheme.colors.backgrounds.surface)
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(drawable.ic_arrow_left_24), contentDescription = "Back", tint = DuckDuckGoTheme.colors.icons.primary)
            }
            Text(
                text = scriptName,
                color = DuckDuckGoTheme.colors.text.primary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showNameDialog = true }) {
                Icon(painterResource(drawable.ic_edit_24), contentDescription = "Edit Name", tint = DuckDuckGoTheme.colors.icons.primary)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // Data Group Selection
            Text("Assigned Data Group", color = DuckDuckGoTheme.colors.text.primary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            DdgCard {
                if (groups.isEmpty()) {
                    Text("No groups available. Create one in My Data.", color = DuckDuckGoTheme.colors.text.secondary, fontSize = 13.sp)
                } else {
                    groups.forEach { group ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedGroup = group.name }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selectedGroup == group.name, onClick = { selectedGroup = group.name })
                            Text(group.name, fontFamily = VarelaRound, fontSize = 14.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Humanize Toggle
            DdgSwitchRow(
                title = "Humanize Behavior",
                subtitle = "Random small waits, letter-by-letter typing, and scrolls into view",
                checked = humanize,
                onCheckedChange = { humanize = it }
            )

            Spacer(Modifier.height(24.dp))

            // Skeleton Steps (now LazyColumn for smooth drag)
            Text("Web Skeleton Steps", color = DuckDuckGoTheme.colors.text.primary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Long-press ☰ to reorder", color = DuckDuckGoTheme.colors.text.secondary, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))

            if (steps.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text("No steps yet. Add a function below.", color = DuckDuckGoTheme.colors.text.secondary, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = (steps.size * 90).dp, max = 600.dp) // Ensure LazyColumn gets enough height
                ) {
                    itemsIndexed(steps, key = { _, step -> step.id }) { index, step ->
                        val isDragging = draggedItemId == step.id
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .zIndex(if (isDragging) 1f else 0f)
                                .offset { IntOffset(0, if (isDragging) draggedOffset.roundToInt() else 0) }
                        ) {
                            DdgCard {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .pointerInput(steps.size, index) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    draggedItemId = step.id
                                                    draggedOffset = 0f
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    draggedOffset += dragAmount.y
                                                },
                                                onDragEnd = {
                                                    // Calculate how many positions to move based on drag offset
                                                    val threshold = 80.dp.toPx()
                                                    val itemsMoved = (draggedOffset / threshold).roundToInt()
                                                    val targetIndex = (index + itemsMoved).coerceIn(0, steps.size - 1)
                                                    
                                                    if (targetIndex != index) {
                                                        val mutable = steps.toMutableList()
                                                        val item = mutable.removeAt(index)
                                                        mutable.add(targetIndex, item)
                                                        steps = mutable.mapIndexed { i, s -> s.copy(sequenceIndex = i + 1) }
                                                    }
                                                    draggedItemId = null
                                                    draggedOffset = 0f
                                                },
                                                onDragCancel = {
                                                    draggedItemId = null
                                                    draggedOffset = 0f
                                                }
                                            )
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "☰",
                                        color = DuckDuckGoTheme.colors.text.secondary,
                                        fontSize = 20.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "${index + 1}. ${step.displayLabel}",
                                            color = DuckDuckGoTheme.colors.text.primary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            "Type: ${step.kind.name}",
                                            color = DuckDuckGoTheme.colors.text.secondary,
                                            fontSize = 12.sp
                                        )
                                    }
                                    IconButton(onClick = {
                                        steps = steps.filterIndexed { i, _ -> i != index }
                                            .mapIndexed { i, s -> s.copy(sequenceIndex = i + 1) }
                                    }) {
                                        Icon(
                                            painterResource(drawable.ic_fire_24),
                                            contentDescription = "Delete step",
                                            tint = DuckDuckGoTheme.colors.text.destructive
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            DdgSecondaryButton(
                text = "+ Add Step / Function",
                iconRes = drawable.ic_add_24,
                onClick = { showAddStepDialog = true }
            )

            Spacer(Modifier.height(24.dp))

            DdgPrimaryButton(
                text = "Save Script",
                onClick = {
                    val updated = originalScript.copy(name = scriptName, groupName = selectedGroup, humanize = humanize, steps = steps)
                    AutomationScriptRepository.saveScript(updated)
                    onBack()
                }
            )
        }
    }

    // Rename Dialog
    if (showNameDialog) {
        InAppInputDialog(
            title = "Script Name",
            value = scriptName,
            onValueChange = { scriptName = it },
            confirmLabel = "OK",
            cancelLabel = "Cancel",
            onConfirm = { showNameDialog = false },
            onDismiss = { showNameDialog = false },
        )
    }

    // Add Step Dialog
    if (showAddStepDialog) {
        Dialog(onDismissRequest = { showAddStepDialog = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .background(DuckDuckGoTheme.colors.backgrounds.surface, RoundedCornerShape(16.dp))
                    .padding(16.dp),
            ) {
                Text("Add Function", fontFamily = VarelaRound, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScriptChip("Wait 2s") {
                        steps = steps + RecordedStep(sequenceIndex = steps.size + 1, kind = StepKind.Wait, waitMs = 2000, displayLabel = "Wait 2s")
                        showAddStepDialog = false
                    }
                    ScriptChip("Delete Tab Data") {
                        steps = steps + RecordedStep(sequenceIndex = steps.size + 1, kind = StepKind.DeleteCookies, fieldType = "delete_tab", displayLabel = "Delete This Tab Data")
                        showAddStepDialog = false
                    }
                    ScriptChip("Delete All Data") {
                        steps = steps + RecordedStep(sequenceIndex = steps.size + 1, kind = StepKind.DeleteCookies, fieldType = "delete_all", displayLabel = "Delete All Data")
                        showAddStepDialog = false
                    }
                    ScriptChip("Wait CAPTCHA") {
                        steps = steps + RecordedStep(sequenceIndex = steps.size + 1, kind = StepKind.CaptchaWait, displayLabel = "Wait for CAPTCHA")
                        showAddStepDialog = false
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("Web Fields (Skeleton)", fontFamily = VarelaRound, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    FieldTypeMapper.assignOptions.forEach { opt ->
                        Text(
                            text = opt.label,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    steps = steps + RecordedStep(
                                        sequenceIndex = steps.size + 1,
                                        kind = StepKind.Field,
                                        fieldType = opt.canonical,
                                        displayLabel = FieldTypeMapper.fieldRowLabel(opt.canonical, "")
                                    )
                                    showAddStepDialog = false
                                }
                                .padding(vertical = 10.dp),
                            fontFamily = VarelaRound,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScriptChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(DuckDuckGoTheme.colors.brand.accentBlue.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text, color = DuckDuckGoTheme.colors.brand.accentBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}