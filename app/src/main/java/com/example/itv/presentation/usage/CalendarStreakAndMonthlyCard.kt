package com.example.itv.presentation.usage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itv.domain.records.DailyHealthTier
import com.example.itv.domain.reward.CalendarStreakPolicy
import com.example.itv.domain.reward.CalendarStreakSummary
import com.example.itv.domain.reward.ChestClaimStatus
import com.example.itv.domain.reward.MonthlyChestItemState
import com.example.itv.domain.reward.MonthlyChestTier
import com.example.itv.domain.reward.MonthlyCounterState
import com.example.itv.domain.reward.StreakProtectionSource
import com.example.itv.presentation.components.ItvCardSurface
import com.example.itv.presentation.components.ItvGreen
import com.example.itv.presentation.components.ItvInkBorder
import com.example.itv.presentation.components.ItvInkShadow
import com.example.itv.presentation.components.ItvInsetSurface
import com.example.itv.presentation.components.ItvLine
import com.example.itv.presentation.components.ItvTactileCard
import com.example.itv.presentation.components.LocalDarkTheme
import java.time.LocalDate

/**
 * 캘린더 상단(기존 범례 자리)에 배치되는 인라인 월간 보물상자 진행 바:
 * [🐶 현재 개수] ──── [동 보물상자 5] ──── [은 보물상자 12] ──── [금 보물상자 20]
 *
 * - 무한 애니메이션 루프(rememberInfiniteTransition)를 전혀 사용하지 않고 정적 하드웨어 레이어로 캐싱해
 *   스크롤 및 아이들 상태에서 CPU/GPU 프레임 드랍 0%를 보장합니다.
 */
@Composable
internal fun InlineMonthlyTreasureProgressBar(
    counter: MonthlyCounterState,
    preloadedOutlineBitmap: ImageBitmap?,
    onChestClick: (MonthlyChestItemState) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = LocalDarkTheme.current
    val textInk = if (isDark) Color(0xFFE2EBE4) else Color(0xFF241E19)
    val subInk = if (isDark) Color(0xFFA6B7A9) else Color(0xFF61564B)

    // 4개의 균등 슬롯(0, 5, 12, 20)에 정확히 맞춰지는 구간별 선형 진행률 계산
    val piecewiseProgress = remember(counter.happyCount) {
        val c = counter.happyCount
        when {
            c <= 0 -> 0f
            c <= 5 -> (c / 5f) * (1f / 3f)
            c <= 12 -> (1f / 3f) + ((c - 5) / 7f) * (1f / 3f)
            c <= 20 -> (2f / 3f) + ((c - 12) / 8f) * (1f / 3f)
            else -> 1f
        }.coerceIn(0f, 1f)
    }

    val trackBg = if (isDark) Color(0xFF243229) else Color(0xFFDFD8CC)
    val trackBorder = if (isDark) Color(0xFF3E5245) else Color(0xFFC5BCB0)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = ItvInsetSurface,
        border = BorderStroke(0.9.dp, ItvLine.copy(alpha = if (isDark) 0.55f else 0.78f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            // 1) 상자/아이콘 뒤를 가로지르는 진행 바 트랙 (정확히 4개 슬롯 원 중심 사이를 연결)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 20.5.dp)
                    .height(11.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(trackBg)
                    .border(1.dp, trackBorder, RoundedCornerShape(999.dp))
            ) {
                if (piecewiseProgress > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(piecewiseProgress)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color(0xFFD58847),
                                        Color(0xFF4CA4B5),
                                        Color(0xFFF5BC2F)
                                    )
                                )
                            )
                    )
                }
            }

            // 2) 좌측 현재 카운트(웃음 얼굴 아이콘) + 3개의 보물상자(5 / 12 / 20)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                // Far-Left Slot: Smiling Puppy Stamp Icon (웃음) + Current Count underneath
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    Box(
                        modifier = Modifier.size(width = 48.dp, height = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (isDark) Color(0xFF2B3B31) else Color(0xFFFFFBF2),
                            border = BorderStroke(1.3.dp, ItvInkBorder),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                DailyHealthStampBadge(
                                    tier = DailyHealthTier.SILVER,
                                    size = 34.dp,
                                    preloadedOutlineBitmap = preloadedOutlineBitmap
                                )
                            }
                        }
                    }
                    Text(
                        text = "${counter.happyCount}",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (counter.happyCount > 0) ItvGreen else textInk,
                        textAlign = TextAlign.Center
                    )
                }

                // 3 Milestone Chests (5 = Bronze, 12 = Silver, 20 = Gold)
                counter.chests.forEach { item ->
                    InlineMilestoneChestNode(
                        item = item,
                        subInk = subInk,
                        onClick = { onChestClick(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineMilestoneChestNode(
    item: MonthlyChestItemState,
    subInk: Color,
    onClick: () -> Unit
) {
    val palette = remember(item.tier) { paletteForChest(item.tier) }
    val isClaimable = item.status == ChestClaimStatus.UNLOCKED_CLAIMABLE
    val isClaimed = item.status == ChestClaimStatus.CLAIMED
    val isLocked = item.status == ChestClaimStatus.LOCKED
    // 보물상자 캔버스는 하단(0.38~0.87)에 무게중심이 있어 살짝 위로 보정해 원 정중앙에 배치
    val chestCenterOffsetY = when {
        isClaimed -> (-3).dp
        item.tier == MonthlyChestTier.GOLD -> (-3.5).dp
        else -> (-5).dp
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier.size(width = 50.dp, height = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isClaimable) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(palette.glowInner.copy(alpha = 0.42f))
                        .border(1.5.dp, palette.badgeAccent, CircleShape)
                )
            }
            MonthlyRewardChestIllustration(
                tier = item.tier,
                openProgress = if (isClaimed) 1f else 0f,
                lockPopProgress = if (isClaimed) 0.18f else 0f,
                glowPhase = 0f,
                dimmed = isLocked,
                modifier = Modifier
                    .size(40.dp)
                    .offset(y = chestCenterOffsetY)
            )
            if (isClaimable) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = palette.badgeAccent,
                    border = BorderStroke(0.9.dp, Color.White.copy(alpha = 0.85f)),
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Text(
                        text = "열기",
                        fontSize = 8.5.sp,
                        lineHeight = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                    )
                }
            } else if (isClaimed) {
                Surface(
                    shape = CircleShape,
                    color = ItvGreen,
                    border = BorderStroke(0.9.dp, Color.White.copy(alpha = 0.85f)),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(15.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "✓",
                            fontSize = 8.5.sp,
                            lineHeight = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }
                }
            }
        }
        Text(
            text = "${item.tier.requiredHappyStamps}",
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            color = when {
                isClaimable -> palette.badgeAccent
                isClaimed -> ItvGreen
                else -> subInk
            },
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 캘린더 하단의 컴팩트 '열쇠·상자 연출 미리보기 (동 / 은 / 금)' 스트립 (0fps 정적 렌더링)
 */
@Composable
internal fun ChestOpeningPreviewStrip(
    onPreviewChest: (MonthlyChestTier) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = LocalDarkTheme.current
    val subInk = if (isDark) Color(0xFFA6B7A9) else Color(0xFF61564B)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        color = ItvInsetSurface,
        border = BorderStroke(0.9.dp, ItvLine.copy(alpha = if (isDark) 0.55f else 0.75f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🔑 열쇠·보물상자 미리보기",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = subInk
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MonthlyChestTier.entries.forEach { tier ->
                    val pal = remember(tier) { paletteForChest(tier) }
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = pal.badgeBg,
                        border = BorderStroke(1.dp, pal.badgeAccent.copy(alpha = 0.55f)),
                        modifier = Modifier.clickable { onPreviewChest(tier) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            RewardKeyIllustration(
                                tier = tier,
                                modifier = Modifier.size(16.dp),
                                rotationDeg = -22f
                            )
                            Text(
                                text = when (tier) {
                                    MonthlyChestTier.BRONZE -> "동"
                                    MonthlyChestTier.SILVER -> "은"
                                    MonthlyChestTier.GOLD -> "금"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = pal.badgeAccent
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 건강 스탬프 연속 스트릭 + 자동 보호(보유 보호권 우선 -> 부족 시 포인트 자동 사용) 카드
 * (별도 구매 버튼 없이 자동 방어 안내만 깔끔하게 표시합니다.)
 */
@Composable
internal fun HealthStampStreakCard(
    streakSummary: CalendarStreakSummary,
    shieldCount: Int,
    currentPoints: Long,
    lastAutoProtectedDate: LocalDate?,
    lastAutoProtectionSource: StreakProtectionSource?,
    modifier: Modifier = Modifier
) {
    val isDark = LocalDarkTheme.current
    val textInk = if (isDark) Color(0xFFE2EBE4) else Color(0xFF241E19)
    val subInk = if (isDark) Color(0xFFA6B7A9) else Color(0xFF61564B)
    val maxShields = CalendarStreakPolicy.MAX_STREAK_SHIELDS
    val shieldCost = CalendarStreakPolicy.SHIELD_COST_POINTS

    ItvTactileCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        containerColor = ItvCardSurface,
        contentColor = textInk,
        borderColor = ItvInkBorder,
        shadowColor = ItvInkShadow,
        borderWidth = 2.dp,
        shadowOffsetX = 2.dp,
        shadowOffsetY = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Top Row: Streak Counter + Best Streak Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (streakSummary.currentStreak > 0) {
                            Color(0xFFFFEFE0)
                        } else {
                            ItvInsetSurface
                        },
                        border = BorderStroke(
                            1.4.dp,
                            if (streakSummary.currentStreak > 0) Color(0xFFE07A2F) else ItvLine
                        ),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = if (streakSummary.currentStreak > 0) "🔥" else "🐾",
                                fontSize = 20.sp
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                text = "${streakSummary.currentStreak}일",
                                fontSize = 21.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (streakSummary.currentStreak > 0) Color(0xFFD96B1F) else textInk
                            )
                            Text(
                                text = "연속 건강 스트릭",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = textInk,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                        Text(
                            text = if (streakSummary.currentStreak > 0) {
                                "${streakSummary.nextMilestone}일 마일스톤까지 ${streakSummary.daysToNextMilestone}일 남았어요"
                            } else if (streakSummary.isTodayProjectedPositive) {
                                "오늘 흐름이 좋아요! 내일 아침 첫 연속 스트릭 시작!"
                            } else {
                                "해맑음·웃음 스탬프를 연속으로 받아 스트릭을 쌓아보세요"
                            },
                            fontSize = 11.5.sp,
                            color = subInk
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ItvInsetSurface,
                    border = BorderStroke(0.9.dp, ItvLine)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("최고", fontSize = 10.sp, color = subInk)
                        Text(
                            text = "${streakSummary.bestStreak}일",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = textInk
                        )
                    }
                }
            }

            // Auto-Protection Notice Banner when a shield or points saved the streak
            if (lastAutoProtectedDate != null && lastAutoProtectedDate in streakSummary.activeShieldProtectedDates) {
                val dateLabel = "${lastAutoProtectedDate.monthValue}/${lastAutoProtectedDate.dayOfMonth}"
                val bannerText = when (lastAutoProtectionSource) {
                    StreakProtectionSource.POINTS ->
                        "🛡️ 보호권이 없어 ${shieldCost} P가 자동 사용되어 $dateLabel 스트릭을 지켜냈어요!"
                    else ->
                        "🛡️ 스트릭 보호권이 $dateLabel 기록을 자동 방어해 연속 스트릭을 이어줬어요!"
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = if (isDark) Color(0xFF1F3640) else Color(0xFFE6F5FA),
                    border = BorderStroke(1.dp, Color(0xFF47A7BF).copy(alpha = 0.55f))
                ) {
                    Text(
                        text = bannerText,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isDark) Color(0xFFB3E8F5) else Color(0xFF1D6275),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
            }

            // Read-Only Auto-Protection Status Strip (No manual purchase button!)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp),
                color = ItvInsetSurface,
                border = BorderStroke(0.9.dp, ItvLine.copy(alpha = if (isDark) 0.55f else 0.78f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🛡️", fontSize = 18.sp)
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                text = "스트릭 자동 보호 · 보호권 ${shieldCount}/${maxShields}개",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = textInk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "끊길 날 자동 사용 · 없으면 ${shieldCost} P 자동 차감 (보유 ${currentPoints} P)",
                                fontSize = 10.8.sp,
                                color = subInk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
