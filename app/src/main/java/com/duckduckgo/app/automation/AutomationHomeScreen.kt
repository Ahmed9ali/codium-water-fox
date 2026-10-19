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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
    onCreateAutomationClick: () -> Unit,
    onViewLogsClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DuckDuckGoTheme.colors.backgrounds.background)
            .verticalScroll(rememberScrollState()),
    ) {
        AutomationTopBar(
            title = stringResource(R.string.automation_home_title),
            onBack = onBack,
        )

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            // Top margin on the hero card
            Spacer(Modifier.height(16.dp))

            HeroCard(onAddDataClick = onAddDataClick)

            Spacer(Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.automation_quick_actions),
                color = DuckDuckGoTheme.colors.text.primary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QuickActionButton(
                    modifier = Modifier.weight(1f),
                    iconRes = drawable.ic_bookmarks_24,
                    label = stringResource(R.string.automation_my_data),
                    onClick = onMyDataClick,
                )
                QuickActionButton(
                    modifier = Modifier.weight(1f),
                    iconRes = drawable.ic_document_download_24,
                    label = stringResource(R.string.automation_view_logs),
                    onClick = onViewLogsClick,
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun HeroCard(onAddDataClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = DuckDuckGoTheme.colors.backgrounds.surface,
                shape = RoundedCornerShape(16.dp),
            )
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.automation_fill_forms_title),
            color = DuckDuckGoTheme.colors.text.primary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.automation_fill_forms_subtitle),
            color = DuckDuckGoTheme.colors.text.secondary,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onAddDataClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = DuckDuckGoTheme.colors.brand.accentBlue,
                contentColor = Color.White,
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(
                painter = painterResource(drawable.ic_add_24),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.automation_add_data),
                color = Color.White,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun QuickActionButton(
    modifier: Modifier = Modifier,
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .background(
                color = DuckDuckGoTheme.colors.backgrounds.surface,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = DuckDuckGoTheme.colors.brand.accentBlue,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            color = DuckDuckGoTheme.colors.text.primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun AutomationTopBar(title: String, subtitle: String? = null, onBack: () -> Unit) {
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = DuckDuckGoTheme.colors.text.primary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = DuckDuckGoTheme.colors.text.secondary,
                    fontSize = 13.sp,
                )
            }
        }
    }
}