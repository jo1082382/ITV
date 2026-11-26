package com.example.itv.presentation.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import com.example.itv.presentation.usage.StampRitualRequest
import com.example.itv.presentation.usage.TestCalendarRecordsScreen
import java.time.LocalDate

@Composable
internal fun HomeScreen(
    modifier: Modifier = Modifier,
    initialSelectedDate: LocalDate? = null,
    focusRequestVersion: Int = 0,
    stampRefreshVersion: Int = 0,
    calendarReloadVersion: Int = 0,
    daySyncTick: Int = 0,
    activeRitualDate: LocalDate? = null,
    listState: LazyListState = rememberLazyListState(),
    onCalendarCellBoundsResolverChanged: (((LocalDate) -> Rect?) -> Unit)? = null,
    onLaunchStampRitual: ((StampRitualRequest) -> Unit)? = null
) {
    TestCalendarRecordsScreen(
        modifier = modifier,
        initialSelectedDate = initialSelectedDate,
        focusRequestVersion = focusRequestVersion,
        stampRefreshVersion = stampRefreshVersion,
        calendarReloadVersion = calendarReloadVersion,
        daySyncTick = daySyncTick,
        activeRitualDate = activeRitualDate,
        listState = listState,
        onCalendarCellBoundsResolverChanged = onCalendarCellBoundsResolverChanged,
        onLaunchStampRitual = onLaunchStampRitual
    )
}
