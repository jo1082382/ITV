package com.example.itv.infrastructure.records

import android.content.Context
import com.example.itv.domain.records.*
import com.example.itv.domain.consent.ConsentStore
import com.example.itv.infrastructure.storage.shortform.ShortformDailyStore
import com.example.itv.infrastructure.observation.accessibility.WritingGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** ShortformDailyStore alone supplies user-facing aggregates. Legacy reels are not added. */
internal class AndroidShortformRecordSource(context: Context,
    private val consentStore: ConsentStore) : ShortformRecordSource {
    private val context = context.applicationContext
    override suspend fun deleteAll() { ShortformDailyStore.deleteAllAndAwait(context) }
    override suspend fun today(): ShortformToday = withContext(Dispatchers.IO) {
        if (!consentStore.read().granted) return@withContext ShortformToday(notice = "수집 동의가 필요합니다")
        fun convert(day: ShortformDailyStore.Day) = ShortformDay(day.ms, day.transitions, day.observed)
        ShortformToday(
            instagram = convert(ShortformDailyStore.today(context, "instagram")),
            youtube = convert(ShortformDailyStore.today(context, "youtube")),
            ready = ShortformDailyStore.isReady(),
            storage = ShortformDailyStore.storageStatus(),
            notice = if (!WritingGuard.connected) "접근성 연결 안 됨 · 숏폼 관측 중단" else null)
    }
}
