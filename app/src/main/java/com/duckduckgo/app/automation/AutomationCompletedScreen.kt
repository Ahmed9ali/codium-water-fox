/*
 * Copyright (c) 2025 DuckDuckGo
 * ... license header ...
 */

package com.duckduckgo.app.automation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duckduckgo.app.browser.R
import com.duckduckgo.common.ui.compose.theme.DuckDuckGoTheme
import com.duckduckgo.mobile.android.R.drawable

@Composable
fun AutomationCompletedScreen(
    groupName: String,
    mode: String,
    totalFilled: Int,
    onViewLogs: () -> Unit,
    onRunAgain: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DuckDuckGoTheme.colors.backgrounds.background),
    ) {
        AutomationTopBar(
            title = "",
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))

            Icon(
                painter = painterResource(drawable.ic_check_24),
                contentDescription = null,
                tint = DuckDuckGoTheme.colors.brand.accentBlue,
                modifier = Modifier.size(96.dp),
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.automation_completed_title),
                color = DuckDuckGoTheme.colors.text.primary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.automation_completed_desc),
                color = DuckDuckGoTheme.colors.text.secondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(32.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = DuckDuckGoTheme.colors.backgrounds.surface,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatRow(stringResource(R.string.automation_data_set_label), groupName)
                StatRow(stringResource(R.string.automation_mode_label), if (mode == "series") "In Series" else "Random")
                StatRow(stringResource(R.string.automation_total_filled_label), "$totalFilled / $totalFilled")
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onViewLogs,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DuckDuckGoTheme.colors.brand.accentBlue,
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.automation_view_logs_btn),
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onRunAgain,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.automation_run_again),
                    color = DuckDuckGoTheme.colors.brand.accentBlue,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = DuckDuckGoTheme.colors.text.secondary,
            fontSize = 14.sp,
        )
        Text(
            text = value,
            color = DuckDuckGoTheme.colors.text.primary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}