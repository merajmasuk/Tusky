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

import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import androidx.room.withTransaction
import com.google.gson.Gson
import com.keylesspalace.tusky.appstore.BookmarkEvent
import com.keylesspalace.tusky.appstore.EventHub
import com.keylesspalace.tusky.appstore.FavoriteEvent
import com.keylesspalace.tusky.appstore.PinEvent
import com.keylesspalace.tusky.appstore.ReblogEvent
import com.keylesspalace.tusky.components.timeline.Placeholder
import com.keylesspalace.tusky.components.timeline.toEntity
import com.keylesspalace.tusky.components.timeline.toViewData
import com.keylesspalace.tusky.db.AccountManager
import com.keylesspalace.tusky.db.AppDatabase
import com.keylesspalace.tusky.entity.Poll
import com.keylesspalace.tusky.network.FilterModel
import com.keylesspalace.tusky.network.MastodonApi
import com.keylesspalace.tusky.network.TimelineCases
import com.keylesspalace.tusky.util.dec
import com.keylesspalace.tusky.util.inc
import com.keylesspalace.tusky.viewdata.StatusViewData
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.await
import javax.inject.Inject

class CachedTimelineViewModel @Inject constructor(
    timelineCases: TimelineCases,
    private val api: MastodonApi,
    eventHub: EventHub,
    private val accountManager: AccountManager,
    sharedPreferences: SharedPreferences,
    filterModel: FilterModel,
    private val db: AppDatabase,
    private val gson: Gson
) : TimelineViewModel(timelineCases, api, eventHub, accountManager, sharedPreferences, filterModel) {

    @ExperimentalPagingApi
    override val statuses = Pager(
        config = PagingConfig(pageSize = LOAD_AT_ONCE),
        remoteMediator = CachedTimelineRemoteMediator(accountManager, api, db, gson),
        pagingSourceFactory = { db.timelineDao().getStatusesForAccount(accountManager.activeAccount!!.id) }
    ).flow
        .map { pagingData ->
            pagingData.map { timelineStatus ->
                timelineStatus.toViewData(gson)
            }
        }
        .map { pagingData ->
            pagingData.filter { statusViewData ->
                !shouldFilterStatus(statusViewData)
            }
        }
        .cachedIn(viewModelScope)

    override fun updatePoll(newPoll: Poll, status: StatusViewData.Concrete) {
        // handled by CacheUpdater
    }

    override fun changeExpanded(expanded: Boolean, status: StatusViewData.Concrete) {
        viewModelScope.launch {
            db.timelineDao().setExpanded(accountManager.activeAccount!!.id, status.id, expanded)
        }
    }

    override fun changeContentHidden(isShowing: Boolean, status: StatusViewData.Concrete) {
        viewModelScope.launch {
            db.timelineDao().setContentHidden(accountManager.activeAccount!!.id, status.id, isShowing)
        }
    }

    override fun changeContentCollapsed(isCollapsed: Boolean, status: StatusViewData.Concrete) {
        viewModelScope.launch {
            db.timelineDao().setContentCollapsed(accountManager.activeAccount!!.id, status.id, isCollapsed)
        }
    }

    override fun removeAllByAccountId(accountId: String) {
        viewModelScope.launch {
            db.timelineDao().removeAllByUser(accountManager.activeAccount!!.id, accountId)
        }
    }

    override fun removeAllByInstance(instance: String) {
        viewModelScope.launch {
            // Todo
        }
    }

    override fun removeStatusWithId(id: String) {
        // handled by CacheUpdater
    }

    override fun loadMore(placeholderId: String) {
        viewModelScope.launch {
            val response = api.homeTimeline(maxId = placeholderId.inc(), limit = 20).await()

            val statuses = response.body()!!

            val timelineDao = db.timelineDao()

            val activeAccount = accountManager.activeAccount!!

            db.withTransaction {

                timelineDao.delete(activeAccount.id, placeholderId)

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

                if (overlappedStatuses == 0) {
                    timelineDao.insertStatus(
                        Placeholder(statuses.last().id.dec()).toEntity(activeAccount.id)
                    )
                }
            }
        }
    }

    override fun handleReblogEvent(reblogEvent: ReblogEvent) {
        // handled by CacheUpdater
    }

    override fun handleFavEvent(favEvent: FavoriteEvent) {
        // handled by CacheUpdater
    }

    override fun handleBookmarkEvent(bookmarkEvent: BookmarkEvent) {
        // handled by CacheUpdater
    }

    override fun handlePinEvent(pinEvent: PinEvent) {
        // handled by CacheUpdater
    }

    override fun fullReload() {
        viewModelScope.launch {
            val activeAccount = accountManager.activeAccount!!
            db.runInTransaction {
                db.timelineDao().removeAllForAccount(activeAccount.id)
                db.timelineDao().removeAllUsersForAccount(activeAccount.id)
            }
        }
    }
}
