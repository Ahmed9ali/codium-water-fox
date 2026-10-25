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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duckduckgo.app.browser.R
import com.duckduckgo.common.ui.compose.theme.DuckDuckGoTheme
import com.duckduckgo.mobile.android.R.drawable

@Composable
fun CreateAutomationScreen(
    onBack: () -> Unit,
    onAddDataClick: () -> Unit = {},
    onStartConfig: (AutomationConfig) -> Unit,
) {
    val groups = VaultRepository.getRecurringGroups()

    var targetUrl by remember { mutableStateOf("") }
    var selectedGroup by remember { mutableStateOf(groups.firstOrNull()?.name ?: "") }
    var useInlineManual by remember { mutableStateOf(groups.isEmpty()) }
    var manualGroupName by remember { mutableStateOf("") }
    var manualRawData by remember { mutableStateOf("") }

    var mode by remember { mutableStateOf("series") }

    var repeatCountText by remember { mutableStateOf("1") }
    var typingDelayText by remember { mutableStateOf("60") }
    var fieldDelayText by remember { mutableStateOf("120") }
    var settleTimeoutText by remember { mutableStateOf("2000") }

    var pauseOnCaptcha by remember { mutableStateOf(true) }
    var clearTabBetweenRuns by remember { mutableStateOf(true) }

    var showExhaustedPreDialog by remember { mutableStateOf(false) }

    val activeGroupObj = groups.firstOrNull { it.name == selectedGroup }
    val unusedProfilesInGroup = if (selectedGroup.isNotBlank()) {
        VaultRepository.unusedProfileCount(selectedGroup)
    } else {
        0
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DuckDuckGoTheme.colors.backgrounds.background),
    ) {
        AutomationTopBar(
            title = stringResource(R.string.automation_create_title),
            subtitle = "Configure website, dataset, speed & multi-step rules",
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            DdgSectionHeader(
                title = "1. Target Website",
                subtitle = "Enter the website URL or domain where forms will be automated",
            )
            Spacer(Modifier.height(8.dp))
            DdgTextField(
                value = targetUrl,
                onValueChange = { targetUrl = it },
                label = "Website URL",
                placeholder = "https://example.com/register",
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
            )

            Spacer(Modifier.height(22.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    DdgSectionHeader(
                        title = "2. Select Group / Dataset",
                        subtitle = "Used 1-by-1 and marked as used automatically",
                    )
                }
                Text(
                    text = if (useInlineManual) "Select Saved Group" else "+ New / Manual",
                    color = DuckDuckGoTheme.colors.brand.accentBlue,
                    fontFamily = VarelaRound,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { useInlineManual = !useInlineManual }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            if (useInlineManual || groups.isEmpty()) {
                DdgCard {
                    Text(
                        text = "Quick Manual Group Entry",
                        color = DuckDuckGoTheme.colors.text.primary,
                        fontFamily = VarelaRound,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Enter 'key: value' lines (e.g. email: a@b.com, password: Pass123!) or paste raw lines.",
                        color = DuckDuckGoTheme.colors.text.secondary,
                        fontFamily = VarelaRound,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    DdgTextField(
                        value = manualGroupName,
                        onValueChange = { manualGroupName = it },
                        label = "Group Name",
                        placeholder = "e.g. Signup Batch 1",
                        singleLine = true,
                    )
                    Spacer(Modifier.height(10.dp))
                    DdgTextField(
                        value = manualRawData,
                        onValueChange = { manualRawData = it },
                        label = "Accounts / Form Data (1 per line)",
                        placeholder = "email: user1@site.com\npassword: Secret123!\nusername: user_one\nphone: 9876543210",
                        singleLine = false,
                        modifier = Modifier.height(140.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    DdgSecondaryButton(
                        text = "Or Open Full Data Importer",
                        iconRes = drawable.ic_add_24,
                        onClick = onAddDataClick,
                    )
                }
            } else {
                groups.forEach { group ->
                    val isSelected = selectedGroup == group.name
                    val groupUnused = group.unusedCount
                    val groupUsed = group.usedCount
                    DdgCard(
                        selected = isSelected,
                        onClick = { selectedGroup = group.name },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { selectedGroup = group.name },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = DuckDuckGoTheme.colors.brand.accentBlue,
                                    unselectedColor = DuckDuckGoTheme.colors.text.secondary,
                                ),
                            )
                            Spacer(Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = group.name,
                                    color = DuckDuckGoTheme.colors.text.primary,
                                    fontFamily = VarelaRound,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "$groupUnused unused · $groupUsed used (${group.entries.size} total)",
                                    color = DuckDuckGoTheme.colors.text.secondary,
                                    fontFamily = VarelaRound,
                                    fontSize = 12.sp,
                                )
                            }
                            DdgStatusBadge(
                                text = if (groupUnused > 0) "$groupUnused left" else "All Used",
                                isPositive = groupUnused > 0,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                if (activeGroupObj != null && activeGroupObj.usedCount > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${activeGroupObj.usedCount} entries marked as used",
                            color = DuckDuckGoTheme.colors.text.secondary,
                            fontFamily = VarelaRound,
                            fontSize = 12.sp,
                        )
                        Text(
                            text = "Reset Used Status",
                            color = DuckDuckGoTheme.colors.brand.accentBlue,
                            fontFamily = VarelaRound,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable { VaultRepository.resetUsed(activeGroupObj.name) }
                                .padding(4.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(22.dp))

            DdgSectionHeader(
                title = "3. Data Selection Order (1-by-1)",
                subtitle = "Choose how records are pulled from your group on each cycle",
            )
            Spacer(Modifier.height(8.dp))

            OrderModeOption(
                title = "Top to Bottom (In Series)",
                desc = "Pulls unused entries sequentially starting from the top (#1, #2, #3...)",
                selected = mode == "series",
                onClick = { mode = "series" },
            )
            Spacer(Modifier.height(8.dp))
            OrderModeOption(
                title = "Bottom to Top (Reverse Series)",
                desc = "Pulls unused entries starting from the bottom of the list upwards",
                selected = mode == "bottom_up",
                onClick = { mode = "bottom_up" },
            )
            Spacer(Modifier.height(8.dp))
            OrderModeOption(
                title = "Random Order",
                desc = "Picks a random unused profile each run and marks it used",
                selected = mode == "random",
                onClick = { mode = "random" },
            )

            Spacer(Modifier.height(22.dp))

            DdgSectionHeader(
                title = "4. Target Cycles & Speed Delays",
                subtitle = "Control how many automations to run and human-like timing",
            )
            Spacer(Modifier.height(8.dp))

            DdgCard {
                DdgTextField(
                    value = repeatCountText,
                    onValueChange = { repeatCountText = it.filter { ch -> ch.isDigit() } },
                    label = "Target Automations to Run (Repeat Count)",
                    placeholder = "e.g. 10",
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                )
                if (!useInlineManual && selectedGroup.isNotBlank() && unusedProfilesInGroup > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Tip: Selected group currently has $unusedProfilesInGroup unused profile(s). If target exceeds this, you'll be asked to repeat or add new data.",
                        color = DuckDuckGoTheme.colors.text.secondary,
                        fontFamily = VarelaRound,
                        fontSize = 11.sp,
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DdgTextField(
                        value = typingDelayText,
                        onValueChange = { typingDelayText = it.filter { ch -> ch.isDigit() } },
                        label = "Typing Delay (ms)",
                        placeholder = "60",
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                    )
                    DdgTextField(
                        value = fieldDelayText,
                        onValueChange = { fieldDelayText = it.filter { ch -> ch.isDigit() } },
                        label = "Field Delay (ms)",
                        placeholder = "120",
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                    )
                }

                Spacer(Modifier.height(12.dp))

                DdgTextField(
                    value = settleTimeoutText,
                    onValueChange = { settleTimeoutText = it.filter { ch -> ch.isDigit() } },
                    label = "Next Form / Step Wait Timeout (ms)",
                    placeholder = "2000",
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                )
            }

            Spacer(Modifier.height(22.dp))

            DdgSectionHeader(
                title = "5. CAPTCHA & Session Isolation",
                subtitle = "Nanobrowser safety rules between multi-step transitions",
            )
            Spacer(Modifier.height(8.dp))

            DdgSwitchRow(
                title = "Pause for Human on CAPTCHA",
                subtitle = "Automatically pauses when reCAPTCHA, hCaptcha, or Turnstile appears so you can solve it",
                checked = pauseOnCaptcha,
                onCheckedChange = { pauseOnCaptcha = it },
            )

            Spacer(Modifier.height(8.dp))

            DdgSwitchRow(
                title = "Clear Tab Data After Every Automation",
                subtitle = "Clears cookies, DOM storage, cache, and history before starting the next profile",
                checked = clearTabBetweenRuns,
                onCheckedChange = { clearTabBetweenRuns = it },
            )

            Spacer(Modifier.height(28.dp))

            val canStart = targetUrl.trim().isNotEmpty() &&
                (if (useInlineManual) manualRawData.trim().isNotEmpty() else selectedGroup.isNotBlank())

            DdgPrimaryButton(
                text = stringResource(R.string.automation_start_automation),
                iconRes = drawable.ic_automation_24,
                enabled = canStart,
                onClick = {
                    val finalGroupName = if (useInlineManual) {
                        val name = manualGroupName.trim().ifBlank { "Manual ${System.currentTimeMillis() % 10000}" }
                        VaultRepository.addGroup(name, manualRawData, DataGroupType.RECURRING)
                        selectedGroup = name
                        useInlineManual = false
                        name
                    } else {
                        selectedGroup
                    }

                    if (!VaultRepository.hasUnusedData(finalGroupName)) {
                        showExhaustedPreDialog = true
                        return@DdgPrimaryButton
                    }

                    // FIXED: Changed from groupName = finalGroupName to groupNames = listOf(finalGroupName)
                    val config = AutomationConfig(
                        targetUrl = targetUrl.trim(),
                        groupNames = listOf(finalGroupName),
                        mode = mode,
                        targetRepeatCount = (repeatCountText.toIntOrNull() ?: 1).coerceAtLeast(1),
                        typingDelayMs = (typingDelayText.toLongOrNull() ?: 60L).coerceAtLeast(0L),
                        fieldDelayMs = (fieldDelayText.toLongOrNull() ?: 120L).coerceAtLeast(0L),
                        settleTimeoutMs = (settleTimeoutText.toLongOrNull() ?: 2000L).coerceAtLeast(500L),
                        pauseOnCaptcha = pauseOnCaptcha,
                        clearTabBetweenRuns = clearTabBetweenRuns,
                    )
                    onStartConfig(config)
                },
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showExhaustedPreDialog) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DuckDuckGoTheme.colors.backgrounds.background.copy(alpha = 0.88f))
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DdgCard {
                Text(
                    text = "All Data in '$selectedGroup' Has Been Used",
                    color = DuckDuckGoTheme.colors.text.primary,
                    fontFamily = VarelaRound,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Would you like to reset the used status to start from the beginning, or add new data?",
                    color = DuckDuckGoTheme.colors.text.secondary,
                    fontFamily = VarelaRound,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(20.dp))
                DdgPrimaryButton(
                    text = "Reset Used & Start",
                    onClick = {
                        VaultRepository.resetUsed(selectedGroup)
                        showExhaustedPreDialog = false
                        // FIXED: Changed from groupName = selectedGroup to groupNames = listOf(selectedGroup)
                        onStartConfig(
                            AutomationConfig(
                                targetUrl = targetUrl.trim(),
                                groupNames = listOf(selectedGroup),
                                mode = mode,
                                targetRepeatCount = (repeatCountText.toIntOrNull() ?: 1).coerceAtLeast(1),
                                typingDelayMs = (typingDelayText.toLongOrNull() ?: 60L).coerceAtLeast(0L),
                                fieldDelayMs = (fieldDelayText.toLongOrNull() ?: 120L).coerceAtLeast(0L),
                                settleTimeoutMs = (settleTimeoutText.toLongOrNull() ?: 2000L).coerceAtLeast(500L),
                                pauseOnCaptcha = pauseOnCaptcha,
                                clearTabBetweenRuns = clearTabBetweenRuns,
                            ),
                        )
                    },
                )
                Spacer(Modifier.height(10.dp))
                DdgSecondaryButton(
                    text = "Add New Data",
                    onClick = {
                        showExhaustedPreDialog = false
                        onAddDataClick()
                    },
                )
                Spacer(Modifier.height(10.dp))
                DdgSecondaryButton(
                    text = "Cancel",
                    onClick = { showExhaustedPreDialog = false },
                )
            }
        }
    }
}

@Composable
private fun OrderModeOption(
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DdgCard(selected = selected, onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = DuckDuckGoTheme.colors.brand.accentBlue,
                    unselectedColor = DuckDuckGoTheme.colors.text.secondary,
                ),
            )
            Spacer(Modifier.width(6.dp))
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
                    text = desc,
                    color = DuckDuckGoTheme.colors.text.secondary,
                    fontFamily = VarelaRound,
                    fontSize = 12.sp,
                )
            }
        }
    }
}