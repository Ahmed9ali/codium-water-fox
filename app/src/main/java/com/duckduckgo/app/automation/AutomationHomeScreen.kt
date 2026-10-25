package com.duckduckgo.app.automation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
fun AutomationHomeScreen(
    onBack: () -> Unit,
    onMyDataClick: () -> Unit,
    onAddDataClick: () -> Unit,
    onMyScriptsClick: () -> Unit,
    onGroupClick: (String) -> Unit,
    onViewLogsClick: () -> Unit,
) {
    val groups = VaultRepository.groups.value
    val scripts = AutomationScriptRepository.allScripts()

    val totalUnused = groups.sumOf { it.unusedCount }
    val totalUsed = groups.sumOf { it.usedCount }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DuckDuckGoTheme.colors.backgrounds.background)
            .verticalScroll(rememberScrollState()),
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
                Icon(
                    painter = painterResource(drawable.ic_arrow_left_24),
                    contentDescription = "Back",
                    tint = DuckDuckGoTheme.colors.icons.primary,
                )
            }
            Text(
                text = "Automation",
                color = DuckDuckGoTheme.colors.text.primary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Column(modifier = Modifier.padding(16.dp)) {
            // Main Header Card
            DdgCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(DuckDuckGoTheme.colors.brand.accentBlue.copy(alpha = 0.1f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(drawable.ic_automation_24),
                            contentDescription = null,
                            tint = DuckDuckGoTheme.colors.brand.accentBlue,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "Fill Forms Automatically",
                            color = DuckDuckGoTheme.colors.text.primary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = VarelaRound
                        )
                        Text(
                            text = "Enter target website, pick a group,\nconfigure delays & run 1-by-1",
                            color = DuckDuckGoTheme.colors.text.secondary,
                            fontSize = 13.sp,
                            fontFamily = VarelaRound
                        )
                    }
                }
                
                Spacer(Modifier.height(16.dp))

                // "Create Automation" button removed here.
                // The "Add Data" button now routes to the Tabbed MyDataScreen
                DdgSecondaryButton(
                    text = "Add Data",
                    iconRes = drawable.ic_add_24,
                    onClick = onAddDataClick 
                )
            }

            Spacer(Modifier.height(16.dp))

            // Stats Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard("${groups.size}", "Groups", Modifier.weight(1f))
                StatCard("$totalUnused", "Unused Data", Modifier.weight(1f))
                StatCard("$totalUsed", "Used Data", Modifier.weight(1f))
            }

            Spacer(Modifier.height(24.dp))

            // Quick Actions
            Text(
                text = "Quick Actions",
                color = DuckDuckGoTheme.colors.text.primary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = VarelaRound
            )
            Text(
                text = "Manage your saved datasets, scripts, and used status",
                color = DuckDuckGoTheme.colors.text.secondary,
                fontSize = 13.sp,
                fontFamily = VarelaRound
            )
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickActionCard(
                    title = "My Data",
                    subtitle = "${groups.size} saved groups",
                    iconRes = drawable.ic_bookmarks_24,
                    modifier = Modifier.weight(1f),
                    onClick = onMyDataClick
                )
                QuickActionCard(
                    title = "My Scripts",
                    subtitle = "${scripts.size} saved",
                    iconRes = drawable.ic_automation_24,
                    modifier = Modifier.weight(1f),
                    onClick = onMyScriptsClick
                )
            }

            Spacer(Modifier.height(24.dp))

            // Pinned & Recent Groups
            if (groups.isNotEmpty()) {
                Text(
                    text = "Pinned & Recent Groups",
                    color = DuckDuckGoTheme.colors.text.primary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = VarelaRound
                )
                Text(
                    text = "Tap any group to inspect entries or launch automation",
                    color = DuckDuckGoTheme.colors.text.secondary,
                    fontSize = 13.sp,
                    fontFamily = VarelaRound
                )
                Spacer(Modifier.height(12.dp))

                groups.take(5).forEach { group ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DuckDuckGoTheme.colors.backgrounds.surface, RoundedCornerShape(12.dp))
                            .clickable { onGroupClick(group.name) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(drawable.ic_bookmarks_24),
                            contentDescription = null,
                            tint = DuckDuckGoTheme.colors.brand.accentBlue,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = group.name,
                                color = DuckDuckGoTheme.colors.text.primary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = VarelaRound
                            )
                            Text(
                                text = "${group.unusedCount} available · ${group.usedCount} used",
                                color = DuckDuckGoTheme.colors.text.secondary,
                                fontSize = 13.sp,
                                fontFamily = VarelaRound
                            )
                        }
                        DdgStatusBadge(
                            text = if (group.unusedCount > 0) "Ready" else "Empty",
                            isPositive = group.unusedCount > 0
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(DuckDuckGoTheme.colors.backgrounds.surface, RoundedCornerShape(12.dp))
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            color = DuckDuckGoTheme.colors.brand.accentBlue,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = DuckDuckGoTheme.colors.text.secondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun QuickActionCard(
    title: String,
    subtitle: String,
    iconRes: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .background(DuckDuckGoTheme.colors.backgrounds.surface, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = DuckDuckGoTheme.colors.brand.accentBlue,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            color = DuckDuckGoTheme.colors.text.primary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = VarelaRound
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle,
            color = DuckDuckGoTheme.colors.text.secondary,
            fontSize = 12.sp,
            fontFamily = VarelaRound
        )
    }
}