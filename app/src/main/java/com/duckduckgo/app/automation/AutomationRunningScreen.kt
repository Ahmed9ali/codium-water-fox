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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay

@Composable
fun AutomationRunningScreen(
    groupName: String,
    mode: String,
    onFinished: (count: Int) -> Unit,
    onCancel: () -> Unit,
) {
    val group = VaultRepository.getGroup(groupName)
    val total = group?.entries?.size ?: 0

    var completed by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        if (total == 0) {
            onFinished(0)
            return@LaunchedEffect
        }
        for (i in 1..total) {
            delay(700)
            completed = i
        }
        delay(500)
        onFinished(total)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DuckDuckGoTheme.colors.backgrounds.background),
    ) {
        AutomationTopBar(
            title = stringResource(R.string.automation_running_title),
            onBack = onCancel,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(drawable.ic_automation_24),
                contentDescription = null,
                tint = DuckDuckGoTheme.colors.brand.accentBlue,
                modifier = Modifier.size(72.dp),
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.automation_filling_form),
                color = DuckDuckGoTheme.colors.text.primary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.automation_using_dataset, groupName),
                color = DuckDuckGoTheme.colors.text.secondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(32.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StatBox(
                    label = stringResource(R.string.automation_completed_count),
                    value = completed.toString(),
                )
                StatBox(
                    label = stringResource(R.string.automation_remaining_count),
                    value = (total - completed).toString(),
                )
                StatBox(
                    label = stringResource(R.string.automation_total_count),
                    value = total.toString(),
                )
            }

            Spacer(Modifier.height(24.dp))

            LinearProgressIndicator(
                progress = { if (total == 0) 0f else completed.toFloat() / total },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = DuckDuckGoTheme.colors.brand.accentBlue,
                trackColor = DuckDuckGoTheme.colors.backgrounds.surface,
            )

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DuckDuckGoTheme.colors.backgrounds.surface,
                    contentColor = DuckDuckGoTheme.colors.text.primary,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.automation_pause),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun StatBox(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(
                color = DuckDuckGoTheme.colors.backgrounds.surface,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(
            text = value,
            color = DuckDuckGoTheme.colors.brand.accentBlue,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = DuckDuckGoTheme.colors.text.secondary,
            fontSize = 11.sp,
        )
    }
}