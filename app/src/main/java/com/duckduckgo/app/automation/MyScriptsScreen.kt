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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duckduckgo.app.browser.R
import com.duckduckgo.common.ui.compose.theme.DuckDuckGoTheme
import com.duckduckgo.mobile.android.R.drawable

@Composable
fun MyScriptsScreen(
    onBack: () -> Unit,
    onScriptClick: (String) -> Unit,
) {
    var scriptToDelete by remember { mutableStateOf<String?>(null) }
    var scriptToRename by remember { mutableStateOf<String?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var overflowMenuFor by remember { mutableStateOf<String?>(null) }

    val scripts = AutomationScriptRepository.scripts.value

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DuckDuckGoTheme.colors.backgrounds.background),
    ) {
        // Top bar
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
                    contentDescription = stringResource(R.string.automation_back),
                    tint = DuckDuckGoTheme.colors.icons.primary,
                )
            }
            Text(
                text = "My Scripts",
                color = DuckDuckGoTheme.colors.text.primary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }

        if (scripts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No scripts saved yet.",
                    color = DuckDuckGoTheme.colors.text.secondary,
                    fontSize = 15.sp,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                scripts.forEach { script ->
                    ScriptRow(
                        script = script,
                        isOverflowOpen = overflowMenuFor == script.id,
                        onCardClick = { onScriptClick(script.id) },
                        onOverflowClick = {
                            overflowMenuFor = if (overflowMenuFor == script.id) null else script.id
                        },
                        onRenameClick = {
                            scriptToRename = script.id
                            renameInput = script.name
                            overflowMenuFor = null
                        },
                        onDeleteClick = {
                            scriptToDelete = script.id
                            overflowMenuFor = null
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }

    scriptToDelete?.let { id ->
        InAppConfirmDialog(
            title = "Delete Script?",
            message = "Are you sure you want to delete this script?",
            confirmLabel = "Delete",
            cancelLabel = "Cancel",
            onConfirm = {
                AutomationScriptRepository.deleteScript(id)
                scriptToDelete = null
            },
            onDismiss = { scriptToDelete = null },
        )
    }

    scriptToRename?.let { id ->
        InAppInputDialog(
            title = "Rename Script",
            value = renameInput,
            onValueChange = { renameInput = it },
            confirmLabel = "Save",
            cancelLabel = "Cancel",
            onConfirm = {
                AutomationScriptRepository.renameScript(id, renameInput)
                scriptToRename = null
            },
            onDismiss = { scriptToRename = null },
        )
    }
}

@Composable
private fun ScriptRow(
    script: AutomationScript,
    isOverflowOpen: Boolean,
    onCardClick: () -> Unit,
    onOverflowClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = DuckDuckGoTheme.colors.backgrounds.surface,
                    shape = RoundedCornerShape(12.dp),
                )
                .clickable(onClick = onCardClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(drawable.ic_automation_24),
                contentDescription = null,
                tint = DuckDuckGoTheme.colors.brand.accentBlue,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = script.name,
                    color = DuckDuckGoTheme.colors.text.primary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "${script.host} · ${script.steps.size} steps",
                    color = DuckDuckGoTheme.colors.text.secondary,
                    fontSize = 13.sp,
                )
            }
            
            // RUN BUTTON DIRECTLY FROM LIST
            IconButton(onClick = { AutomationEngine.launchScriptFromList(script, context) }) {
                Icon(
                    painter = painterResource(drawable.ic_play_24), // Uses standard play icon
                    contentDescription = "Run Script",
                    tint = DuckDuckGoTheme.colors.brand.accentBlue,
                )
            }

            IconButton(onClick = onOverflowClick) {
                Icon(
                    painter = painterResource(drawable.ic_menu_hamburger_24),
                    contentDescription = "More options",
                    tint = DuckDuckGoTheme.colors.icons.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (isOverflowOpen) {
            Spacer(Modifier.height(4.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = DuckDuckGoTheme.colors.backgrounds.surface,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(vertical = 4.dp),
            ) {
                OverflowRow(
                    icon = drawable.ic_edit_24,
                    label = "Rename",
                    onClick = onRenameClick,
                )
                OverflowRow(
                    icon = drawable.ic_fire_24,
                    label = "Delete",
                    tint = DuckDuckGoTheme.colors.text.destructive,
                    onClick = onDeleteClick,
                )
            }
        }
    }
}

@Composable
private fun OverflowRow(
    icon: Int,
    label: String,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = tint ?: DuckDuckGoTheme.colors.icons.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            color = tint ?: DuckDuckGoTheme.colors.text.primary,
            fontSize = 14.sp,
        )
    }
}