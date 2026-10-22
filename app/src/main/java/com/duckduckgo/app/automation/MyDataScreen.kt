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

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    onAddClick: () -> Unit,
    onGroupClick: (String) -> Unit,
) {
    val context = LocalContext.current
    var groupToDelete by remember { mutableStateOf<String?>(null) }
    var groupToRename by remember { mutableStateOf<String?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var overflowMenuFor by remember { mutableStateOf<String?>(null) }

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
                text = stringResource(R.string.automation_my_data),
                color = DuckDuckGoTheme.colors.text.primary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onAddClick) {
                Icon(
                    painter = painterResource(drawable.ic_add_24),
                    contentDescription = stringResource(R.string.automation_add_data),
                    tint = DuckDuckGoTheme.colors.icons.primary,
                )
            }
        }

        val groups = VaultRepository.groups.value
        if (groups.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.automation_no_data),
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
                                Toast.makeText(
                                    context,
                                    "Max ${5} pinned groups",
                                    Toast.LENGTH_SHORT,
                                ).show()
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

    // Custom in-app confirmation dialog
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

    // Rename dialog
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
                Text(
                    text = group.name,
                    color = DuckDuckGoTheme.colors.text.primary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
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