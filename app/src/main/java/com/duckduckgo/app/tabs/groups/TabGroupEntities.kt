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

package com.duckduckgo.app.tabs.groups

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "tab_groups")
data class TabGroupEntity(
    @PrimaryKey val groupId: String,
    val name: String,
    val colorIndex: Int,
    val createdAt: Long,
)

@Entity(tableName = "tab_group_members", indices = [Index("groupId")])
data class TabGroupMemberEntity(
    @PrimaryKey val tabId: String,
    val groupId: String,
    val position: Int,
)
