package com.example.itv.domain.coaching

import kotlin.random.Random

/**
 * Situations where the companion dog speaks to the user.
 */
enum class SpeechSituation {
    GENTLE_OFFER,         // 약한 개입: 화면 구석에서 조용히 등장할 때
    NORMAL_OFFER_TITLE,   // 보통 개입: 헤드라인 타이틀 ("잠깐 쉬어가자" 등)
    STRONG_OFFER,         // 강한 개입: 화면을 가로막으며 등장할 때
    RETRY_INCOMPLETE,     // 재약속: 약속 시간 전에 화면을 다시 켰을 때
    HOVER_HOME,           // 드래그 중 집(쉬기) 영역에 올렸을 때
    HOVER_EDGE,           // 드래그 중 가장자리(나중에) 영역에 올렸을 때
    HOVER_CAGE,           // 드래그 중 철창(닫기) 영역에 올렸을 때
    HOVER_NORMAL,         // 드래그 중 빈 공간에 올렸을 때
    REACT_LATER,          // "나중에" 선택 후 인사하며 사라질 때
    REACT_DISMISS,        // "닫기" 선택 후 쓸쓸히 돌아설 때
    MISSION_COMPLETE,     // 미션 성공 후 뼈다귀를 물고 왔을 때
    REWARD_CLAIMED,       // 뼈다귀/간식 터치하여 보상 수령했을 때
    LEVEL_UP              // 보상 수령으로 레벨업했을 때
}

/**
 * Rich, context-aware speech catalog supporting multiple dialogue variations
 * for each [SpeechSituation] and [CoachingTone].
 * Randomly picks from the curated collection to keep interactions fresh and natural.
 */
object CoachingSpeechCatalog {

    /**
     * Returns all available dialogue candidates for a situation and tone.
     */
    fun getCandidates(
        situation: SpeechSituation,
        tone: CoachingTone,
        reason: String? = null,
        timeText: String? = null,
        level: Int? = null,
        exp: Long? = null,
        points: Long? = null
    ): List<String> {
        return when (situation) {
            SpeechSituation.GENTLE_OFFER -> gentleOfferCandidates(tone, reason)
            SpeechSituation.NORMAL_OFFER_TITLE -> normalOfferTitleCandidates(tone)
            SpeechSituation.STRONG_OFFER -> strongOfferCandidates(tone, reason)
            SpeechSituation.RETRY_INCOMPLETE -> retryIncompleteCandidates(tone)
            SpeechSituation.HOVER_HOME -> hoverHomeCandidates(tone)
            SpeechSituation.HOVER_EDGE -> hoverEdgeCandidates(tone)
            SpeechSituation.HOVER_CAGE -> hoverCageCandidates(tone)
            SpeechSituation.HOVER_NORMAL -> hoverNormalCandidates(tone)
            SpeechSituation.REACT_LATER -> reactLaterCandidates(tone, timeText)
            SpeechSituation.REACT_DISMISS -> reactDismissCandidates(tone)
            SpeechSituation.MISSION_COMPLETE -> missionCompleteCandidates(tone)
            SpeechSituation.REWARD_CLAIMED -> rewardClaimedCandidates(tone, exp, points, level)
            SpeechSituation.LEVEL_UP -> levelUpCandidates(tone, level, exp, points)
        }
    }

    /**
     * Returns a randomized dialogue matching the given situation and tone.
     * Optional parameters allow inserting custom context (e.g. time, reason, level).
     */
    fun pickSpeech(
        situation: SpeechSituation,
        tone: CoachingTone,
        reason: String? = null,
        timeText: String? = null,
        level: Int? = null,
        exp: Long? = null,
        points: Long? = null,
        random: Random = Random.Default
    ): String {
        return getCandidates(situation, tone, reason, timeText, level, exp, points).random(random)
    }

    private fun gentleOfferCandidates(tone: CoachingTone, reason: String?): List<String> {
        val r = reason?.takeIf { it.isNotBlank() }
        return when (tone) {
            CoachingTone.SOFT -> listOfNotNull(
                r?.let { "$it\n잠깐 눈을 쉬어가요 🌿" },
                "영상을 오래 보셨어요.\n잠깐 눈을 깜빡이며 쉬어가요 🌿",
                "오늘 하루도 고생 많으셨어요.\n잠시 숨을 고르고 올까요? ☕",
                "눈이 조금 피로해 보여요.\n먼 산이나 창밖을 10초만 바라봐요 🍃",
                "바쁜 하루 중 소중한 쉼표 하나,\n지금 잠깐만 쉬어가요 🐾"
            ).ifEmpty { listOf("영상을 오래 보셨어요.\n잠깐 눈을 쉬어가요 🌿") }

            CoachingTone.PLAYFUL -> listOfNotNull(
                r?.let { "$it\n벌써 숏폼 여러 편째! 잠깐 쉬자 🐾" },
                "어라?! 스와이프 모터 달렸나요?!\n손가락 스트레칭 타임! 🐾",
                "벌써 쇼츠 알고리즘에 홀딱 빠졌죠?\n나랑 1분만 기지개 켜기 대결해요! 🐶",
                "띵동-! 강아지 배달이요!\n귀여운 나를 보고 10초만 멍때리기! ✨",
                "숏폼은 다음에도 계속 나와요!\n지금은 나랑 잠깐 댕댕 타임 가져요 🐾"
            ).ifEmpty { listOf("벌써 숏폼 여러 편째!\n잠깐만 쉬어가요 🐾") }

            CoachingTone.FIRM -> listOfNotNull(
                r?.let { "$it\n시청 시간이 길어졌어요. 화면을 꺼주세요." },
                "시청 시간이 길어졌어요.\n지금 잠깐 화면을 꺼주세요 🛑",
                "약속했던 시청 기준을 초과했어요.\n눈과 뇌를 위해 즉시 멈춰주세요.",
                "더 보면 피로가 배로 쌓여요.\n지금이 멈출 최적의 타이밍입니다 ⏱️",
                "의식하지 못하는 사이 과몰입 중이에요.\n스마트폰을 내려놓고 심호흡하세요."
            ).ifEmpty { listOf("시청 시간이 길어졌어요.\n잠깐 화면을 꺼주세요 🛑") }
        }
    }

    private fun normalOfferTitleCandidates(tone: CoachingTone): List<String> {
        return when (tone) {
            CoachingTone.SOFT -> listOf(
                "잠깐 쉬어가자 🌿",
                "함께 쉼표를 찍어볼까? ☕",
                "잠시 눈을 쉬어줄 시간이야 🍃",
                "오늘도 수고했어, 잠깐 쉬자 🐾"
            )
            CoachingTone.PLAYFUL -> listOf(
                "잠깐만 쉬어가자! 🐾",
                "멍멍! 숏폼 브레이크 타임! 🐶",
                "나랑 잠깐 놀아줄 시간이야! ✨",
                "앗! 벌써 여기까지 봤어?! 같이 쉬자 🐾"
            )
            CoachingTone.FIRM -> listOf(
                "지금은 쉴 시간이야 🛑",
                "스마트폰 휴식이 필요해 ⏱️",
                "과몰입 주의! 지금 멈춰야 해",
                "시청 시간이 기준을 넘었어 🛑"
            )
        }
    }

    private fun strongOfferCandidates(tone: CoachingTone, reason: String?): List<String> {
        val r = reason ?: "영상을 오래 시청하고 있어"
        return when (tone) {
            CoachingTone.SOFT -> listOf(
                "$r\n몸과 마음을 위해 지금 함께 쉬어요 🌿",
                "$r\n눈이 정말 피로해요. 강아지를 터치해 미션을 시작해요 ☕",
                "$r\n잠깐의 쉼이 더 큰 활력이 돼요. 함께 화면을 꺼볼까요? 🍃"
            )
            CoachingTone.PLAYFUL -> listOf(
                "$r\n앞을 딱 가로막았지롱! 강아지를 터치해 미션을 시작해요 🐾",
                "$r\n더 보면 눈이 뱅글뱅글 돌아가요! 얼른 나를 꾹 눌러줘요 🐶",
                "$r\n숏폼 지킴이 출동! 터치하고 같이 휴식 미션 하러 가요! ✨"
            )
            CoachingTone.FIRM -> listOf(
                "$r\n과몰입 위험! 강아지를 터치해 즉시 미션을 시작하세요 🛑",
                "$r\n지금 화면을 끄고 쉬어야 해요. 터치하여 휴식을 확정하세요 ⏱️",
                "$r\n더 이상의 연속 시청은 안 돼요. 터치 후 화면을 꺼주세요 🛑"
            )
        }
    }

    private fun retryIncompleteCandidates(tone: CoachingTone): List<String> {
        return when (tone) {
            CoachingTone.SOFT -> listOf(
                "아직 시간 안 됐어요!\n눈을 위해 조금만 더 화면을 끄고 쉬어주세요 🌿\n(터치 시 화면 꺼짐)",
                "약속했던 휴식 시간이 아직 남았어요 ☕\n잠깐만 더 편안하게 쉬어볼까요?\n(터치 시 화면 꺼짐)",
                "벌써 보고 싶었지만 조금만 더 참아봐요 🍃\n아직 쉴 시간이에요!\n(터치 시 화면 꺼짐)"
            )
            CoachingTone.PLAYFUL -> listOf(
                "아직 시간 안 됐어요!\n화면을 다시 끄고 쉬어주세요 🐾\n(터치 시 화면 꺼짐)",
                "앗, 들켰다! 벌써 폰 켜면 반칙이에요! 🐶\n남은 시간 얼른 채우고 와요!\n(터치 시 화면 꺼짐)",
                "쉬러 가기로 약속했잖아요! 🐾\n아직 시간 안 됐으니 얼른 다시 자요!\n(터치 시 화면 꺼짐)"
            )
            CoachingTone.FIRM -> listOf(
                "아직 시간 안 됐어요!\n정해진 휴식 시간을 꼭 지켜주세요 🛑\n(터치 시 화면 꺼짐)",
                "약속 불이행 감지! ⏱️\n지금 다시 화면을 끄고 쉬어야 합니다.\n(터치 시 화면 꺼짐)",
                "의지력이 흔들리면 안 돼요! 🛑\n터치하여 즉시 화면을 다시 끄세요.\n(터치 시 화면 꺼짐)"
            )
        }
    }

    private fun hoverHomeCandidates(tone: CoachingTone): List<String> {
        return when (tone) {
            CoachingTone.SOFT -> listOf("Zzz... 따뜻해... 졸려...", "집이다... 우리 푹 쉬자 🌿", "포근해... 눈 감고 쉴래...")
            CoachingTone.PLAYFUL -> listOf("Zzz... 기절 5초 전... 🐾", "집이다! 꿀잠 예약 완료! 🐶", "이불 밖은 위험해... 코 자자!")
            CoachingTone.FIRM -> listOf("집으로 복귀. 즉시 쉬어주세요.", "좋은 선택입니다. 화면을 꺼주세요.", "올바른 결정! 이제 쉬세요.")
        }
    }

    private fun hoverEdgeCandidates(tone: CoachingTone): List<String> {
        return when (tone) {
            CoachingTone.SOFT -> listOf("조금만 더 생각해보면 안 될까...? 🥺", "눈이 많이 아플 텐데... 진짜 나중에?", "잠깐만 쉬고 가도 늦지 않아...")
            CoachingTone.PLAYFUL -> listOf("진짜 안 쉴거야?! 🥺", "치... 나랑 안 놀아주고 영상만 볼 거야?", "나중에 오면 삐칠지도 몰라요! 🐾")
            CoachingTone.FIRM -> listOf("나중으로 미루면 피로만 쌓입니다.", "약속을 미루지 마세요. 지금 쉬세요.", "과몰입이 반복될 수 있어요. 다시 생각하세요.")
        }
    }

    private fun hoverCageCandidates(tone: CoachingTone): List<String> {
        return when (tone) {
            CoachingTone.SOFT -> listOf("거긴 너무 답답해... 가기 싫어... 🥺", "나를 가두지 말아줘... 슬퍼...", "철창은 무서워... 같이 쉬자...")
            CoachingTone.PLAYFUL -> listOf("거긴 가기 싫어… 낑낑 💦", "철창이라니 너무해! 멍멍!", "내가 싫어진 거야...? 나랑 놀자! 🐾")
            CoachingTone.FIRM -> listOf("경고 창을 닫아도 시청 시간은 줄지 않습니다.", "회피하지 마세요. 몸을 먼저 챙겨야 합니다.", "닫아도 피로는 사라지지 않아요.")
        }
    }

    private fun hoverNormalCandidates(tone: CoachingTone): List<String> {
        return when (tone) {
            CoachingTone.SOFT -> listOf("나랑 같이 쉬자 🌿", "어디로 갈까? 집으로 데려다줘 ☕", "잠깐 눈 쉬어주는 거 잊지 마 🍃")
            CoachingTone.PLAYFUL -> listOf("나랑 같이 쉬자! 🐾", "둥실둥실~ 어디로 날아가는 중?! 🐶", "집으로 슝~ 보내주세요! ✨")
            CoachingTone.FIRM -> listOf("지금 스마트폰을 내려놓으세요.", "휴식 영역(집)으로 이동하세요.", "시청 루프를 끊어야 합니다.")
        }
    }

    private fun reactLaterCandidates(tone: CoachingTone, timeText: String?): List<String> {
        val t = timeText ?: "잠시"
        return when (tone) {
            CoachingTone.SOFT -> listOf(
                "$t 뒤에 다시 올게. 그동안 눈 자주 깜빡여줘! 🌿",
                "알겠어. $t 뒤에 다시 상태 보러 올게 ☕",
                "조금만 보고 꼭 쉬어야 해! $t 뒤에 만나 🍃"
            )
            CoachingTone.PLAYFUL -> listOf(
                "$t 뒤에 다시 보자! 그때는 꼭 쉬기다! 🐾",
                "오케이! $t 뒤에 기습 방문할 거니까 긴장해! 🐶",
                "알겠어! $t 카운트다운 들어간다! 슝~ ✨"
            )
            CoachingTone.FIRM -> listOf(
                "$t 뒤에 재확인합니다. 그 전엔 꼭 쉬세요 ⏱️",
                "연장 승인: $t 뒤에 다시 개입합니다 🛑",
                "$t 뒤에 다시 옵니다. 화면을 줄이세요."
            )
        }
    }

    private fun reactDismissCandidates(tone: CoachingTone): List<String> {
        return when (tone) {
            CoachingTone.SOFT -> listOf(
                "다음에 보자... 눈 아프면 꼭 쉬어야 해 🌿",
                "알겠어... 몸 챙기면서 시청해줘 🥺",
                "언제든 내가 필요하면 불러줘..."
            )
            CoachingTone.PLAYFUL -> listOf(
                "다음에 보자.... 힝 🐾",
                "치... 숏폼이 나보다 좋다는 거지? 다음에 봐! 🐶",
                "알겠어! 다음엔 꼭 나랑 놀아주기다! ✨"
            )
            CoachingTone.FIRM -> listOf(
                "코칭이 종료되었습니다. 자율적으로 조절하세요.",
                "닫혔습니다. 과몰입에 유의하세요 🛑",
                "스스로의 시청 시간을 통제해야 합니다."
            )
        }
    }

    private fun missionCompleteCandidates(tone: CoachingTone): List<String> {
        return when (tone) {
            CoachingTone.SOFT -> listOf(
                "정말 훌륭하게 쉬어주셨어요! 🌿\n선물을 가져왔으니 뼈다귀를 눌러보세요!",
                "멋져요! 눈과 마음이 한결 가벼워졌죠? ☕\n뼈다귀를 눌러 보상을 받아주세요!",
                "약속을 지켜줘서 고마워요 🍃\n물고 온 뼈다귀를 터치해 선물을 확인해요!"
            )
            CoachingTone.PLAYFUL -> listOf(
                "와아아! 휴식 미션 대성공! 🐾\n내가 뼈다귀 물어왔지롱! 얼른 터치해줘!",
                "미션 클리어! 댕댕 파워 충전 완료! 🐶\n간식을 콕 눌러서 보상을 챙겨가요!",
                "역시 해낼 줄 알았어! 대단해! ✨\n뼈다귀를 탭하고 경험치 받아가자!"
            )
            CoachingTone.FIRM -> listOf(
                "휴식 미션 완수 확인. 훌륭한 자제력입니다! 🛑\n뼈다귀를 터치하여 보상을 수령하세요.",
                "목표 달성 성공. 눈의 피로가 회복되었습니다 ⏱️\n뼈다귀를 눌러 경험치를 받으세요.",
                "약속 완수! 훌륭합니다. 보상을 수령하세요 🎖️"
            )
        }
    }

    private fun rewardClaimedCandidates(tone: CoachingTone, exp: Long?, points: Long?, level: Int?): List<String> {
        val rewardInfo = if (exp != null && points != null) "+$exp EXP · +$points P 획득" else "보상 획득 완료"
        val lvInfo = if (level != null) " (Lv.$level)" else ""
        return when (tone) {
            CoachingTone.SOFT -> listOf(
                "보상을 받았어요! 잘 쉬어 줘서 정말 고마워요 🌿\n$rewardInfo$lvInfo",
                "오늘 하루 건강한 습관 하나 완성! ☕\n$rewardInfo$lvInfo",
                "차곡차곡 건강하게 성장하고 있어요 🍃\n$rewardInfo$lvInfo"
            )
            CoachingTone.PLAYFUL -> listOf(
                "냠냠! 보상 획득 완료! 🐾\n$rewardInfo$lvInfo",
                "맛있는 간식 냠! 우리 둘 다 레벨업 각! 🐶\n$rewardInfo$lvInfo",
                "경험치 냠냠 굿! 다음 미션도 같이 하자! ✨\n$rewardInfo$lvInfo"
            )
            CoachingTone.FIRM -> listOf(
                "보상이 지급되었습니다. 훌륭한 절제 습관입니다.\n$rewardInfo$lvInfo",
                "규칙 준수 보상 수령 완료 🎖️\n$rewardInfo$lvInfo",
                "습관 점수가 상승했습니다. 계속 유지하세요.\n$rewardInfo$lvInfo"
            )
        }
    }

    private fun levelUpCandidates(tone: CoachingTone, level: Int?, exp: Long?, points: Long?): List<String> {
        val rewardInfo = if (exp != null && points != null) "+$exp EXP · +$points P 획득" else ""
        val lv = level ?: 1
        return when (tone) {
            CoachingTone.SOFT -> listOf(
                "축하드려요! 레벨 업! 🎉 Lv.$lv 달성!\n함께 성장해서 정말 기뻐요 🌿\n$rewardInfo",
                "와아! 어느덧 Lv.${lv}이 되었어요! ☕\n절제 습관이 점점 빛을 발하고 있어요!\n$rewardInfo"
            )
            CoachingTone.PLAYFUL -> listOf(
                "우와아아 대박 사건! 🎉 레벨 업! Lv.$lv 달성!\n강아지가 더 튼튼해졌어요! 🐶🐾\n$rewardInfo",
                "레벨 업 팡파레 빰빰빰! 🎺 Lv.$lv 돌파!\n최고야! 앞으로도 숏폼 탈출 가보자고! ✨\n$rewardInfo"
            )
            CoachingTone.FIRM -> listOf(
                "축하합니다! 레벨 업: Lv.$lv 도달 🎖️\n절제 능력이 한 단계 진화했습니다.\n$rewardInfo",
                "목표 달성 누적 보상: Lv.$lv 승급 🛑\n자기 통제력이 훌륭하게 강화되었습니다.\n$rewardInfo"
            )
        }
    }
}
