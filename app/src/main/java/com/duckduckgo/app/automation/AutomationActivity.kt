/*
 * Copyright (c) 2025 DuckDuckGo
 * ... license header ...
 */

package com.duckduckgo.app.automation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.duckduckgo.common.ui.compose.theme.DuckDuckGoTheme

class AutomationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VaultRepository.init(applicationContext)
        setContent {
            DuckDuckGoTheme {
                AutomationNavHost(onBack = { finish() })
            }
        }
    }
}