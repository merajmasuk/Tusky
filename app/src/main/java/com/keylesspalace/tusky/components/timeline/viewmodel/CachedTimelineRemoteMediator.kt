/* Copyright 2021 Tusky Contributors
 *
 * This file is a part of Tusky.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 *
 * Tusky is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Tusky; if not,
 * see <http://www.gnu.org/licenses>. */

package com.keylesspalace.tusky.components.timeline.viewmodel

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import com.google.gson.Gson
import com.keylesspalace.tusky.components.timeline.Placeholder
import com.keylesspalace.tusky.components.timeline.toEntity
import com.keylesspalace.tusky.db.AccountManager
import com.keylesspalace.tusky.db.AppDatabase
import com.keylesspalace.tusky.db.TimelineStatusWithAccount
import com.keylesspalace.tusky.network.MastodonApi
import com.keylesspalace.tusky.util.dec
import kotlinx.coroutines.rx3.await

@ExperimentalPagingApi
class CachedTimelineRemoteMediator(
    private val accountManager: AccountManager,
    private val api: MastodonApi,
    private val db: AppDatabase,
    private val gson: Gson
) : RemoteMediator<Int, TimelineStatusWithAccount>() {

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, TimelineStatusWithAccount>
    ): MediatorResult {

        val activeAccount = accountManager.activeAccount!!

        try {
            val statusResponse = when (loadType) {
                LoadType.REFRESH -> {
                    api.homeTimeline(limit = state.config.pageSize).await()
                }
                LoadType.PREPEND -> {
                    return MediatorResult.Success(endOfPaginationReached = true)
                }
                LoadType.APPEND -> {
                    val maxId = state.pages.findLast { it.data.isNotEmpty() }?.data?.lastOrNull()?.status?.serverId
                    api.homeTimeline(maxId = maxId, limit = state.config.pageSize).await()
                }
            }

            val statuses = statusResponse.body()!!

            val timelineDao = db.timelineDao()

            db.withTransaction {
                val overlappedStatuses = if (statuses.isNotEmpty()) {
                    timelineDao.deleteRange(activeAccount.id, statuses.last().id, statuses.first().id)
                } else {
                    0
                }

                for (status in statuses) {
                    timelineDao.insertAccount(status.account.toEntity(activeAccount.id, gson))
                    status.reblog?.account?.toEntity(activeAccount.id, gson)?.let { rebloggedAccount ->
                        timelineDao.insertAccount(rebloggedAccount)
                    }
                    timelineDao.insertStatus(
                        status.toEntity(
                            timelineUserId = activeAccount.id,
                            gson = gson,
                            expanded = activeAccount.alwaysOpenSpoiler,
                            contentHidden = !activeAccount.alwaysShowSensitiveMedia && status.actionableStatus.sensitive,
                            contentCollapsed = false
                        )
                    )
                }

                if (loadType == LoadType.REFRESH && overlappedStatuses == 0) {
                    timelineDao.insertStatus(
                        Placeholder(statuses.last().id.dec()).toEntity(activeAccount.id)
                    )
                }
            }
            return MediatorResult.Success(endOfPaginationReached = statuses.isEmpty())
        } catch (e: Exception) {
            return MediatorResult.Error(e)
        }
    }
}