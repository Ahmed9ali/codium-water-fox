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

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duckduckgo.app.browser.BrowserActivity
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.browser.mode.InAppNavigation
import com.duckduckgo.common.ui.compose.theme.DuckDuckGoTheme
import com.duckduckgo.mobile.android.R.drawable
import com.duckduckgo.fonts.R as FontsR // IMPORTED FONTS R

// CHANGED: Use the hijacked DuckSans font (which is actually Varela Round)
val VarelaRound = FontFamily(
    Font(FontsR.font.ducksansdisplay_regular, FontWeight.Normal)
)

sealed interface AutomationScreen {
    object Home : AutomationScreen
    object MyData : AutomationScreen
    object AddData : AutomationScreen
    object PasteData : AutomationScreen
    object GenerateData : AutomationScreen
    data class GroupDetail(val groupName: String) : AutomationScreen
    object CreateAutomation : AutomationScreen
    data class Running(val config: AutomationConfig) : AutomationScreen
    data class Completed(val groupName: String, val mode: String, val count: Int) : AutomationScreen
}

@Composable
fun AutomationNavHost(
    initialScreen: AutomationScreen = AutomationScreen.Home,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = DuckDuckGoTheme.colors.backgrounds.background,
    ) {
        var current by remember { mutableStateOf<AutomationScreen>(initialScreen) }

        BackHandler {
            when (current) {
                AutomationScreen.Home -> onBack()
                AutomationScreen.MyData -> current = AutomationScreen.Home
                is AutomationScreen.GroupDetail -> current = AutomationScreen.MyData
                AutomationScreen.AddData -> current = AutomationScreen.Home
                AutomationScreen.PasteData -> current = AutomationScreen.AddData
                AutomationScreen.GenerateData -> current = AutomationScreen.AddData
                AutomationScreen.CreateAutomation -> current = AutomationScreen.Home
                is AutomationScreen.Running -> {
                    AutomationEngine.stopAutomation()
                    current = AutomationScreen.Home
                }
                is AutomationScreen.Completed -> current = AutomationScreen.Home
            }
        }

        when (val c = current) {
            AutomationScreen.Home -> AutomationHomeScreen(
                onBack = onBack,
                onMyDataClick = { current = AutomationScreen.MyData },
                onAddDataClick = { current = AutomationScreen.AddData },
                onCreateAutomationClick = { current = AutomationScreen.CreateAutomation },
                onViewLogsClick = { current = AutomationScreen.MyData },
            )

            AutomationScreen.MyData -> MyDataScreen(
                onBack = { current = AutomationScreen.Home },
                onAddClick = { current = AutomationScreen.AddData },
                onGroupClick = { name -> current = AutomationScreen.GroupDetail(name) },
            )

            AutomationScreen.AddData -> AddDataChoiceScreen(
                onBack = { current = AutomationScreen.Home },
                onPasteClick = { current = AutomationScreen.PasteData },
                onFileLoaded = { filename, text ->
                    val parsed = SmartParser.parse(text)
                    val lines = parsed.all.joinToString("\n") { (k, v) -> "$k: $v" }
                    VaultRepository.addGroup(filename, lines.ifBlank { text })
                    current = AutomationScreen.MyData
                },
                onGenerateClick = { current = AutomationScreen.GenerateData },
            )

            AutomationScreen.PasteData -> PasteDataScreen(
                onBack = { current = AutomationScreen.AddData },
                onSaved = { current = AutomationScreen.MyData },
            )

            AutomationScreen.GenerateData -> GenerateDataScreen(
                onBack = { current = AutomationScreen.AddData },
                onSaved = { current = AutomationScreen.MyData },
            )

            is AutomationScreen.GroupDetail -> GroupDetailScreen(
                groupName = c.groupName,
                onBack = { current = AutomationScreen.MyData },
            )

            AutomationScreen.CreateAutomation -> CreateAutomationScreen(
                onBack = { current = AutomationScreen.Home },
                onAddDataClick = { current = AutomationScreen.AddData },
                onStartConfig = { config ->
                    val formattedUrl = if (config.targetUrl.startsWith("http://") || config.targetUrl.startsWith("https://")) {
                        config.targetUrl
                    } else {
                        "https://${config.targetUrl}"
                    }

                    AutomationEngine.queueAutomationInBrowserTab(config.copy(targetUrl = formattedUrl))

                    val browserIntent = BrowserActivity.intent(
                        context = context,
                        launchSource = InAppNavigation,
                        queryExtra = formattedUrl,
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    context.startActivity(browserIntent)
                    (context as? Activity)?.finish()
                },
            )

            is AutomationScreen.Running -> AutomationRunningScreen(
                config = c.config,
                onFinished = { count ->
                    current = AutomationScreen.Completed(c.config.groupName, c.config.mode, count)
                },
                onAddNewDataRequested = {
                    current = AutomationScreen.PasteData
                },
                onCancel = {
                    AutomationEngine.stopAutomation()
                    current = AutomationScreen.Home
                },
            )

            is AutomationScreen.Completed -> AutomationCompletedScreen(
                groupName = c.groupName,
                mode = c.mode,
                totalFilled = c.count,
                onViewLogs = { current = AutomationScreen.GroupDetail(c.groupName) },
                onRunAgain = { current = AutomationScreen.CreateAutomation },
                onBack = { current = AutomationScreen.Home },
            )
        }
    }
}

// ============================================================================
// SHARED DUCKDUCKGO DESIGN SYSTEM COMPOSABLES
// ============================================================================

@Composable
fun AutomationTopBar(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DuckDuckGoTheme.colors.backgrounds.surface)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
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
                    fontFamily = VarelaRound,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        color = DuckDuckGoTheme.colors.text.secondary,
                        fontFamily = VarelaRound,
                        fontSize = 12.sp,
                    )
                }
            }
            if (trailingContent != null) {
                trailingContent()
            }
        }
    }
}

@Composable
fun DdgCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val borderColor = if (selected) {
        DuckDuckGoTheme.colors.brand.accentBlue
    } else {
        DuckDuckGoTheme.colors.text.secondary.copy(alpha = 0.14f)
    }
    val borderWidth = if (selected) 1.5.dp else 1.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(DuckDuckGoTheme.colors.backgrounds.surface, shape)
            .border(borderWidth, borderColor, shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun DdgPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconRes: Int? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = DuckDuckGoTheme.colors.brand.accentBlue,
            contentColor = Color.White,
            disabledContainerColor = DuckDuckGoTheme.colors.text.disabled.copy(alpha = 0.35f),
            disabledContentColor = Color.White.copy(alpha = 0.7f),
        ),
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            color = Color.White,
            fontFamily = VarelaRound,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun DdgSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconRes: Int? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, DuckDuckGoTheme.colors.brand.accentBlue),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = DuckDuckGoTheme.colors.backgrounds.surface,
            contentColor = DuckDuckGoTheme.colors.brand.accentBlue,
        ),
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = DuckDuckGoTheme.colors.brand.accentBlue,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            color = DuckDuckGoTheme.colors.brand.accentBlue,
            fontFamily = VarelaRound,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun DdgTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = TextStyle(
            color = DuckDuckGoTheme.colors.text.primary,
            fontFamily = VarelaRound,
            fontSize = 15.sp,
        ),
        label = if (label != null) {
            {
                Text(
                    text = label,
                    color = DuckDuckGoTheme.colors.text.secondary,
                    fontFamily = VarelaRound,
                    fontSize = 12.sp,
                )
            }
        } else {
            null
        },
        placeholder = if (placeholder != null) {
            {
                Text(
                    text = placeholder,
                    color = DuckDuckGoTheme.colors.text.secondary.copy(alpha = 0.7f),
                    fontFamily = VarelaRound,
                    fontSize = 14.sp,
                )
            }
        } else {
            null
        },
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedTextColor = DuckDuckGoTheme.colors.text.primary,
            unfocusedTextColor = DuckDuckGoTheme.colors.text.primary,
            focusedLabelColor = DuckDuckGoTheme.colors.brand.accentBlue,
            unfocusedLabelColor = DuckDuckGoTheme.colors.text.secondary,
            cursorColor = DuckDuckGoTheme.colors.brand.accentBlue,
            focusedContainerColor = DuckDuckGoTheme.colors.backgrounds.surface,
            unfocusedContainerColor = DuckDuckGoTheme.colors.backgrounds.surface,
            focusedIndicatorColor = DuckDuckGoTheme.colors.brand.accentBlue,
            unfocusedIndicatorColor = DuckDuckGoTheme.colors.text.secondary.copy(alpha = 0.35f),
        ),
    )
}

@Composable
fun DdgSectionHeader(
    title: String,
    subtitle: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = DuckDuckGoTheme.colors.text.primary,
            fontFamily = VarelaRound,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = DuckDuckGoTheme.colors.text.secondary,
                fontFamily = VarelaRound,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
fun DdgSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    DdgCard(onClick = { onCheckedChange(!checked) }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = DuckDuckGoTheme.colors.text.primary,
                    fontFamily = VarelaRound,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = DuckDuckGoTheme.colors.text.secondary,
                    fontFamily = VarelaRound,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = DuckDuckGoTheme.colors.brand.accentBlue,
                    uncheckedThumbColor = DuckDuckGoTheme.colors.text.secondary,
                    uncheckedTrackColor = DuckDuckGoTheme.colors.backgrounds.background,
                    uncheckedBorderColor = DuckDuckGoTheme.colors.text.secondary.copy(alpha = 0.5f),
                ),
            )
        }
    }
}

@Composable
fun DdgStatusBadge(
    text: String,
    isPositive: Boolean,
) {
    val bgColor = if (isPositive) {
        DuckDuckGoTheme.colors.brand.accentBlue.copy(alpha = 0.14f)
    } else {
        DuckDuckGoTheme.colors.text.secondary.copy(alpha = 0.16f)
    }
    val textColor = if (isPositive) {
        DuckDuckGoTheme.colors.brand.accentBlue
    } else {
        DuckDuckGoTheme.colors.text.secondary
    }
    Box(
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = textColor,
            fontFamily = VarelaRound,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}