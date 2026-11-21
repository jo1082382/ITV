package com.example.itv.domain.coaching

import com.example.itv.infrastructure.observation.InferredAppCategory
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RestMissionCatalogTest {

    @Before
    fun setUp() {
        MissionCooldownTracker.clear()
    }

    @Test
    fun longformStaticSelectsComboMissionAsFirstPriority() {
        // 롱폼 시청 중 + 미동 없음 (정적 자세) 환경
        val context = MissionSelectionContext(
            isLongform = true,
            isStationary = true,
            posture = UserPosture.SITTING_OR_STANDING,
            continuousUsedMs = 25 * 60 * 1000L
        )

        // 10회 연속 선택 시에도 콤보 미션 목록에서만 선정되어야 함
        repeat(10) {
            val rec = RestMissionCatalog.pick(
                posture = UserPosture.SITTING_OR_STANDING,
                isStrong = false,
                context = context
            )
            assertTrue("롱폼+미동없음 상황에서는 콤보 미션(isCombo=true)이 1순위로 선정되어야 함: ${rec.title}", rec.isCombo)
            assertTrue(
                "선정된 미션은 COMBO_RECOMMENDATIONS에 속해야 함",
                RestMissionCatalog.COMBO_RECOMMENDATIONS.any { it.title == rec.title }
            )
        }
    }

    @Test
    fun gameAppCategoryPrioritizesGameWristThumbStretching() {
        // 게임 플레이 중 (주간 환경, 롱폼 아님)
        val context = MissionSelectionContext(
            appCategory = InferredAppCategory.GAME,
            isGame = true,
            isLongform = false,
            isStationary = false,
            posture = UserPosture.SITTING_OR_STANDING,
            currentHour = 14
        )

        var gameMissionCount = 0
        val runs = 30
        repeat(runs) {
            MissionCooldownTracker.clear()
            val rec = RestMissionCatalog.pick(
                posture = UserPosture.SITTING_OR_STANDING,
                isStrong = false,
                context = context
            )
            if (RestMissionCatalog.GAME_RECOMMENDATIONS.any { it.title == rec.title }) {
                gameMissionCount++
            }
        }

        // 2x 가중치가 반영되어 게임 미션이 높은 비율로 선정되어야 함
        assertTrue("게임 중에는 손목/엄지/손가락 스트레칭 미션이 최소 30% 이상 선정되어야 함 (실제: $gameMissionCount / $runs)", gameMissionCount > 8)
    }

    @Test
    fun shortformPrioritizesEyeRestAndDopamineCooldown() {
        // 숏폼 시청 중 (주간 환경, 롱폼 아님)
        val context = MissionSelectionContext(
            isShortform = true,
            isLongform = false,
            isStationary = false,
            posture = UserPosture.SITTING_OR_STANDING,
            currentHour = 14
        )

        var shortformMissionCount = 0
        val runs = 30
        repeat(runs) {
            MissionCooldownTracker.clear()
            val rec = RestMissionCatalog.pick(
                posture = UserPosture.SITTING_OR_STANDING,
                isStrong = false,
                context = context
            )
            if (RestMissionCatalog.SHORTFORM_RECOMMENDATIONS.any { it.title == rec.title }) {
                shortformMissionCount++
            }
        }

        assertTrue("숏폼 시청 중에는 도파민 쿨다운 및 20-20-20 눈 쉬기 미션이 가중 선택되어야 함 (실제: $shortformMissionCount / $runs)", shortformMissionCount > 5)
    }

    @Test
    fun nighttimeAndBedtimeCandidatePrioritizesSleepAndEyeRest() {
        // 심야 / 암실 / 취침 후보 환경
        val context = MissionSelectionContext(
            isDarkOrBedtime = true,
            currentHour = 23,
            isLongform = false,
            isStationary = false,
            posture = UserPosture.LYING_DOWN
        )

        var nightOrEyeMissionCount = 0
        val runs = 30
        repeat(runs) {
            MissionCooldownTracker.clear()
            val rec = RestMissionCatalog.pick(
                posture = UserPosture.LYING_DOWN,
                isStrong = false,
                context = context
            )
            if (RestMissionCatalog.NIGHT_RECOMMENDATIONS.any { it.title == rec.title } ||
                rec.targetBodyPart == "눈" || rec.targetBodyPart == "두뇌쿨다운") {
                nightOrEyeMissionCount++
            }
        }

        assertTrue("야간/취침 후보 환경에서는 수면 호흡 및 눈 휴식 미션이 주로 선정되어야 함 (실제: $nightOrEyeMissionCount / $runs)", nightOrEyeMissionCount > 10)
    }

    @Test
    fun missionCooldownTrackerPreventsImmediateRepetition() {
        // 쿨다운 동작 검증: 5개의 콤보 미션 중 연속 3개는 서로 다른 미션이어야 함
        val context = MissionSelectionContext(
            isLongform = true,
            isStationary = true,
            posture = UserPosture.SITTING_OR_STANDING
        )

        val pickedTitles = mutableListOf<String>()
        repeat(3) {
            val rec = RestMissionCatalog.pick(
                posture = UserPosture.SITTING_OR_STANDING,
                isStrong = false,
                context = context
            )
            assertFalse(
                "방금 선택된 미션 '${rec.title}'은 쿨다운 중이 아니어야 함 (현재 쿨다운 목록: ${MissionCooldownTracker.getHistory()})",
                pickedTitles.contains(rec.title)
            )
            pickedTitles.add(rec.title)
        }

        // 3개가 모두 서로 달라야 함
        assertEquals(3, pickedTitles.distinct().size)
        assertEquals(pickedTitles, MissionCooldownTracker.getHistory())

        // 4번째 선택 시, 가장 처음 뽑혔던 미션은 쿨다운에서 해제될 수 있음
        val fourth = RestMissionCatalog.pick(
            posture = UserPosture.SITTING_OR_STANDING,
            isStrong = false,
            context = context
        )
        // 4번째 미션은 최소한 직전 2, 3번째 미션과는 달라야 함
        assertNotEquals(pickedTitles[1], fourth.title)
        assertNotEquals(pickedTitles[2], fourth.title)
    }

    @Test
    fun strongInterventionAlwaysRequiresFacedownAndHonorsCooldown() {
        repeat(5) {
            val rec = RestMissionCatalog.pick(UserPosture.LYING_DOWN, isStrong = true)
            assertTrue("강한 개입은 항상 스마트폰 엎어두기가 필수여야 함", rec.isFacedownRequired)
            assertTrue("강한 개입 미션 제목에 엎어두기가 포함되어야 함", rec.title.contains("엎어두기"))
        }
        assertEquals(3, MissionCooldownTracker.getHistory().size)
    }
}
