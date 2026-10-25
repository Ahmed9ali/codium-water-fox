package com.duckduckgo.app.automation

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset // <-- THIS WAS MISSING
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
fun MyDataScreen(
    onBack: () -> Unit,
    onAddClick: (DataGroupType) -> Unit,
    onGroupClick: (String) -> Unit,
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    
    var groupToDelete by remember { mutableStateOf<String?>(null) }
    var groupToRename by remember { mutableStateOf<String?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var overflowMenuFor by remember { mutableStateOf<String?>(null) }

    val currentType = if (selectedTab == 0) DataGroupType.RECURRING else DataGroupType.DEFAULT
    val groups = if (selectedTab == 0) VaultRepository.getRecurringGroups() else VaultRepository.getDefaultGroups()

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
                text = "My Data",
                color = DuckDuckGoTheme.colors.text.primary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onAddClick(currentType) }) {
                Icon(
                    painter = painterResource(drawable.ic_add_24),
                    contentDescription = "Add Data",
                    tint = DuckDuckGoTheme.colors.icons.primary,
                )
            }
        }

        // TABS
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = DuckDuckGoTheme.colors.backgrounds.surface,
            contentColor = DuckDuckGoTheme.colors.brand.accentBlue,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = DuckDuckGoTheme.colors.brand.accentBlue
                )
            },
            divider = {
                HorizontalDivider(color = DuckDuckGoTheme.colors.backgrounds.background)
            }
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { 
                    Text(
                        "Recurring Data", 
                        fontWeight = FontWeight.SemiBold,
                        color = if (selectedTab == 0) DuckDuckGoTheme.colors.text.primary else DuckDuckGoTheme.colors.text.secondary
                    ) 
                }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { 
                    Text(
                        "Default Data", 
                        fontWeight = FontWeight.SemiBold,
                        color = if (selectedTab == 1) DuckDuckGoTheme.colors.text.primary else DuckDuckGoTheme.colors.text.secondary
                    ) 
                }
            )
        }

        // Info Banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(DuckDuckGoTheme.colors.brand.accentBlue.copy(alpha = 0.1f))
                .padding(16.dp)
        ) {
            Text(
                text = if (selectedTab == 0) {
                    "Recurring data is consumed 1-by-1. Perfect for Emails, Phones, and Usernames."
                } else {
                    "Default data is reused infinitely. Perfect for Passwords, DOB, and Names."
                },
                color = DuckDuckGoTheme.colors.brand.accentBlue,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }

        if (groups.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No ${currentType.name.lowercase()} groups yet.",
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
                groups.forEach { group ->
                    GroupRow(
                        group = group,
                        isOverflowOpen = overflowMenuFor == group.name,
                        onCardClick = { onGroupClick(group.name) },
                        onOverflowClick = {
                            overflowMenuFor = if (overflowMenuFor == group.name) null else group.name
                        },
                        onRenameClick = {
                            groupToRename = group.name
                            renameInput = group.name
                            overflowMenuFor = null
                        },
                        onPinClick = {
                            if (!group.pinned && !VaultRepository.canPinMore()) {
                                Toast.makeText(context, "Max 5 pinned groups", Toast.LENGTH_SHORT).show()
                            } else {
                                VaultRepository.togglePin(group.name)
                            }
                            overflowMenuFor = null
                        },
                        onDeleteClick = {
                            groupToDelete = group.name
                            overflowMenuFor = null
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }

    // Delete Confirmation
    groupToDelete?.let { name ->
        InAppConfirmDialog(
            title = stringResource(R.string.automation_delete_confirm),
            message = name,
            confirmLabel = stringResource(R.string.automation_delete),
            cancelLabel = stringResource(R.string.automation_cancel),
            onConfirm = {
                VaultRepository.removeGroup(name)
                groupToDelete = null
            },
            onDismiss = { groupToDelete = null },
        )
    }

    // Rename Dialog
    groupToRename?.let { oldName ->
        InAppInputDialog(
            title = stringResource(R.string.automation_rename_title),
            value = renameInput,
            onValueChange = { renameInput = it },
            confirmLabel = stringResource(R.string.automation_save),
            cancelLabel = stringResource(R.string.automation_cancel),
            onConfirm = {
                VaultRepository.renameGroup(oldName, renameInput)
                groupToRename = null
            },
            onDismiss = { groupToRename = null },
        )
    }
}

@Composable
private fun GroupRow(
    group: VaultGroup,
    isOverflowOpen: Boolean,
    onCardClick: () -> Unit,
    onOverflowClick: () -> Unit,
    onRenameClick: () -> Unit,
    onPinClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = DuckDuckGoTheme.colors.backgrounds.surface,
                    shape = RoundedCornerShape(12.dp),
                )
                .clickable(onClick = onCardClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(
                    if (group.pinned) drawable.ic_bookmark_solid_24 else drawable.ic_bookmarks_24,
                ),
                contentDescription = null,
                tint = if (group.pinned) {
                    DuckDuckGoTheme.colors.brand.accentBlue
                } else {
                    DuckDuckGoTheme.colors.icons.primary
                },
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = group.name,
                        color = DuckDuckGoTheme.colors.text.primary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (group.type == DataGroupType.DEFAULT) Color(0xFFE0F2FE) else Color(0xFFFCE7F3),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (group.type == DataGroupType.DEFAULT) "DEFAULT" else "RECURRING",
                            color = if (group.type == DataGroupType.DEFAULT) Color(0xFF0369A1) else Color(0xFFBE185D),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.automation_entries_count, group.entries.size),
                    color = DuckDuckGoTheme.colors.text.secondary,
                    fontSize = 13.sp,
                )
            }
            IconButton(onClick = onOverflowClick) {
                Icon(
                    painter = painterResource(drawable.ic_menu_hamburger_24),
                    contentDescription = stringResource(R.string.automation_more_options),
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
                    label = stringResource(R.string.automation_rename),
                    onClick = onRenameClick,
                )
                OverflowRow(
                    icon = if (group.pinned) drawable.ic_bookmark_solid_24 else drawable.ic_bookmark_24,
                    label = if (group.pinned) {
                        stringResource(R.string.automation_unpin)
                    } else {
                        stringResource(R.string.automation_pin)
                    },
                    onClick = onPinClick,
                )
                OverflowRow(
                    icon = drawable.ic_fire_24,
                    label = stringResource(R.string.automation_delete),
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