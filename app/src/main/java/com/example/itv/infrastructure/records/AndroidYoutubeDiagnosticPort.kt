package com.example.itv.infrastructure.records

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.itv.domain.records.YoutubeDiagnosticPort
import com.example.itv.domain.records.YoutubeDiagnosticSnapshot
import com.example.itv.domain.consent.ConsentStore
import com.example.itv.infrastructure.observation.youtube.YoutubeProbe
import com.example.itv.infrastructure.observation.accessibility.WritingGuard

/** Maintains existing main-thread diagnostic behavior. No new collection or persistence. */
internal class AndroidYoutubeDiagnosticPort(private val consent: ConsentStore) : YoutubeDiagnosticPort {
    override fun snapshot() = YoutubeDiagnosticSnapshot(WritingGuard.connected, YoutubeProbe.enabled, YoutubeProbe.report())
    override suspend fun canStart(): Boolean = withContext(Dispatchers.IO) {
        consent.read().granted && WritingGuard.connected
    }
    override fun start() {
        check(WritingGuard.connected) { "접근성 연결이 해제되었습니다. 다시 확인해 주세요." }
        YoutubeProbe.clearBaseline(); YoutubeProbe.enabled = true
        YoutubeProbe.status = "YouTube 쇼츠를 열어 주세요"
    }
    override fun stop() {
        YoutubeProbe.enabled = false; YoutubeProbe.clearBaseline(); YoutubeProbe.status = "감시 중지"
    }
    override fun reset() { YoutubeProbe.reset() }
}
