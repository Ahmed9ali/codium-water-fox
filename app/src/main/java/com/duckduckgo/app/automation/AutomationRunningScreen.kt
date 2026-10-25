package com.duckduckgo.app.automation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip // <-- FIXED: Added this import
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duckduckgo.common.ui.compose.theme.DuckDuckGoTheme

@Composable
fun AutomationRunningScreen(
    config: AutomationConfig,
    onFinished: (Int) -> Unit,
    onAddNewDataRequested: () -> Unit,
    onCancel: () -> Unit,
) {
    val displayGroup = config.groupNames.joinToString(", ")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DuckDuckGoTheme.colors.backgrounds.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        
        Text(
            text = "Running Automation",
            color = DuckDuckGoTheme.colors.text.primary,
            fontFamily = VarelaRound,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        
        Spacer(Modifier.height(8.dp))
        
        Text(
            text = "Target: ${config.targetUrl}",
            color = DuckDuckGoTheme.colors.text.secondary,
            fontFamily = VarelaRound,
            fontSize = 14.sp,
        )
        
        Spacer(Modifier.height(4.dp))
        
        Text(
            text = "Data Pools: $displayGroup",
            color = DuckDuckGoTheme.colors.text.secondary,
            fontFamily = VarelaRound,
            fontSize = 14.sp,
        )

        Spacer(Modifier.height(32.dp))
        
        LinearProgressIndicator(
            progress = { 
                if (config.targetRepeatCount <= 0) 0f 
                else (AutomationEngine.hudCompleted.toFloat() / config.targetRepeatCount.toFloat()).coerceIn(0f, 1f) 
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = DuckDuckGoTheme.colors.brand.accentBlue,
            trackColor = DuckDuckGoTheme.colors.backgrounds.surface
        )
        
        Spacer(Modifier.height(16.dp))
        
        Text(
            text = "${AutomationEngine.hudCompleted} / ${config.targetRepeatCount} completed",
            color = DuckDuckGoTheme.colors.text.primary,
            fontFamily = VarelaRound,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(Modifier.height(32.dp))

        if (AutomationEngine.hudState == AutomationState.WAITING_DATA_EXHAUSTED) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Red.copy(alpha = 0.1f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Data Exhausted",
                        color = Color.Red,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "All selected recurring groups are empty. Add more data to continue.",
                        color = DuckDuckGoTheme.colors.text.primary,
                        fontSize = 14.sp,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            DdgPrimaryButton(text = "Add New Data", onClick = onAddNewDataRequested)
        }

        Spacer(Modifier.weight(1f))
        
        Spacer(Modifier.height(32.dp))
        
        DdgSecondaryButton(text = "Stop Automation", onClick = onCancel)
        Spacer(Modifier.height(32.dp))
    }
}