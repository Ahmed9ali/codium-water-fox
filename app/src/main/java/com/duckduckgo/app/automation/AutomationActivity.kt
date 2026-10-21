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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.core.view.WindowCompat
import com.duckduckgo.common.ui.compose.theme.DuckDuckGoTheme

class AutomationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        VaultRepository.init(applicationContext)
        AutomationEngine.init(applicationContext)

        val isLight = resolveDuckDuckGoIsLight(applicationContext)

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
}

fun resolveDuckDuckGoIsLight(context: Context): Boolean {
    // 1. DuckDuckGo sets AppCompatDelegate.setDefaultNightMode on startup based on its internal theme
    when (AppCompatDelegate.getDefaultNightMode()) {
        AppCompatDelegate.MODE_NIGHT_NO -> return true
        AppCompatDelegate.MODE_NIGHT_YES -> return false
    }

    // 2. Check DuckDuckGo's SharedPreferences files for GLOBAL_THEME
    val prefNames = listOf(
        "com.duckduckgo.app.settings_activity.settings",
        "${context.packageName}_preferences",
    )
    for (name in prefNames) {
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val theme = prefs.getString("GLOBAL_THEME", null)?.uppercase()
        if (theme == "LIGHT") return true
        if (theme == "DARK") return false
    }

    // 3. Fallback to current configuration
    val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return uiMode != Configuration.UI_MODE_NIGHT_YES
}

/**
 * Forces [LocalConfiguration] to match DuckDuckGo's Light/Dark setting and overrides
 * DuckDuckGoTheme's debug cursive/magenta Material3 defaults with clean SansSerif typography and DDG colors.
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

    val cleanTypography = Typography(
        displayLarge = TextStyle(fontFamily = FontFamily.SansSerif),
        displayMedium = TextStyle(fontFamily = FontFamily.SansSerif),
        displaySmall = TextStyle(fontFamily = FontFamily.SansSerif),
        headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif),
        headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif),
        headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif),
        titleLarge = TextStyle(fontFamily = FontFamily.SansSerif),
        titleMedium = TextStyle(fontFamily = FontFamily.SansSerif),
        titleSmall = TextStyle(fontFamily = FontFamily.SansSerif),
        bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif),
        bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif),
        bodySmall = TextStyle(fontFamily = FontFamily.SansSerif),
        labelLarge = TextStyle(fontFamily = FontFamily.SansSerif),
        labelMedium = TextStyle(fontFamily = FontFamily.SansSerif),
        labelSmall = TextStyle(fontFamily = FontFamily.SansSerif),
    )

    CompositionLocalProvider(LocalConfiguration provides patchedConfig) {
        DuckDuckGoTheme {
            MaterialTheme(
                typography = cleanTypography,
                colorScheme = MaterialTheme.colorScheme.copy(
                    primary = DuckDuckGoTheme.colors.brand.accentBlue,
                    onPrimary = Color.White,
                    secondary = DuckDuckGoTheme.colors.brand.accentBlue,
                    onSecondary = Color.White,
                    background = DuckDuckGoTheme.colors.backgrounds.background,
                    onBackground = DuckDuckGoTheme.colors.text.primary,
                    surface = DuckDuckGoTheme.colors.backgrounds.surface,
                    onSurface = DuckDuckGoTheme.colors.text.primary,
                    surfaceVariant = DuckDuckGoTheme.colors.backgrounds.surface,
                    onSurfaceVariant = DuckDuckGoTheme.colors.text.secondary,
                    outline = DuckDuckGoTheme.colors.text.secondary,
                ),
            ) {
                ProvideTextStyle(
                    value = TextStyle(
                        fontFamily = FontFamily.SansSerif,
                        color = DuckDuckGoTheme.colors.text.primary,
                    ),
                ) {
                    content()
                }
            }
        }
    }
}
