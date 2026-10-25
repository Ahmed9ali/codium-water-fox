/*
 * Copyright (c) 2025 DuckDuckGo
 * ... license header ...
 */

package com.duckduckgo.app.automation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duckduckgo.app.browser.R
import com.duckduckgo.common.ui.compose.theme.DuckDuckGoTheme
import com.duckduckgo.mobile.android.R.drawable

@Composable
fun ImportScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    var rawText by remember { mutableStateOf("") }
    var parsed by remember { mutableStateOf<SmartParser.ParsedData?>(null) }
    var groupName by remember { mutableStateOf("") }
    val selections = remember { mutableStateMapOf<String, Boolean>() }

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
                text = if (parsed == null) {
                    stringResource(R.string.automation_import_title)
                } else {
                    stringResource(R.string.automation_import_review_title)
                },
                color = DuckDuckGoTheme.colors.text.primary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (parsed == null) {
                // INPUT STAGE
                Text(
                    text = stringResource(R.string.automation_import_hint),
                    color = DuckDuckGoTheme.colors.text.secondary,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = rawText,
                    onValueChange = { rawText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    shape = RoundedCornerShape(12.dp),
                    placeholder = {
                        Text(
                            text = stringResource(R.string.automation_import_placeholder),
                            color = DuckDuckGoTheme.colors.text.secondary,
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = DuckDuckGoTheme.colors.backgrounds.surface,
                        unfocusedContainerColor = DuckDuckGoTheme.colors.backgrounds.surface,
                        focusedIndicatorColor = DuckDuckGoTheme.colors.brand.accentBlue,
                        unfocusedIndicatorColor = DuckDuckGoTheme.colors.text.secondary,
                    ),
                )

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        parsed = SmartParser.parse(rawText)
                        parsed?.all?.forEach { (type, value) ->
                            selections["$type::$value"] = true
                        }
                    },
                    enabled = rawText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DuckDuckGoTheme.colors.brand.accentBlue,
                        contentColor = Color.White,
                        disabledContainerColor = DuckDuckGoTheme.colors.text.disabled,
                        disabledContentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.automation_analyze),
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                // REVIEW STAGE
                val data = parsed!!
                val all = data.all

                if (all.isEmpty()) {
                    Text(
                        text = stringResource(R.string.automation_import_empty),
                        color = DuckDuckGoTheme.colors.text.secondary,
                        fontSize = 14.sp,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.automation_import_found, all.size),
                        color = DuckDuckGoTheme.colors.text.secondary,
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(12.dp))

                    all.forEach { (type, value) ->
                        val key = "$type::$value"
                        val checked = selections[key] == true
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = DuckDuckGoTheme.colors.backgrounds.surface,
                                    shape = RoundedCornerShape(12.dp),
                                )
                                .clickable { selections[key] = !checked }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { selections[key] = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = DuckDuckGoTheme.colors.brand.accentBlue,
                                ),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = type.uppercase(),
                                    color = DuckDuckGoTheme.colors.text.secondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = value,
                                    color = DuckDuckGoTheme.colors.text.primary,
                                    fontSize = 14.sp,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    Spacer(Modifier.height(16.dp))

                    OutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        label = { Text(stringResource(R.string.automation_group_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = DuckDuckGoTheme.colors.backgrounds.surface,
                            unfocusedContainerColor = DuckDuckGoTheme.colors.backgrounds.surface,
                            focusedIndicatorColor = DuckDuckGoTheme.colors.brand.accentBlue,
                            unfocusedIndicatorColor = DuckDuckGoTheme.colors.text.secondary,
                        ),
                    )

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = {
                            val selected = all.filter { (type, value) -> selections["$type::$value"] == true }
                            if (selected.isNotEmpty()) {
                                val text = selected.joinToString("\n") { (type, value) -> "$type: $value" }
                                val name = groupName.ifBlank {
                                    if (data.emails.isNotEmpty()) data.emails.first()
                                    else "Imported ${System.currentTimeMillis() % 10000}"
                                }
                                // FIXED: Added DataGroupType.RECURRING parameter
                                VaultRepository.addGroup(name, text, DataGroupType.RECURRING)
                                onSaved()
                            }
                        },
                        enabled = groupName.isNotBlank() && all.any { (type, value) -> selections["$type::$value"] == true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DuckDuckGoTheme.colors.brand.accentBlue,
                            contentColor = Color.White,
                            disabledContainerColor = DuckDuckGoTheme.colors.text.disabled,
                            disabledContentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.automation_import_save),
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}