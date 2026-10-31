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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class TabGroupsDao {

    @Query("select * from tab_groups order by createdAt")
    abstract fun groups(): Flow<List<TabGroupEntity>>

    @Query("select * from tab_group_members order by position")
    abstract fun members(): Flow<List<TabGroupMemberEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertGroup(group: TabGroupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertMember(member: TabGroupMemberEntity)

    @Query("delete from tab_group_members where tabId = :tabId")
    abstract suspend fun removeTab(tabId: String)

    @Query("delete from tab_group_members where groupId = :groupId")
    abstract suspend fun removeAllMembers(groupId: String)

    @Query("delete from tab_groups where groupId = :groupId")
    abstract suspend fun deleteGroup(groupId: String)

    @Query("update tab_groups set name = :name, colorIndex = :colorIndex where groupId = :groupId")
    abstract suspend fun updateGroup(groupId: String, name: String, colorIndex: Int)

    @Transaction
    open suspend fun ungroup(groupId: String) {
        removeAllMembers(groupId)
        deleteGroup(groupId)
    }
}
