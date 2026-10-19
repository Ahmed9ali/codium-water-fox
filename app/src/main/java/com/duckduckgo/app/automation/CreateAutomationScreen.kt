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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duckduckgo.app.browser.R
import com.duckduckgo.common.ui.compose.theme.DuckDuckGoTheme
import com.duckduckgo.mobile.android.R.drawable

@Composable
fun CreateAutomationScreen(
    onBack: () -> Unit,
    onStart: (groupName: String, mode: String) -> Unit,
) {
    val groups = VaultRepository.groups.value
    var selectedGroup by remember { mutableStateOf(groups.firstOrNull()?.name ?: "") }
    var mode by remember { mutableStateOf("series") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DuckDuckGoTheme.colors.backgrounds.background),
    ) {
        AutomationTopBar(
            title = stringResource(R.string.automation_create_title),
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.automation_create_desc),
                color = DuckDuckGoTheme.colors.text.secondary,
                fontSize = 14.sp,
            )

            Spacer(Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.automation_select_dataset),
                color = DuckDuckGoTheme.colors.text.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            if (groups.isEmpty()) {
                Text(
                    text = stringResource(R.string.automation_no_dataset),
                    color = DuckDuckGoTheme.colors.text.secondary,
                    fontSize = 14.sp,
                )
            } else {
                groups.forEach { group ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = DuckDuckGoTheme.colors.backgrounds.surface,
                                shape = RoundedCornerShape(12.dp),
                            )
                            .clickable { selectedGroup = group.name }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedGroup == group.name,
                            onClick = { selectedGroup = group.name },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = DuckDuckGoTheme.colors.brand.accentBlue,
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = group.name,
                                color = DuckDuckGoTheme.colors.text.primary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = stringResource(R.string.automation_entries_count, group.entries.size),
                                color = DuckDuckGoTheme.colors.text.secondary,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.automation_fill_mode),
                color = DuckDuckGoTheme.colors.text.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            ModeOption(
                title = stringResource(R.string.automation_mode_in_series),
                desc = stringResource(R.string.automation_mode_in_series_desc),
                selected = mode == "series",
                onClick = { mode = "series" },
            )
            Spacer(Modifier.height(8.dp))
            ModeOption(
                title = stringResource(R.string.automation_mode_random),
                desc = stringResource(R.string.automation_mode_random_desc),
                selected = mode == "random",
                onClick = { mode = "random" },
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { onStart(selectedGroup, mode) },
                enabled = selectedGroup.isNotBlank(),
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
                    text = stringResource(R.string.automation_start_automation),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ModeOption(
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = DuckDuckGoTheme.colors.backgrounds.surface,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = DuckDuckGoTheme.colors.brand.accentBlue,
            ),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = DuckDuckGoTheme.colors.text.primary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = desc,
                color = DuckDuckGoTheme.colors.text.secondary,
                fontSize = 13.sp,
            )
        }
    }
}