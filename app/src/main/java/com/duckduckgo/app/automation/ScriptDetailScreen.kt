package com.duckduckgo.app.automation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import com.duckduckgo.common.ui.compose.theme.DuckDuckGoTheme
import com.duckduckgo.mobile.android.R.drawable
import kotlin.math.roundToInt

@Composable
fun ScriptDetailScreen(
    scriptId: String,
    onBack: () -> Unit,
) {
    val originalScript: AutomationScript? = AutomationScriptRepository.getScript(scriptId)

    if (originalScript == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var scriptName by remember(scriptId) { mutableStateOf(originalScript.name) }
    var selectedGroup by remember(scriptId) { mutableStateOf(originalScript.groupName) }
    var selectedDefaultGroup by remember(scriptId) { mutableStateOf(originalScript.defaultGroupName) }
    var humanize by remember(scriptId) { mutableStateOf(originalScript.humanize) }
    var steps by remember(scriptId) { mutableStateOf(originalScript.steps) }

    var showAddStepDialog by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }
    var stepToEdit by remember { mutableStateOf<RecordedStep?>(null) }

    // Drag State
    var draggedItemId by remember { mutableStateOf<String?>(null) }
    var draggedOffset by remember { mutableFloatStateOf(0f) }

    val recurringGroups = VaultRepository.getRecurringGroups()
    val defaultGroups = VaultRepository.getDefaultGroups()

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
                Icon(
                    painter = painterResource(drawable.ic_arrow_left_24),
                    contentDescription = "Back",
                    tint = DuckDuckGoTheme.colors.icons.primary,
                )
            }
            Text(
                text = scriptName,
                color = DuckDuckGoTheme.colors.text.primary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showNameDialog = true }) {
                Icon(
                    painter = painterResource(drawable.ic_edit_24),
                    contentDescription = "Edit Name",
                    tint = DuckDuckGoTheme.colors.icons.primary,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // RECURRING DATA SELECTION
            Text(
                "Recurring Data (Emails, Phones)",
                color = DuckDuckGoTheme.colors.text.primary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Select the pool of data to use 1-by-1. Required for automation.",
                color = DuckDuckGoTheme.colors.text.secondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            DdgCard {
                if (recurringGroups.isEmpty()) {
                    Text("No Recurring groups available. Create one in My Data.", color = Color.Red, fontSize = 13.sp)
                } else {
                    recurringGroups.forEach { group ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedGroup = group.name }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedGroup == group.name,
                                onClick = { selectedGroup = group.name },
                            )
                            Text(group.name, fontFamily = VarelaRound, fontSize = 14.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // DEFAULT DATA SELECTION
            Text(
                "Default Data (Password, DOB)",
                color = DuckDuckGoTheme.colors.text.primary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Reusable data that never runs out. Used for static fields.",
                color = DuckDuckGoTheme.colors.text.secondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            DdgCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedDefaultGroup = "" }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selectedDefaultGroup.isBlank(),
                        onClick = { selectedDefaultGroup = "" },
                    )
                    Text("None (Use Recurring for all)", fontFamily = VarelaRound, fontSize = 14.sp, color = DuckDuckGoTheme.colors.text.secondary)
                }
                defaultGroups.forEach { group ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedDefaultGroup = group.name }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedDefaultGroup == group.name,
                            onClick = { selectedDefaultGroup = group.name },
                        )
                        Text(group.name, fontFamily = VarelaRound, fontSize = 14.sp)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Humanize Toggle
            DdgSwitchRow(
                title = "Humanize Behavior",
                subtitle = "Random small waits, letter-by-letter typing, and scrolls into view",
                checked = humanize,
                onCheckedChange = { humanize = it },
            )

            Spacer(Modifier.height(24.dp))

            // Skeleton Steps
            Text(
                "Web Skeleton Steps",
                color = DuckDuckGoTheme.colors.text.primary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Long-press the handle ☰ to reorder",
                color = DuckDuckGoTheme.colors.text.secondary,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(8.dp))

            if (steps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No steps yet. Add a function below.",
                        color = DuckDuckGoTheme.colors.text.secondary,
                        fontSize = 13.sp,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = (steps.size * 76).dp, max = 600.dp),
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
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = DuckDuckGoTheme.colors.backgrounds.surface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { stepToEdit = step }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .background(DuckDuckGoTheme.colors.brand.accentBlue.copy(alpha = 0.15f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "${index + 1}",
                                            color = DuckDuckGoTheme.colors.brand.accentBlue,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                    }
                                    
                                    Spacer(Modifier.width(16.dp))
                                    
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            step.displayLabel,
                                            color = DuckDuckGoTheme.colors.text.primary,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            "Type: ${step.kind.name}",
                                            color = DuckDuckGoTheme.colors.text.secondary,
                                            fontSize = 12.sp
                                        )
                                    }
                                    
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
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
                                                        val threshold = 76.dp.toPx()
                                                        val itemsMoved = (draggedOffset / threshold).roundToInt()
                                                        val targetIndex = (index + itemsMoved).coerceIn(0, steps.size - 1)

                                                        if (targetIndex != index) {
                                                            val mutable = steps.toMutableList()
                                                            val item = mutable.removeAt(index)
                                                            mutable.add(targetIndex, item)
                                                            steps = mutable.mapIndexed { i, s ->
                                                                s.copy(sequenceIndex = i + 1)
                                                            }
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
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(drawable.ic_menu_hamburger_24),
                                            contentDescription = "Drag to reorder",
                                            tint = DuckDuckGoTheme.colors.text.secondary.copy(alpha = 0.5f),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            
            Button(
                onClick = { showAddStepDialog = true },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DuckDuckGoTheme.colors.backgrounds.surface),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DuckDuckGoTheme.colors.backgrounds.surface)
            ) {
                Icon(painterResource(drawable.ic_add_24), contentDescription = null, tint = DuckDuckGoTheme.colors.brand.accentBlue)
                Spacer(Modifier.width(8.dp))
                Text("Add Step / Function", color = DuckDuckGoTheme.colors.brand.accentBlue, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(24.dp))

            DdgPrimaryButton(
                text = "Save Script",
                onClick = {
                    val updated = originalScript.copy(
                        name = scriptName,
                        groupName = selectedGroup,
                        defaultGroupName = selectedDefaultGroup,
                        humanize = humanize,
                        steps = steps,
                    )
                    AutomationScriptRepository.saveScript(updated)
                    onBack()
                },
            )
        }
    }

    // Dialogs remain the same (Rename, Add Step, Step Options)...
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

    if (showAddStepDialog) {
        Dialog(onDismissRequest = { showAddStepDialog = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 24.dp),
                color = DuckDuckGoTheme.colors.backgrounds.surface
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Add to Script", fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = VarelaRound)
                        IconButton(onClick = { showAddStepDialog = false }) {
                            Text("✕", fontSize = 20.sp, color = DuckDuckGoTheme.colors.text.secondary)
                        }
                    }

                    Text("Actions", fontFamily = VarelaRound, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp), color = DuckDuckGoTheme.colors.text.secondary)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ActionChip("Wait 2s") {
                            steps = steps + RecordedStep(sequenceIndex = steps.size + 1, kind = StepKind.Wait, waitMs = 2000, displayLabel = "Wait 2s")
                            showAddStepDialog = false
                        }
                        ActionChip("Clear Data") {
                            steps = steps + RecordedStep(sequenceIndex = steps.size + 1, kind = StepKind.DeleteCookies, fieldType = "delete_tab", displayLabel = "Delete Tab Data")
                            showAddStepDialog = false
                        }
                        ActionChip("CAPTCHA") {
                            steps = steps + RecordedStep(sequenceIndex = steps.size + 1, kind = StepKind.CaptchaWait, displayLabel = "Wait CAPTCHA")
                            showAddStepDialog = false
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    
                    Text("Web Fields", fontFamily = VarelaRound, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp), color = DuckDuckGoTheme.colors.text.secondary)
                    Spacer(Modifier.height(8.dp))
                    
                    LazyColumn(
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        contentPadding = PaddingValues(bottom = 20.dp)
                    ) {
                        items(items = FieldTypeMapper.assignOptions) { opt ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        steps = steps + RecordedStep(
                                            sequenceIndex = steps.size + 1,
                                            kind = StepKind.Field,
                                            fieldType = opt.canonical,
                                            displayLabel = FieldTypeMapper.fieldRowLabel(opt.canonical, ""),
                                        )
                                        showAddStepDialog = false
                                    }
                                    .padding(horizontal = 8.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier.size(36.dp).background(DuckDuckGoTheme.colors.backgrounds.background, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(opt.canonical.take(1).uppercase(), fontWeight = FontWeight.Bold, color = DuckDuckGoTheme.colors.brand.accentBlue)
                                }
                                Spacer(Modifier.width(16.dp))
                                Text(opt.label, fontFamily = VarelaRound, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            }
                            HorizontalDivider(color = DuckDuckGoTheme.colors.backgrounds.background, thickness = 1.dp)
                        }
                    }
                }
            }
        }
    }

    stepToEdit?.let { step ->
        Dialog(onDismissRequest = { stepToEdit = null }) {
            Surface(shape = RoundedCornerShape(16.dp), color = DuckDuckGoTheme.colors.backgrounds.surface) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(step.displayLabel, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("${step.kind.name} Step", color = DuckDuckGoTheme.colors.text.secondary, fontSize = 14.sp)
                    Spacer(Modifier.height(16.dp))
                    
                    DdgSecondaryButton(
                        text = "Delete Step",
                        onClick = {
                            steps = steps.filter { it.id != step.id }.mapIndexed { i, s -> s.copy(sequenceIndex = i + 1) }
                            stepToEdit = null
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    DdgSecondaryButton(
                        text = "Cancel",
                        onClick = { stepToEdit = null }
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(DuckDuckGoTheme.colors.brand.accentBlue.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text, color = DuckDuckGoTheme.colors.brand.accentBlue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}