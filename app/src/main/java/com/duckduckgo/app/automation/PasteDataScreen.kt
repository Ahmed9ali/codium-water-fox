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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duckduckgo.app.browser.R
import com.duckduckgo.common.ui.compose.theme.DuckDuckGoTheme
import com.duckduckgo.mobile.android.R.drawable

@Composable
fun PasteDataScreen(
    type: DataGroupType, // NEW: Required to know what we are adding
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    var fieldName by remember { mutableStateOf("") }
    var valuesText by remember { mutableStateOf("") }

    val valueCount = valuesText
        .lines()
        .map { it.trim() }
        .count { it.isNotEmpty() }

    val fieldColors = TextFieldDefaults.colors(
        focusedTextColor = DuckDuckGoTheme.colors.text.primary,
        unfocusedTextColor = DuckDuckGoTheme.colors.text.primary,
        focusedLabelColor = DuckDuckGoTheme.colors.text.secondary,
        unfocusedLabelColor = DuckDuckGoTheme.colors.text.secondary,
        focusedPlaceholderColor = DuckDuckGoTheme.colors.text.secondary,
        unfocusedPlaceholderColor = DuckDuckGoTheme.colors.text.secondary,
        cursorColor = DuckDuckGoTheme.colors.brand.accentBlue,
        focusedContainerColor = DuckDuckGoTheme.colors.backgrounds.surface,
        unfocusedContainerColor = DuckDuckGoTheme.colors.backgrounds.surface,
        focusedIndicatorColor = DuckDuckGoTheme.colors.brand.accentBlue,
        unfocusedIndicatorColor = DuckDuckGoTheme.colors.text.secondary,
    )

    val shape = RoundedCornerShape(12.dp)

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
                text = stringResource(R.string.automation_paste_title),
                color = DuckDuckGoTheme.colors.text.primary,
                fontFamily = VarelaRound,
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
            // Type Indicator Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = if (type == DataGroupType.DEFAULT) Color(0xFFE0F2FE) else Color(0xFFFCE7F3),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(16.dp)
            ) {
                Column {
                    Text(
                        text = if (type == DataGroupType.DEFAULT) "Creating Default Data" else "Creating Recurring Data",
                        color = if (type == DataGroupType.DEFAULT) Color(0xFF0369A1) else Color(0xFFBE185D),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        fontFamily = VarelaRound
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (type == DataGroupType.DEFAULT) {
                            "This data is reusable and will never run out (e.g., Password, DOB)."
                        } else {
                            "This data is consumed 1-by-1 (e.g., Emails, Phone numbers)."
                        },
                        color = if (type == DataGroupType.DEFAULT) Color(0xFF0369A1).copy(alpha = 0.8f) else Color(0xFFBE185D).copy(alpha = 0.8f),
                        fontSize = 13.sp,
                        fontFamily = VarelaRound
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Field name
            Text(
                text = stringResource(R.string.automation_field_name_label),
                color = DuckDuckGoTheme.colors.text.secondary,
                fontFamily = VarelaRound,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = fieldName,
                onValueChange = { fieldName = it },
                placeholder = {
                    Text(
                        text = stringResource(R.string.automation_field_name_placeholder),
                        color = DuckDuckGoTheme.colors.text.secondary,
                        fontFamily = VarelaRound,
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = shape,
                colors = fieldColors,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
            )

            Spacer(Modifier.height(20.dp))

            // Values
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.automation_values_label),
                    color = DuckDuckGoTheme.colors.text.secondary,
                    fontFamily = VarelaRound,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                if (valueCount > 0) {
                    Text(
                        text = stringResource(R.string.automation_values_count, valueCount),
                        color = DuckDuckGoTheme.colors.brand.accentBlue,
                        fontFamily = VarelaRound,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = valuesText,
                onValueChange = { valuesText = it },
                placeholder = {
                    Text(
                        text = stringResource(R.string.automation_values_placeholder),
                        color = DuckDuckGoTheme.colors.text.secondary,
                        fontFamily = VarelaRound,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                shape = shape,
                colors = fieldColors,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Default,
                ),
            )

            Spacer(Modifier.height(24.dp))

            // Save button
            val canSave = fieldName.isNotBlank() && valueCount > 0
            Button(
                onClick = {
                    if (canSave) {
                        // UPDATED: Pass the type to the repository
                        VaultRepository.addGroup(fieldName, valuesText, type)
                        onSaved()
                    }
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DuckDuckGoTheme.colors.brand.accentBlue,
                    contentColor = Color.White,
                    disabledContainerColor = DuckDuckGoTheme.colors.text.disabled,
                    disabledContentColor = Color.White,
                ),
                shape = shape,
            ) {
                Text(
                    text = if (valueCount > 0) {
                        stringResource(R.string.automation_save_with_count, valueCount)
                    } else {
                        stringResource(R.string.automation_save)
                    },
                    color = Color.White,
                    fontFamily = VarelaRound,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}