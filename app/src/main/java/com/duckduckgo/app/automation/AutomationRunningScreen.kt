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

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Observer
import com.duckduckgo.app.browser.R
import com.duckduckgo.common.ui.compose.theme.DuckDuckGoTheme

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AutomationRunningScreen(
    config: AutomationConfig,
    onFinished: (count: Int) -> Unit,
    onAddNewDataRequested: () -> Unit = {},
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var engineState by remember { mutableStateOf(AutomationState.NAVIGATING) }
    var completed by remember { mutableIntStateOf(0) }
    var totalTarget by remember { mutableIntStateOf(config.targetRepeatCount) }
    var statusText by remember { mutableStateOf("Initializing browser engine...") }
    var isCaptchaWaiting by remember { mutableStateOf(false) }
    var isDataExhausted by remember { mutableStateOf(false) }

    // Create a dedicated live WebView so the user can watch every step & solve CAPTCHAs directly
    val liveWebView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadsImagesAutomatically = true
                useWideViewPort = true
                loadWithOverviewMode = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                javaScriptCanOpenWindowsAutomatically = true
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    AutomationEngine.injectScanner()
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner, liveWebView) {
        val stateObserver = Observer<AutomationState> { engineState = it }
        val completedObserver = Observer<Int> { completed = it }
        val totalObserver = Observer<Int> { totalTarget = it }
        val statusObserver = Observer<String> { statusText = it }
        val captchaObserver = Observer<Boolean> { isCaptchaWaiting = it }
        val exhaustedObserver = Observer<Boolean> { isDataExhausted = it }

        AutomationEngine.state.observe(lifecycleOwner, stateObserver)
        AutomationEngine.completedCount.observe(lifecycleOwner, completedObserver)
        AutomationEngine.totalTargetCount.observe(lifecycleOwner, totalObserver)
        AutomationEngine.statusMessage.observe(lifecycleOwner, statusObserver)
        AutomationEngine.captchaState.observe(lifecycleOwner, captchaObserver)
        AutomationEngine.dataExhaustedState.observe(lifecycleOwner, exhaustedObserver)

        AutomationEngine.onAutomationFinished = { finalCount ->
            onFinished(finalCount)
        }
        AutomationEngine.onDataExhaustedAction = {
            onAddNewDataRequested()
        }

        AutomationEngine.attachWebView(liveWebView)
        AutomationEngine.startAutomation(config)

        onDispose {
            AutomationEngine.state.removeObserver(stateObserver)
            AutomationEngine.completedCount.removeObserver(completedObserver)
            AutomationEngine.totalTargetCount.removeObserver(totalObserver)
            AutomationEngine.statusMessage.removeObserver(statusObserver)
            AutomationEngine.captchaState.removeObserver(captchaObserver)
            AutomationEngine.dataExhaustedState.removeObserver(exhaustedObserver)
            AutomationEngine.onAutomationFinished = null
            AutomationEngine.onDataExhaustedAction = null
            liveWebView.stopLoading()
            liveWebView.destroy()
        }
    }

    val unusedRemaining = VaultRepository.unusedProfileCount(config.groupName)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DuckDuckGoTheme.colors.backgrounds.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AutomationTopBar(
                title = stringResource(R.string.automation_running_title),
                subtitle = "${config.groupName} · ${config.targetUrl}",
                onBack = {
                    AutomationEngine.stopAutomation()
                    onCancel()
                },
            )

            // Compact Nanobrowser Status & Progress Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DuckDuckGoTheme.colors.backgrounds.surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = statusText,
                            color = DuckDuckGoTheme.colors.text.primary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Cycle $completed / $totalTarget · $unusedRemaining unused left in group",
                            color = DuckDuckGoTheme.colors.text.secondary,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    DdgStatusBadge(
                        text = engineState.name.replace("_", " "),
                        isPositive = engineState != AutomationState.WAITING_CAPTCHA_HUMAN &&
                            engineState != AutomationState.WAITING_DATA_EXHAUSTED &&
                            engineState != AutomationState.PAUSED,
                    )
                }

                Spacer(Modifier.height(10.dp))

                LinearProgressIndicator(
                    progress = {
                        if (totalTarget <= 0) 0f else (completed.toFloat() / totalTarget.toFloat()).coerceIn(0f, 1f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = DuckDuckGoTheme.colors.brand.accentBlue,
                    trackColor = DuckDuckGoTheme.colors.backgrounds.background,
                )

                // CAPTCHA Human Takeover Action Bar
                if (isCaptchaWaiting || engineState == AutomationState.WAITING_CAPTCHA_HUMAN) {
                    Spacer(Modifier.height(12.dp))
                    DdgCard(selected = true) {
                        Text(
                            text = "Human Action Required: Solve CAPTCHA Below",
                            color = DuckDuckGoTheme.colors.text.primary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Complete the CAPTCHA verification inside the webpage below, then tap Continue to resume filling.",
                            color = DuckDuckGoTheme.colors.text.secondary,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        DdgPrimaryButton(
                            text = "I Solved the CAPTCHA — Continue",
                            onClick = { AutomationEngine.continueAfterCaptchaResolution() },
                        )
                    }
                }
            }

            // Live Interactive WebView Viewport
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(
                        1.dp,
                        DuckDuckGoTheme.colors.text.secondary.copy(alpha = 0.2f),
                        RoundedCornerShape(12.dp),
                    ),
            ) {
                AndroidView(
                    factory = { liveWebView },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Bottom Control Bar: Pause / Resume / Stop
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DuckDuckGoTheme.colors.backgrounds.surface)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (engineState == AutomationState.PAUSED) {
                    DdgPrimaryButton(
                        text = "Resume",
                        modifier = Modifier.weight(1f),
                        onClick = { AutomationEngine.resumeAutomation() },
                    )
                } else {
                    DdgSecondaryButton(
                        text = stringResource(R.string.automation_pause),
                        modifier = Modifier.weight(1f),
                        onClick = { AutomationEngine.pauseAutomation() },
                    )
                }

                DdgSecondaryButton(
                    text = "Stop & Exit",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        AutomationEngine.stopAutomation()
                        onCancel()
                    },
                )
            }
        }

        // Data Exhausted Mid-Run Modal Overlay
        if (isDataExhausted || engineState == AutomationState.WAITING_DATA_EXHAUSTED) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DuckDuckGoTheme.colors.backgrounds.background.copy(alpha = 0.9f))
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                DdgCard {
                    Text(
                        text = "Group Data Finished ($completed / $totalTarget Completed)",
                        color = DuckDuckGoTheme.colors.text.primary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "All entries in '${config.groupName}' have been used and marked. How would you like to finish the remaining ${totalTarget - completed} cycle(s)?",
                        color = DuckDuckGoTheme.colors.text.secondary,
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(20.dp))
                    DdgPrimaryButton(
                        text = "Repeat With Same Data",
                        onClick = { AutomationEngine.handleDataExhaustedChoice("repeat_same") },
                    )
                    Spacer(Modifier.height(10.dp))
                    DdgSecondaryButton(
                        text = "Add New Data to Vault",
                        onClick = { AutomationEngine.handleDataExhaustedChoice("add_new") },
                    )
                    Spacer(Modifier.height(10.dp))
                    DdgSecondaryButton(
                        text = "Finish Now ($completed Completed)",
                        onClick = {
                            AutomationEngine.stopAutomation()
                            onFinished(completed)
                        },
                    )
                }
            }
        }
    }
}
