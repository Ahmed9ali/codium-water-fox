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

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.duckduckgo.common.ui.compose.theme.DuckDuckGoTheme

class AutomationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        applyDuckDuckGoNightMode()
        super.onCreate(savedInstanceState)

        VaultRepository.init(applicationContext)
        AutomationEngine.init(applicationContext)

        val isLight = isDuckDuckGoLightMode()

        setContent {
            AutomationThemeWrapper(isLightMode = isLight) {
                val view = LocalView.current
                if (!view.isInEditMode) {
                    SideEffect {
                        window.statusBarColor = if (isLight) {
                            AndroidColor.parseColor("#FFFFFF")
                        } else {
                            AndroidColor.parseColor("#1C1C1E")
                        }
                        window.navigationBarColor = if (isLight) {
                            AndroidColor.parseColor("#F2F2F7")
                        } else {
                            AndroidColor.parseColor("#111111")
                        }
                        val controller = WindowCompat.getInsetsController(window, view)
                        controller.isAppearanceLightStatusBars = isLight
                        controller.isAppearanceLightNavigationBars = isLight
                    }
                }

                AutomationNavHost(onBack = { finish() })
            }
        }
    }

    /**
     * Reads DuckDuckGo's internal theme setting ("LIGHT", "DARK", or "SYSTEM_DEFAULT")
     * from SharedPreferences so AutomationActivity never mismatches the browser's theme.
     */
    private fun isDuckDuckGoLightMode(): Boolean {
        val prefs = getSharedPreferences("com.duckduckgo.app.settings_activity.settings", Context.MODE_PRIVATE)
        val savedTheme = prefs.getString("GLOBAL_THEME", null) ?: "SYSTEM_DEFAULT"
        return when (savedTheme.uppercase()) {
            "LIGHT" -> true
            "DARK" -> false
            else -> {
                when (AppCompatDelegate.getDefaultNightMode()) {
                    AppCompatDelegate.MODE_NIGHT_NO -> true
                    AppCompatDelegate.MODE_NIGHT_YES -> false
                    else -> {
                        val uiMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                        uiMode != Configuration.UI_MODE_NIGHT_YES
                    }
                }
            }
        }
    }

    private fun applyDuckDuckGoNightMode() {
        val prefs = getSharedPreferences("com.duckduckgo.app.settings_activity.settings", Context.MODE_PRIVATE)
        when (prefs.getString("GLOBAL_THEME", "SYSTEM_DEFAULT")?.uppercase()) {
            "LIGHT" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "DARK" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }
}

/**
 * Forces [LocalConfiguration] uiMode to match DuckDuckGo's chosen theme before calling [DuckDuckGoTheme],
 * because [DuckDuckGoTheme] internally reads `isSystemInDarkTheme()` from `LocalConfiguration.current`.
 */
@Composable
fun AutomationThemeWrapper(
    isLightMode: Boolean,
    content: @Composable () -> Unit,
) {
    val currentConfig = LocalConfiguration.current
    val patchedConfig = Configuration(currentConfig).apply {
        val nightBits = if (isLightMode) Configuration.UI_MODE_NIGHT_NO else Configuration.UI_MODE_NIGHT_YES
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightBits
    }
    CompositionLocalProvider(LocalConfiguration provides patchedConfig) {
        DuckDuckGoTheme {
            content()
        }
    }
}
