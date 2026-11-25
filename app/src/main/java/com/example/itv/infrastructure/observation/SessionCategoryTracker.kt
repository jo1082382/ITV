package com.example.itv.infrastructure.observation

import com.example.itv.domain.coaching.UsageWeightTier
import com.example.itv.domain.jev.JevSettings
import com.example.itv.infrastructure.observation.instagram.InstagramScreenMode
import com.example.itv.infrastructure.observation.instagram.InstagramTracker
import com.example.itv.infrastructure.observation.instagram.ReelsProbe
import com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker
import com.example.itv.infrastructure.observation.youtube.YoutubeProbe
import com.example.itv.infrastructure.observation.youtube.YoutubeWatchMode
import java.util.concurrent.ConcurrentHashMap

/**
 * HUD 및 개입 평가에서 실시간으로 추적하는 7개 표준 앱 카테고리
 */
enum class SessionAppCategory(
    val shortLabel: String,
    val fullLabel: String,
    val weightTier: UsageWeightTier
) {
    YOUTUBE_SHORTS("쇼츠", "유튜브 쇼츠", UsageWeightTier.FULL_1_TO_1),
    INSTA_REELS("릴스", "인스타 릴스", UsageWeightTier.FULL_1_TO_1),
    YOUTUBE_LONGFORM("롱폼", "유튜브 롱폼", UsageWeightTier.LOW_1_TO_3),
    FEED_SNS("피드/SNS", "피드/SNS", UsageWeightTier.MID_1_TO_2),
    GAME("게임", "게임", UsageWeightTier.MID_1_TO_2),
    DOC_MESSENGER("문서/메신저", "문서/메신저", UsageWeightTier.LOW_1_TO_3),
    WEB_GENERAL("웹/일반", "웹/일반", UsageWeightTier.LOW_1_TO_3)
}

/**
 * 현재 연속 세션 내에서 앱 카테고리별 누적 물리 사용 시간 및 환산 가중치 시간을 실시간 추적하는 싱글톤 트래커.
 * 자연 휴식(3분 화면 꺼짐), 확인된 휴식, 휴식 미션 완료, 15분 세션 만료 시 초기화됩니다.
 */
object SessionCategoryTracker {
    private val categoryMsMap = ConcurrentHashMap<SessionAppCategory, Long>()
    private var lastTickElapsed: Long = 0L

    fun resolveCurrentCategory(fgPkg: String?): SessionAppCategory? {
        if (fgPkg == null) return null
        if (JevSettings.isLauncherPackage(fgPkg) || JevSettings.isExcludedSystemPackage(fgPkg)) return null
        if (fgPkg.startsWith("com.team.itv") || fgPkg.startsWith("com.example.itv")) return null

        return when (fgPkg) {
            JevSettings.YOUTUBE -> {
                val yt = YoutubeLongformTracker.snapshot.value
                when {
                    yt.mode == YoutubeWatchMode.SHORTS ||
                        yt.mode == YoutubeWatchMode.SHORTS_COMMENTS ||
                        YoutubeProbe.screenState() == 1 -> SessionAppCategory.YOUTUBE_SHORTS
                    yt.mode == YoutubeWatchMode.BROWSE_FEED -> SessionAppCategory.FEED_SNS
                    else -> SessionAppCategory.YOUTUBE_LONGFORM
                }
            }
            JevSettings.INSTAGRAM -> {
                val ig = InstagramTracker.snapshot.value
                when {
                    ig.mode == InstagramScreenMode.REELS || ReelsProbe.screenState() == 1 -> SessionAppCategory.INSTA_REELS
                    ig.mode == InstagramScreenMode.DM -> SessionAppCategory.DOC_MESSENGER
                    else -> SessionAppCategory.FEED_SNS
                }
            }
            else -> {
                val gen = GeneralAppTracker.snapshot.value
                when (gen.category) {
                    InferredAppCategory.GAME -> SessionAppCategory.GAME
                    InferredAppCategory.COMMUNITY_SNS -> SessionAppCategory.FEED_SNS
                    InferredAppCategory.DOCUMENT,
                    InferredAppCategory.MESSENGER,
                    InferredAppCategory.WRITING,
                    InferredAppCategory.VOICE_CALL -> SessionAppCategory.DOC_MESSENGER
                    InferredAppCategory.VIDEO_MEDIA -> SessionAppCategory.YOUTUBE_LONGFORM
                    else -> SessionAppCategory.WEB_GENERAL
                }
            }
        }
    }

    @Synchronized
    fun observe(nowElapsed: Long, isUnlocked: Boolean, fgPkg: String?, paused: Boolean = false) {
        val delta = if (lastTickElapsed > 0L) (nowElapsed - lastTickElapsed).coerceIn(0L, 3500L) else 0L
        lastTickElapsed = nowElapsed

        if (!isUnlocked || paused || delta == 0L) return

        val cat = resolveCurrentCategory(fgPkg) ?: return
        val current = categoryMsMap[cat] ?: 0L
        categoryMsMap[cat] = current + delta
    }

    @Synchronized
    fun clear() {
        categoryMsMap.clear()
        lastTickElapsed = 0L
    }

    fun getCategoryMs(cat: SessionAppCategory): Long = categoryMsMap[cat] ?: 0L

    fun getCategoryDurationsSnapshot(): Map<SessionAppCategory, Long> {
        return categoryMsMap.entries
            .filter { it.value > 0L }
            .associate { it.key to it.value }
    }

    fun getActiveCategories(): List<Pair<SessionAppCategory, Long>> {
        return SessionAppCategory.entries
            .mapNotNull { cat ->
                val ms = categoryMsMap[cat] ?: 0L
                if (ms > 0L) cat to ms else null
            }
    }

    fun getTotalPhysicalMs(): Long = categoryMsMap.values.sum()

    fun getTotalWeightedMs(): Long {
        return categoryMsMap.entries.sumOf { (cat, ms) ->
            ms / cat.weightTier.divisor
        }
    }

    @Synchronized
    fun toJson(): org.json.JSONObject {
        val obj = org.json.JSONObject()
        for ((cat, ms) in categoryMsMap) {
            if (ms > 0L) obj.put(cat.name, ms)
        }
        return obj
    }

    @Synchronized
    fun restoreFromJson(json: org.json.JSONObject?) {
        categoryMsMap.clear()
        lastTickElapsed = 0L
        if (json == null) return
        for (cat in SessionAppCategory.entries) {
            val ms = json.optLong(cat.name, 0L)
            if (ms > 0L) categoryMsMap[cat] = ms
        }
    }
}
