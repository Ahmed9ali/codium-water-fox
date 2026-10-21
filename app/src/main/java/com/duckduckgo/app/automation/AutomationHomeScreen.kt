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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duckduckgo.app.browser.R
import com.duckduckgo.common.ui.compose.theme.DuckDuckGoTheme
import com.duckduckgo.mobile.android.R.drawable

@Composable
fun AutomationHomeScreen(
    onBack: () -> Unit,
    onMyDataClick: () -> Unit,
    onAddDataClick: () -> Unit,
    onCreateAutomationClick: () -> Unit,
    onViewLogsClick: () -> Unit,
) {
    val groups = VaultRepository.groups.value
    val totalGroups = groups.size
    val totalUnused = groups.sumOf { it.unusedCount }
    val totalUsed = groups.sumOf { it.usedCount }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DuckDuckGoTheme.colors.backgrounds.background),
    ) {
        AutomationTopBar(
            title = stringResource(R.string.automation_home_title),
            subtitle = "Multi-Step Browser Form Engine",
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // Primary Hero Card: Create & Run Automation
            DdgCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                DuckDuckGoTheme.colors.brand.accentBlue.copy(alpha = 0.14f),
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(drawable.ic_automation_24),
                            contentDescription = null,
                            tint = DuckDuckGoTheme.colors.brand.accentBlue,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.automation_fill_forms_title),
                            color = DuckDuckGoTheme.colors.text.primary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Enter target website, pick a group, configure delays & run 1-by-1",
                            color = DuckDuckGoTheme.colors.text.secondary,
                            fontSize = 13.sp,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                DdgPrimaryButton(
                    text = stringResource(R.string.automation_create_title),
                    iconRes = drawable.ic_automation_24,
                    onClick = onCreateAutomationClick,
                )

                Spacer(Modifier.height(10.dp))

                DdgSecondaryButton(
                    text = stringResource(R.string.automation_add_data),
                    iconRes = drawable.ic_add_24,
                    onClick = onAddDataClick,
                )
            }

            Spacer(Modifier.height(20.dp))

            // Vault Summary Stats Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SummaryStatCard(
                    modifier = Modifier.weight(1f),
                    value = totalGroups.toString(),
                    label = "Groups",
                )
                SummaryStatCard(
                    modifier = Modifier.weight(1f),
                    value = totalUnused.toString(),
                    label = "Unused Data",
                )
                SummaryStatCard(
                    modifier = Modifier.weight(1f),
                    value = totalUsed.toString(),
                    label = "Used Data",
                )
            }

            Spacer(Modifier.height(24.dp))

            DdgSectionHeader(
                title = stringResource(R.string.automation_quick_actions),
                subtitle = "Manage your saved datasets, groups, and used status",
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QuickActionTile(
                    modifier = Modifier.weight(1f),
                    iconRes = drawable.ic_bookmarks_24,
                    title = stringResource(R.string.automation_my_data),
                    subtitle = "$totalGroups saved groups",
                    onClick = onMyDataClick,
                )
                QuickActionTile(
                    modifier = Modifier.weight(1f),
                    iconRes = drawable.ic_add_24,
                    title = stringResource(R.string.automation_add_data),
                    subtitle = "Paste, upload or generate",
                    onClick = onAddDataClick,
                )
            }

            if (groups.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                DdgSectionHeader(
                    title = "Pinned & Recent Groups",
                    subtitle = "Tap any group to inspect entries or launch automation",
                )
                Spacer(Modifier.height(10.dp))

                groups.take(4).forEach { group ->
                    DdgCard(
                        onClick = onMyDataClick,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (group.pinned) drawable.ic_bookmark_solid_24 else drawable.ic_bookmarks_24,
                                ),
                                contentDescription = null,
                                tint = DuckDuckGoTheme.colors.brand.accentBlue,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = group.name,
                                    color = DuckDuckGoTheme.colors.text.primary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "${group.unusedCount} available · ${group.usedCount} used",
                                    color = DuckDuckGoTheme.colors.text.secondary,
                                    fontSize = 12.sp,
                                )
                            }
                            DdgStatusBadge(
                                text = if (group.unusedCount > 0) "Ready" else "Exhausted",
                                isPositive = group.unusedCount > 0,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SummaryStatCard(
    modifier: Modifier = Modifier,
    value: String,
    label: String,
) {
    DdgCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                color = DuckDuckGoTheme.colors.brand.accentBlue,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                color = DuckDuckGoTheme.colors.text.secondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun QuickActionTile(
    modifier: Modifier = Modifier,
    iconRes: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    DdgCard(
        modifier = modifier,
        onClick = onClick,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = DuckDuckGoTheme.colors.brand.accentBlue,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            color = DuckDuckGoTheme.colors.text.primary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle,
            color = DuckDuckGoTheme.colors.text.secondary,
            fontSize = 11.sp,
        )
    }
}
