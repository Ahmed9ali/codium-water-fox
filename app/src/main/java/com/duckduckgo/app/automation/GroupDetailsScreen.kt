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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duckduckgo.app.browser.R
import com.duckduckgo.common.ui.compose.theme.DuckDuckGoTheme
import com.duckduckgo.mobile.android.R.drawable

@Composable
fun GroupDetailScreen(
    groupName: String,
    onBack: () -> Unit,
) {
    val group = VaultRepository.groups.value.firstOrNull { it.name == groupName }
    val entries = group?.entries ?: emptyList()
    val unusedCount = group?.unusedCount ?: 0
    val usedCount = group?.usedCount ?: 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DuckDuckGoTheme.colors.backgrounds.background),
    ) {
        AutomationTopBar(
            title = groupName,
            subtitle = "$unusedCount unused · $usedCount used (${entries.size} total)",
            onBack = onBack,
            trailingContent = {
                if (usedCount > 0) {
                    Text(
                        text = "Reset Used",
                        color = DuckDuckGoTheme.colors.brand.accentBlue,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable { VaultRepository.resetUsed(groupName) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            },
        )

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.automation_no_entries),
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
                entries.forEachIndexed { index, entry ->
                    DdgCard(
                        onClick = { VaultRepository.toggleEntryUsed(groupName, index) },
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
                                    fontSize = 15.sp,
                                    textDecoration = if (entry.used) TextDecoration.LineThrough else TextDecoration.None,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            DdgStatusBadge(
                                text = if (entry.used) "USED" else "UNUSED",
                                isPositive = !entry.used,
                            )
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
}
