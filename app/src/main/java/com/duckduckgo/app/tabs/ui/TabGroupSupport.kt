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

package com.duckduckgo.app.tabs.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.browser.databinding.ItemTabGroupGridBinding
import com.duckduckgo.app.tabs.model.TabEntity

data class TabGroupItem(
    val groupId: String,
    val name: String,
    val colorIndex: Int,
    val tabs: List<TabEntity>,
) : TabSwitcherItem("group_$groupId")

class TabGroupViewHolder(val binding: ItemTabGroupGridBinding) :
    TabSwitcherAdapter.TabSwitcherViewHolder(binding.root)

object TabGroupColors {
    private val colorRes = intArrayOf(
        R.color.tabGroupGrey, R.color.tabGroupBlue, R.color.tabGroupRed,
        R.color.tabGroupYellow, R.color.tabGroupGreen, R.color.tabGroupPink,
        R.color.tabGroupPurple, R.color.tabGroupCyan, R.color.tabGroupOrange,
    )

    fun color(context: Context, index: Int): Int =
        ContextCompat.getColor(context, colorRes[index.coerceIn(colorRes.indices)])
}

private const val PREVIEW_PLACEHOLDER = 0x33FFFFFF

fun TabGroupViewHolder.bindGroup(item: TabGroupItem) {
    val color = TabGroupColors.color(binding.root.context, item.colorIndex)
    binding.groupColorDot.backgroundTintList = ColorStateList.valueOf(color)
    binding.groupTitle.text = item.name
    binding.root.setCardBackgroundColor(ColorUtils.setAlphaComponent(color, 56))
    val cells = listOf(binding.groupPreview0, binding.groupPreview1, binding.groupPreview2, binding.groupPreview3)
    val overflow = item.tabs.size > 4
    val shown = if (overflow) 3 else item.tabs.size
    cells.forEachIndexed { i, cell ->
        val filled = i < shown || (overflow && i == 3)
        cell.setBackgroundColor(if (filled) PREVIEW_PLACEHOLDER else Color.TRANSPARENT)
    }
    binding.groupMoreCount.isVisible = overflow
    binding.groupMoreCount.text = "+${item.tabs.size - 3}"
}