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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duckduckgo.app.browser.R
import com.duckduckgo.common.ui.compose.theme.DuckDuckGoTheme
import com.duckduckgo.mobile.android.R.drawable

private val PRESET_FIELD_TYPES = listOf(
    "email",
    "password",
    "confirmPassword",
    "fullName",
    "firstName",
    "lastName",
    "username",
    "phone",
    "message",
    "subject",
    "address",
    "city",
    "zip",
    "country",
    "countryCode",
    "dob",
    "otp",
    "text",
)

@Composable
fun GroupDetailScreen(
    groupName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val group = VaultRepository.groups.value.firstOrNull { it.name == groupName }
    val entries = group?.entries ?: emptyList()
    val unusedCount = group?.unusedCount ?: 0
    val usedCount = group?.usedCount ?: 0

    // State for "Add Specific Data" dialog
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedFieldType by remember { mutableStateOf("email") }
    var customFieldType by remember { mutableStateOf("") }
    var newEntryValuesText by remember { mutableStateOf("") }

    // State for "Edit Specific Entry" dialog
    var editingIndex by remember { mutableIntStateOf(-1) }
    var editingFieldType by remember { mutableStateOf("") }
    var editingValue by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DuckDuckGoTheme.colors.backgrounds.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AutomationTopBar(
                title = groupName,
                subtitle = "$unusedCount unused · $usedCount used (${entries.size} total)",
                onBack = onBack,
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (usedCount > 0) {
                            Text(
                                text = "Reset Used",
                                color = DuckDuckGoTheme.colors.brand.accentBlue,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clickable {
                                        VaultRepository.resetUsed(groupName)
                                        Toast.makeText(context, "All entries marked UNUSED", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                        IconButton(onClick = {
                            selectedFieldType = "email"
                            customFieldType = ""
                            newEntryValuesText = ""
                            showAddDialog = true
                        }) {
                            Icon(
                                painter = painterResource(drawable.ic_add_24),
                                contentDescription = "Add Specific Data",
                                tint = DuckDuckGoTheme.colors.brand.accentBlue,
                            )
                        }
                    }
                },
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                // Top quick-add button inside the group
                DdgSecondaryButton(
                    text = "+ Add Specific Field / Data to '$groupName'",
                    onClick = {
                        selectedFieldType = "email"
                        customFieldType = ""
                        newEntryValuesText = ""
                        showAddDialog = true
                    },
                )

                Spacer(Modifier.height(14.dp))

                if (entries.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.automation_no_entries),
                            color = DuckDuckGoTheme.colors.text.secondary,
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 15.sp,
                        )
                    }
                } else {
                    entries.forEachIndexed { index, entry ->
                        DdgCard(
                            onClick = {
                                editingIndex = index
                                editingFieldType = entry.fieldType
                                editingValue = entry.value
                            },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "#${index + 1} · ${entry.fieldType.uppercase()}",
                                        color = DuckDuckGoTheme.colors.text.secondary,
                                        fontFamily = FontFamily.SansSerif,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = entry.value,
                                        color = if (entry.used) {
                                            DuckDuckGoTheme.colors.text.secondary
                                        } else {
                                            DuckDuckGoTheme.colors.text.primary
                                        },
                                        fontFamily = FontFamily.SansSerif,
                                        fontSize = 15.sp,
                                        textDecoration = if (entry.used) TextDecoration.LineThrough else TextDecoration.None,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier.clickable {
                                        VaultRepository.toggleEntryUsed(groupName, index)
                                    },
                                ) {
                                    DdgStatusBadge(
                                        text = if (entry.used) "USED" else "UNUSED",
                                        isPositive = !entry.used,
                                    )
                                }
                                IconButton(
                                    onClick = { VaultRepository.deleteEntry(groupName, index) },
                                ) {
                                    Icon(
                                        painter = painterResource(drawable.ic_fire_24),
                                        contentDescription = stringResource(R.string.automation_delete),
                                        tint = DuckDuckGoTheme.colors.text.secondary,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }

        // ====================================================================
        // MODAL 1: ADD SPECIFIC DATA TO THIS GROUP
        // ====================================================================
        if (showAddDialog) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable { showAddDialog = false },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(DuckDuckGoTheme.colors.backgrounds.surface)
                        .clickable { /* consume click */ }
                        .padding(20.dp),
                ) {
                    Text(
                        text = "Add Specific Data to '$groupName'",
                        color = DuckDuckGoTheme.colors.text.primary,
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Select the field type (or type a custom one) and enter 1 or more values (1 per line).",
                        color = DuckDuckGoTheme.colors.text.secondary,
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 12.sp,
                    )

                    Spacer(Modifier.height(12.dp))

                    // Horizontal scrollable field type chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PRESET_FIELD_TYPES.forEach { preset ->
                            val isSelected = selectedFieldType.equals(preset, ignoreCase = true) && customFieldType.isBlank()
                            val chipShape = RoundedCornerShape(10.dp)
                            Box(
                                modifier = Modifier
                                    .clip(chipShape)
                                    .background(
                                        if (isSelected) {
                                            DuckDuckGoTheme.colors.brand.accentBlue
                                        } else {
                                            DuckDuckGoTheme.colors.backgrounds.background
                                        },
                                        chipShape,
                                    )
                                    .border(
                                        1.dp,
                                        if (isSelected) {
                                            DuckDuckGoTheme.colors.brand.accentBlue
                                        } else {
                                            DuckDuckGoTheme.colors.text.secondary.copy(alpha = 0.25f)
                                        },
                                        chipShape,
                                    )
                                    .clickable {
                                        selectedFieldType = preset
                                        customFieldType = ""
                                    }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                            ) {
                                Text(
                                    text = preset,
                                    color = if (isSelected) Color.White else DuckDuckGoTheme.colors.text.primary,
                                    fontFamily = FontFamily.SansSerif,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    DdgTextField(
                        value = customFieldType,
                        onValueChange = { customFieldType = it },
                        label = "Or Custom Field Type (optional)",
                        placeholder = "Selected: $selectedFieldType",
                        singleLine = true,
                    )

                    Spacer(Modifier.height(10.dp))

                    DdgTextField(
                        value = newEntryValuesText,
                        onValueChange = { newEntryValuesText = it },
                        label = "Value(s) for ${customFieldType.ifBlank { selectedFieldType }} (1 per line)",
                        placeholder = "Enter value (or paste multiple lines)...",
                        singleLine = false,
                        modifier = Modifier.height(130.dp),
                    )

                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        DdgSecondaryButton(
                            text = stringResource(R.string.automation_cancel),
                            modifier = Modifier.weight(1f),
                            onClick = { showAddDialog = false },
                        )
                        DdgPrimaryButton(
                            text = stringResource(R.string.automation_save),
                            modifier = Modifier.weight(1f),
                            enabled = newEntryValuesText.trim().isNotEmpty(),
                            onClick = {
                                val finalType = customFieldType.trim().ifBlank { selectedFieldType }
                                VaultRepository.addSpecificEntries(
                                    groupName = groupName,
                                    fieldType = finalType,
                                    rawValues = newEntryValuesText,
                                )
                                showAddDialog = false
                            },
                        )
                    }
                }
            }
        }

        // ====================================================================
        // MODAL 2: EDIT EXISTING ENTRY FIELD TYPE OR VALUE
        // ====================================================================
        if (editingIndex >= 0 && editingIndex < entries.size) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable { editingIndex = -1 },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(DuckDuckGoTheme.colors.backgrounds.surface)
                        .clickable { }
                        .padding(20.dp),
                ) {
                    Text(
                        text = "Edit Entry #${editingIndex + 1}",
                        color = DuckDuckGoTheme.colors.text.primary,
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(12.dp))
                    DdgTextField(
                        value = editingFieldType,
                        onValueChange = { editingFieldType = it },
                        label = "Field Type (e.g. email, fullName, message)",
                        singleLine = true,
                    )
                    Spacer(Modifier.height(10.dp))
                    DdgTextField(
                        value = editingValue,
                        onValueChange = { editingValue = it },
                        label = "Value",
                        singleLine = false,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        DdgSecondaryButton(
                            text = stringResource(R.string.automation_cancel),
                            modifier = Modifier.weight(1f),
                            onClick = { editingIndex = -1 },
                        )
                        DdgPrimaryButton(
                            text = stringResource(R.string.automation_save),
                            modifier = Modifier.weight(1f),
                            enabled = editingValue.trim().isNotEmpty(),
                            onClick = {
                                VaultRepository.updateEntry(
                                    groupName = groupName,
                                    index = editingIndex,
                                    newFieldType = editingFieldType.trim().ifBlank { "text" },
                                    newValue = editingValue.trim(),
                                )
                                editingIndex = -1
                            },
                        )
                    }
                }
            }
        }
    }
}
