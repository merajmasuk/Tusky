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
import com.keylesspalace.tusky.db.AccountManager
import com.keylesspalace.tusky.util.HttpHeaderLink
import com.keylesspalace.tusky.util.dec
import com.keylesspalace.tusky.util.toViewData
import com.keylesspalace.tusky.viewdata.StatusViewData

@ExperimentalPagingApi
class NetworkTimelineRemoteMediator(
    private val accountManager: AccountManager,
    private val viewModel: NetworkTimelineViewModel
) : RemoteMediator<String, StatusViewData>() {

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<String, StatusViewData>
    ): MediatorResult {

        val activeAccount = accountManager.activeAccount!!

        try {
            val statusResponse = when (loadType) {
                LoadType.REFRESH -> {
                    viewModel.fetchStatusesForKind(null, null, limit = state.config.pageSize)
                }
                LoadType.PREPEND -> {
                    return MediatorResult.Success(endOfPaginationReached = true)
                }
                LoadType.APPEND -> {
                    val maxId = viewModel.nextKey
                    viewModel.fetchStatusesForKind(maxId, null, limit = state.config.pageSize)
                }
            }

            val statuses = statusResponse.body()!!

            val data = statuses.map { status ->
                status.toViewData(
                    alwaysShowSensitiveMedia = !activeAccount.alwaysShowSensitiveMedia && status.actionableStatus.sensitive,
                    alwaysOpenSpoiler = activeAccount.alwaysOpenSpoiler
                )
            }

            val overlappedStatuses = if (statuses.isNotEmpty()) {
                viewModel.statusData.removeAll { statusViewData ->
                    statuses.find { status -> status.id == statusViewData.asStatusOrNull()?.id } != null
                }
            } else {
                false
            }

            if (loadType == LoadType.REFRESH) {

                viewModel.statusData.addAll(0, data)

                if (!overlappedStatuses) {
                    viewModel.statusData.add(statuses.size, StatusViewData.Placeholder(statuses.last().id.dec(), false))
                }
            } else {
                val linkHeader = statusResponse.headers()["Link"]
                val links = HttpHeaderLink.parse(linkHeader)
                val nextId = HttpHeaderLink.findByRelationType(links, "next")?.uri?.getQueryParameter("max_id")

                viewModel.nextKey = nextId

                viewModel.statusData.addAll(data)
            }

            viewModel.currentSource?.invalidate()
            return MediatorResult.Success(endOfPaginationReached = statuses.isEmpty())
        } catch (e: Exception) {
            return MediatorResult.Error(e)
        }
    }
}