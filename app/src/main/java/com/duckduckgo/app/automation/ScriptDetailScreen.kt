package com.duckduckgo.app.automation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.duckduckgo.app.browser.R
import com.duckduckgo.common.ui.compose.theme.DuckDuckGoTheme
import com.duckduckgo.mobile.android.R.drawable

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
    var steps by remember { mutableStateOf(originalScript.steps) }

    var showAddStepDialog by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }

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
                            RadioButton(
                                selected = selectedGroup == group.name,
                                onClick = { selectedGroup = group.name },
                            )
                            Text(group.name, fontFamily = VarelaRound, fontSize = 14.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Skeleton Steps
            Text("Web Skeleton Steps", color = DuckDuckGoTheme.colors.text.primary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            if (steps.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text("No steps yet. Add a function below.", color = DuckDuckGoTheme.colors.text.secondary, fontSize = 13.sp)
                }
            } else {
                steps.forEachIndexed { index, step ->
                    DdgCard(modifier = Modifier.padding(bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${index + 1}. ${step.displayLabel}", color = DuckDuckGoTheme.colors.text.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("Type: ${step.kind.name}", color = DuckDuckGoTheme.colors.text.secondary, fontSize = 12.sp)
                            }
                            IconButton(onClick = {
                                steps = steps.filterIndexed { i, _ -> i != index }.mapIndexed { i, s -> s.copy(sequenceIndex = i + 1) }
                            }) {
                                Icon(painterResource(drawable.ic_fire_24), contentDescription = "Delete step", tint = DuckDuckGoTheme.colors.text.destructive)
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
                    val updated = originalScript.copy(name = scriptName, groupName = selectedGroup, steps = steps)
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

                // Quick Add Options
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScriptChip("Wait 2s") { 
                        steps = steps + RecordedStep(sequenceIndex = steps.size + 1, kind = StepKind.Wait, waitMs = 2000, displayLabel = "Wait 2s")
                        showAddStepDialog = false 
                    }
                    ScriptChip("Delete Cookies") {
                        steps = steps + RecordedStep(sequenceIndex = steps.size + 1, kind = StepKind.DeleteCookies, displayLabel = "Delete Cookies")
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