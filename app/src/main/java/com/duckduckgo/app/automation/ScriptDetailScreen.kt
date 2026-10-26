package com.duckduckgo.app.automation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    var selectedGroups by remember(scriptId) { mutableStateOf(originalScript.groupNames.toSet()) }
    var selectedDefaultGroup by remember(scriptId) { mutableStateOf(originalScript.defaultGroupName) }
    var humanize by remember(scriptId) { mutableStateOf(originalScript.humanize) }
    var steps by remember(scriptId) { mutableStateOf(originalScript.steps) }

    var showAddStepDialog by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }
    var stepToEdit by remember { mutableStateOf<RecordedStep?>(null) }
    
    var showWaitDialog by remember { mutableStateOf(false) }
    var waitInput by remember { mutableStateOf("2") }

    val listState = rememberLazyListState()
    var draggedItemId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dropTargetId by remember { mutableStateOf<String?>(null) }
    var lastDragEventTime by remember { mutableLongStateOf(0L) }

    // BUG 3 FIX: identify dragged item by KEY (immune to header-count changes)
    // and fall back to dragOffset-based scrolling when the item's layout position
    // scrolls off-screen (which is exactly when the original `+ 4` code broke).
    LaunchedEffect(draggedItemId) {
        while (isActive && draggedItemId != null) {
            val viewportStart = listState.layoutInfo.viewportStartOffset.toFloat()
            val viewportEnd = listState.layoutInfo.viewportEndOffset.toFloat()
            val viewportHeight = viewportEnd - viewportStart
            val threshold = 150f
            val scrollStep = 20f

            val draggedInfo = listState.layoutInfo.visibleItemsInfo
                .find { it.key == draggedItemId }

            val shouldScrollDown: Boolean
            val shouldScrollUp: Boolean
            if (draggedInfo != null) {
                val top = draggedInfo.offset + dragOffset
                val bottom = top + draggedInfo.size
                shouldScrollDown = bottom > viewportEnd - threshold
                shouldScrollUp = top < viewportStart + threshold
            } else {
                // Dragged item is off-screen in layout — use dragOffset as fallback.
                shouldScrollDown = dragOffset > viewportHeight * 0.4f
                shouldScrollUp = dragOffset < -viewportHeight * 0.4f
            }

            if (shouldScrollDown) {
                listState.scrollBy(scrollStep)
                dragOffset += scrollStep
                Log.d("ScriptDetailScreen", "Auto-scroll DOWN step=$scrollStep dragOffset=$dragOffset")
            } else if (shouldScrollUp) {
                listState.scrollBy(-scrollStep)
                dragOffset -= scrollStep
                Log.d("ScriptDetailScreen", "Auto-scroll UP step=$scrollStep dragOffset=$dragOffset")
            }
            delay(16)
        }
    }

    val recurringGroups = VaultRepository.getRecurringGroups()
    val defaultGroups = VaultRepository.getDefaultGroups()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DuckDuckGoTheme.colors.backgrounds.background),
    ) {
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

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(16.dp)
        ) {
            item {
                Text("Recurring Data (Emails, Phones)", color = DuckDuckGoTheme.colors.text.primary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Select multiple pools. Engine picks 1-by-1 across them.", color = DuckDuckGoTheme.colors.text.secondary, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                DdgCard {
                    if (recurringGroups.isEmpty()) {
                        Text("No Recurring groups available.", color = Color.Red, fontSize = 13.sp)
                    } else {
                        recurringGroups.forEach { group ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { 
                                        selectedGroups = if (selectedGroups.contains(group.name)) selectedGroups - group.name else selectedGroups + group.name
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = selectedGroups.contains(group.name),
                                    onCheckedChange = { checked ->
                                        selectedGroups = if (checked) selectedGroups + group.name else selectedGroups - group.name
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = DuckDuckGoTheme.colors.brand.accentBlue)
                                )
                                Text(group.name, fontFamily = VarelaRound, fontSize = 14.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            item {
                Text("Default Data (Password, DOB)", color = DuckDuckGoTheme.colors.text.primary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Reusable data that never runs out.", color = DuckDuckGoTheme.colors.text.secondary, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                DdgCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedDefaultGroup = "" }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selectedDefaultGroup.isBlank(), onClick = { selectedDefaultGroup = "" })
                        Text("None", fontFamily = VarelaRound, fontSize = 14.sp, color = DuckDuckGoTheme.colors.text.secondary)
                    }
                    defaultGroups.forEach { group ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedDefaultGroup = group.name }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selectedDefaultGroup == group.name, onClick = { selectedDefaultGroup = group.name })
                            Text(group.name, fontFamily = VarelaRound, fontSize = 14.sp)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            item {
                DdgSwitchRow(
                    title = "Humanize Behavior",
                    subtitle = "Random small waits, letter-by-letter typing.",
                    checked = humanize,
                    onCheckedChange = { humanize = it },
                )
                Spacer(Modifier.height(24.dp))
                Text("Web Skeleton Steps", color = DuckDuckGoTheme.colors.text.primary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Long-press the handle ☰ to drag and reorder", color = DuckDuckGoTheme.colors.text.secondary, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
            }

            itemsIndexed(steps, key = { _, step -> step.id }) { index, step ->
                val isDragging = draggedItemId == step.id
                val isDropTarget = dropTargetId == step.id && draggedItemId != step.id

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .offset { IntOffset(0, if (isDragging) dragOffset.roundToInt() else 0) }
                        .zIndex(if (isDragging) 1f else 0f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isDropTarget) DuckDuckGoTheme.colors.brand.accentBlue.copy(alpha = 0.1f) else DuckDuckGoTheme.colors.backgrounds.surface,
                        border = if (isDropTarget) BorderStroke(2.dp, DuckDuckGoTheme.colors.brand.accentBlue) else null,
                        shadowElevation = if (isDragging) 8.dp else 0.dp,
                        modifier = Modifier.fillMaxWidth().alpha(if (isDragging) 0.9f else 1f)
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
                                Text("${index + 1}", color = DuckDuckGoTheme.colors.brand.accentBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            
                            Spacer(Modifier.width(16.dp))
                            
                            Column(modifier = Modifier.weight(1f)) {
                                Text(step.displayLabel, color = DuckDuckGoTheme.colors.text.primary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text("Type: ${step.kind.name}", color = DuckDuckGoTheme.colors.text.secondary, fontSize = 12.sp)
                            }
                            
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .pointerInput(steps.size, step.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                draggedItemId = step.id
                                                dragOffset = 0f
                                                dropTargetId = step.id
                                                lastDragEventTime = System.currentTimeMillis()
                                                Log.d("ScriptDetailScreen", "Drag START ${step.displayLabel}")
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragOffset += dragAmount.y
                                                lastDragEventTime = System.currentTimeMillis()

                                                val draggedInfo = listState.layoutInfo.visibleItemsInfo
                                                    .find { it.key == draggedItemId }
                                                if (draggedInfo != null) {
                                                    val centerY = draggedInfo.offset + dragOffset + draggedInfo.size / 2f
                                                    val target = listState.layoutInfo.visibleItemsInfo.find {
                                                        centerY >= it.offset && centerY <= it.offset + it.size
                                                    }
                                                    if (target != null && target.key != null) {
                                                        dropTargetId = target.key as String
                                                    }
                                                }
                                            },
                                            onDragEnd = {
                                                Log.d("ScriptDetailScreen", "Drag END from=${step.id} to=${dropTargetId}")
                                                val fromIdx = steps.indexOfFirst { it.id == step.id }
                                                val toIdx = steps.indexOfFirst { it.id == dropTargetId }
                                                if (fromIdx != -1 && toIdx != -1 && fromIdx != toIdx) {
                                                    val mutable = steps.toMutableList()
                                                    val item = mutable.removeAt(fromIdx)
                                                    mutable.add(toIdx, item)
                                                    steps = mutable.mapIndexed { i, s -> s.copy(sequenceIndex = i + 1) }
                                                }
                                                draggedItemId = null
                                                dragOffset = 0f
                                                dropTargetId = null
                                            },
                                            onDragCancel = {
                                                Log.d("ScriptDetailScreen", "Drag CANCEL")
                                                draggedItemId = null
                                                dragOffset = 0f
                                                dropTargetId = null
                                            }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(painterResource(drawable.ic_menu_hamburger_24), contentDescription = "Drag", tint = DuckDuckGoTheme.colors.text.secondary.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { showAddStepDialog = true },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DuckDuckGoTheme.colors.backgrounds.surface),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, DuckDuckGoTheme.colors.backgrounds.surface)
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
                            groupNames = selectedGroups.toList(),
                            defaultGroupName = selectedDefaultGroup,
                            humanize = humanize,
                            steps = steps,
                        )
                        AutomationScriptRepository.saveScript(updated)
                        onBack()
                    },
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showNameDialog) {
        InAppInputDialog(
            title = "Script Name",
            value = scriptName,
            onValueChange = { scriptName = it },
            confirmLabel = "OK", cancelLabel = "Cancel",
            onConfirm = { showNameDialog = false },
            onDismiss = { showNameDialog = false },
        )
    }

    if (showWaitDialog) {
        InAppInputDialog(
            title = "Wait Time (seconds)",
            value = waitInput,
            onValueChange = { waitInput = it.filter { c -> c.isDigit() }.take(3) },
            confirmLabel = "Add", cancelLabel = "Cancel",
            onConfirm = {
                val secs = (waitInput.toLongOrNull() ?: 2L).coerceIn(1L, 300L)
                steps = steps + RecordedStep(sequenceIndex = steps.size + 1, kind = StepKind.Wait, waitMs = secs * 1000, displayLabel = "Wait ${secs}s")
                showWaitDialog = false
                showAddStepDialog = false
            },
            onDismiss = { showWaitDialog = false }
        )
    }

    if (showAddStepDialog) {
        Dialog(onDismissRequest = { showAddStepDialog = false }) {
            Surface(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 24.dp),
                color = DuckDuckGoTheme.colors.backgrounds.surface
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Add to Script", fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = VarelaRound)
                        IconButton(onClick = { showAddStepDialog = false }) { Text("✕", fontSize = 20.sp, color = DuckDuckGoTheme.colors.text.secondary) }
                    }

                    Text("Actions", fontFamily = VarelaRound, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp), color = DuckDuckGoTheme.colors.text.secondary)
                    Spacer(Modifier.height(12.dp))
                    
                    Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            ActionCard(title = "Wait", icon = "⏳", modifier = Modifier.weight(1f)) { waitInput = "2"; showWaitDialog = true }
                            ActionCard(title = "CAPTCHA", icon = "🛡️", modifier = Modifier.weight(1f)) {
                                steps = steps + RecordedStep(sequenceIndex = steps.size + 1, kind = StepKind.CaptchaWait, displayLabel = "Wait CAPTCHA")
                                showAddStepDialog = false
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            ActionCard(title = "Clear Tab Data", icon = "🧹", modifier = Modifier.weight(1f)) {
                                steps = steps + RecordedStep(sequenceIndex = steps.size + 1, kind = StepKind.DeleteCookies, fieldType = "delete_tab", displayLabel = "Delete Tab Data")
                                showAddStepDialog = false
                            }
                            ActionCard(title = "Clear All Data", icon = "🔥", modifier = Modifier.weight(1f)) {
                                steps = steps + RecordedStep(sequenceIndex = steps.size + 1, kind = StepKind.DeleteCookies, fieldType = "delete_all", displayLabel = "Delete All Data")
                                showAddStepDialog = false
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    
                    Text("Web Fields", fontFamily = VarelaRound, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp), color = DuckDuckGoTheme.colors.text.secondary)
                    Spacer(Modifier.height(8.dp))
                    
                    LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                        items(items = FieldTypeMapper.assignOptions) { opt ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    steps = steps + RecordedStep(sequenceIndex = steps.size + 1, kind = StepKind.Field, fieldType = opt.canonical, displayLabel = FieldTypeMapper.fieldRowLabel(opt.canonical, ""))
                                    showAddStepDialog = false
                                }.padding(horizontal = 8.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.size(36.dp).background(DuckDuckGoTheme.colors.backgrounds.background, CircleShape), contentAlignment = Alignment.Center) {
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
                    DdgSecondaryButton(text = "Delete Step", onClick = {
                        steps = steps.filter { it.id != step.id }.mapIndexed { i, s -> s.copy(sequenceIndex = i + 1) }
                        stepToEdit = null
                    })
                    Spacer(Modifier.height(8.dp))
                    DdgSecondaryButton(text = "Cancel", onClick = { stepToEdit = null })
                }
            }
        }
    }
}

@Composable
private fun ActionCard(title: String, icon: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier.background(DuckDuckGoTheme.colors.backgrounds.background, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 20.sp)
        Spacer(Modifier.width(8.dp))
        Text(title, color = DuckDuckGoTheme.colors.text.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = VarelaRound)
    }
}