package com.example.itv.infrastructure.observation

import com.example.itv.domain.coaching.UsageWeightTier
import com.example.itv.domain.jev.JevSettings
import com.example.itv.infrastructure.observation.instagram.InstagramScreenMode
import com.example.itv.infrastructure.observation.instagram.InstagramTracker
import com.example.itv.infrastructure.observation.instagram.ReelsProbe
import com.example.itv.infrastructure.observation.youtube.YoutubeLongformTracker
import com.example.itv.infrastructure.observation.youtube.YoutubeProbe
import com.example.itv.infrastructure.observation.youtube.YoutubeWatchMode

/**
 * 현재 포그라운드 앱/화면의 3단계 가중치([UsageWeightTier])를 일관되게 판별합니다.
 * - 1:1 ([UsageWeightTier.FULL_1_TO_1]): YouTube 쇼츠, Instagram 릴스 등 확정 숏폼
 * - 1:2 ([UsageWeightTier.MID_1_TO_2]): 피드·SNS (인스타 피드/탐색/스토리, 유튜브 홈/피드, 커뮤니티/SNS) 및 게임
 * - 1:3 ([UsageWeightTier.LOW_1_TO_3]): 유튜브 롱폼 영상, 인스타 DM, 웹/검색, 문서/메신저, 기타 일반 앱
 */
object UsageWeightResolver {

    fun resolveTier(fgPkg: String?): UsageWeightTier {
        if (fgPkg == null) return UsageWeightTier.LOW_1_TO_3
        return when (fgPkg) {
            JevSettings.YOUTUBE -> {
                val ytMode = YoutubeLongformTracker.snapshot.value.mode
                when {
                    YoutubeProbe.screenState() == 1 ||
                        ytMode == YoutubeWatchMode.SHORTS ||
                        ytMode == YoutubeWatchMode.SHORTS_COMMENTS -> UsageWeightTier.FULL_1_TO_1
                    ytMode == YoutubeWatchMode.BROWSE_FEED -> UsageWeightTier.MID_1_TO_2
                    else -> UsageWeightTier.LOW_1_TO_3
                }
            }
            JevSettings.INSTAGRAM -> {
                val igMode = InstagramTracker.snapshot.value.mode
                when {
                    ReelsProbe.screenState() == 1 || igMode == InstagramScreenMode.REELS -> UsageWeightTier.FULL_1_TO_1
                    igMode == InstagramScreenMode.DM -> UsageWeightTier.LOW_1_TO_3
                    else -> UsageWeightTier.MID_1_TO_2
                }
            }
            else -> {
                val genSnap = GeneralAppTracker.snapshot.value
                if (genSnap.packageName == fgPkg &&
                    (genSnap.category == InferredAppCategory.COMMUNITY_SNS ||
                        genSnap.category == InferredAppCategory.GAME)
                ) {
                    UsageWeightTier.MID_1_TO_2
                } else {
                    UsageWeightTier.LOW_1_TO_3
                }
            }
        }
    }
}
