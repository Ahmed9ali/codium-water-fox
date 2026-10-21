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

/**
 * Which panel the Automation HUD overlay is currently showing.
 *
 * Kept separate from [AutomationState] on purpose:
 *  - [AutomationState] describes what the *runner coroutine* is doing.
 *  - [HudMode] describes what the *overlay* is rendering.
 *
 * A menu can be open (`Menu`) while `AutomationState` is `IDLE`.
 * A recording can be active (`Recording`) while `AutomationState` is still `IDLE`.
 */
enum class HudMode {
    Hidden,
    Menu,
    Recording,
    Running,
}