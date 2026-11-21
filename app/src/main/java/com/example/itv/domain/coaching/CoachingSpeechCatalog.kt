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
                r?.let { "$it\n강아지를 터치하면 미션이 진행돼요 🌿" },
                "시청 시간이 길어졌어요.\n강아지를 터치하면 미션이 진행돼요 🌿",
                "연속 시청 중이에요.\n강아지를 터치하면 미션이 진행돼요 🍃",
                "스마트폰을 내려놓고 쉬어요.\n강아지를 터치하면 미션이 진행돼요 ☕"
            ).ifEmpty { listOf("시청 시간이 길어졌어요.\n강아지를 터치하면 미션이 진행돼요 🌿") }

            CoachingTone.PLAYFUL -> listOfNotNull(
                r?.let { "$it\n강아지를 터치하면 미션이 진행돼요! 🐾" },
                "연속 시청 중!\n강아지를 터치해 미션을 시작해요 🐾",
                "영상 시청이 길어졌어요!\n강아지를 터치하면 미션이 진행돼요! 🐶",
                "스마트폰 내려놓고 쉬어요!\n강아지를 터치하면 미션이 진행돼요! ✨"
            ).ifEmpty { listOf("연속 시청 중!\n강아지를 터치하면 미션이 진행돼요! 🐾") }

            CoachingTone.FIRM -> listOfNotNull(
                r?.let { "$it\n강아지를 터치해 미션을 진행하세요." },
                "시청 시간이 길어졌어요.\n강아지를 터치하면 미션이 진행돼요 🛑",
                "연속 시청 기준 초과.\n강아지를 터치해 미션을 진행하세요 ⏱️",
                "과몰입 방지를 위해 쉬세요.\n강아지를 터치하면 미션이 진행돼요 🛑"
            ).ifEmpty { listOf("시청 시간이 길어졌어요.\n강아지를 터치하면 미션이 진행돼요 🛑") }
        }
    }

    private fun normalOfferTitleCandidates(tone: CoachingTone): List<String> {
        return when (tone) {
            CoachingTone.SOFT -> listOf(
                "잠깐 쉬어가자 🌿",
                "눈을 쉴 시간이야 🍃",
                "화면 끄고 쉬자 ☕"
            )
            CoachingTone.PLAYFUL -> listOf(
                "잠깐 쉬어가자! 🐾",
                "화면 끄고 쉬자! 🐶",
                "잠깐 쉴 시간! ✨"
            )
            CoachingTone.FIRM -> listOf(
                "휴식이 필요해 🛑",
                "화면을 끄고 쉬어 ⏱️",
                "시청 기준 초과 🛑"
            )
        }
    }

    private fun strongOfferCandidates(tone: CoachingTone, reason: String?): List<String> {
        val r = reason ?: "연속 시청 시간이 길어졌어요"
        return when (tone) {
            CoachingTone.SOFT -> listOf(
                "$r\n강아지를 터치하면 미션이 진행돼요 🌿",
                "$r\n휴식 시간이에요.\n강아지를 터치하면 미션이 진행돼요 ☕",
                "$r\n잠시 휴식이 필요해요.\n강아지를 터치해 미션을 시작해요 🍃"
            )
            CoachingTone.PLAYFUL -> listOf(
                "$r\n강아지를 터치하면 미션이 진행돼요! 🐾",
                "$r\n연속 시청 중단!\n강아지를 터치해 미션을 시작해요 🐶",
                "$r\n휴식 시간이에요!\n강아지를 터치하면 미션이 진행돼요 ✨"
            )
            CoachingTone.FIRM -> listOf(
                "$r\n강아지를 터치해 미션을 진행하세요 🛑",
                "$r\n연속 시청 중단.\n강아지를 터치해 미션을 진행하세요 ⏱️",
                "$r\n기준 초과.\n강아지를 터치하여 미션을 진행하세요 🛑"
            )
        }
    }

    private fun retryIncompleteCandidates(tone: CoachingTone): List<String> {
        return when (tone) {
            CoachingTone.SOFT -> listOf(
                "휴식 시간이 아직 남았어요.\n폰을 엎어두면 화면이 꺼져요 🌿",
                "아직 휴식 중이에요.\n폰을 엎어두고 조금만 더 쉬어요 ☕",
                "휴식 시간이 남았어요.\n폰을 바닥에 엎어두고 쉬어주세요 🍃"
            )
            CoachingTone.PLAYFUL -> listOf(
                "휴식 시간이 아직 남았어요!\n폰을 엎어두면 화면이 꺼져요 🐾",
                "아직 휴식 중이에요!\n폰을 바닥에 엎어두고 조금만 더 쉬어요 🐶",
                "남은 휴식 시간을 채워줘요!\n얼른 폰을 바닥에 엎어둬요! ✨"
            )
            CoachingTone.FIRM -> listOf(
                "휴식 시간이 남았습니다.\n폰을 엎어두면 화면이 꺼집니다 🛑",
                "아직 휴식 중입니다.\n스마트폰을 바닥에 엎어두세요 ⏱️",
                "휴식 미션 진행 중.\n폰을 엎어두면 화면이 꺼집니다 🛑"
            )
        }
    }

    private fun hoverHomeCandidates(tone: CoachingTone): List<String> {
        return when (tone) {
            CoachingTone.SOFT -> listOf("휴식 시작 🌿", "화면 끄고 쉬기 ☕", "휴식 미션 시작 🍃")
            CoachingTone.PLAYFUL -> listOf("휴식 시작! 🐾", "화면 끄고 쉬기! 🐶", "휴식 미션 출발! ✨")
            CoachingTone.FIRM -> listOf("휴식 시작 🛑", "화면 끄기 ⏱️", "휴식 미션 확정")
        }
    }

    private fun hoverEdgeCandidates(tone: CoachingTone): List<String> {
        return when (tone) {
            CoachingTone.SOFT -> listOf("나중에 쉬기 🌿", "잠시 후 다시 알림 ☕", "조금 뒤에 쉬기 🍃")
            CoachingTone.PLAYFUL -> listOf("나중에 쉬기! 🐾", "잠깐 미루기 🐶", "조금 뒤에 올게! ✨")
            CoachingTone.FIRM -> listOf("나중에 쉬기 ⏱️", "휴식 연기 🛑", "잠시 후 재알림")
        }
    }

    private fun hoverCageCandidates(tone: CoachingTone): List<String> {
        return when (tone) {
            CoachingTone.SOFT -> listOf("닫기 🌿", "창 닫기 ☕", "알림 닫기 🍃")
            CoachingTone.PLAYFUL -> listOf("닫기 🐾", "창 닫기 🐶", "알림 닫기! ✨")
            CoachingTone.FIRM -> listOf("닫기 🛑", "알림 닫기 ⏱️", "코칭 닫기")
        }
    }

    private fun hoverNormalCandidates(tone: CoachingTone): List<String> {
        return when (tone) {
            CoachingTone.SOFT -> listOf("원하는 영역으로 드래그해 주세요 🌿", "선택지로 옮겨주세요 ☕", "집으로 옮기면 휴식이 시작돼요 🍃")
            CoachingTone.PLAYFUL -> listOf("원하는 곳으로 드래그해 줘! 🐾", "집으로 보내면 휴식 시작! 🐶", "선택지로 옮겨줘! ✨")
            CoachingTone.FIRM -> listOf("선택 영역으로 드래그하세요 🛑", "집(휴식)으로 이동하세요 ⏱️", "원하는 동작을 선택하세요.")
        }
    }

    private fun reactLaterCandidates(tone: CoachingTone, timeText: String?): List<String> {
        val t = timeText ?: "잠시"
        return when (tone) {
            CoachingTone.SOFT -> listOf(
                "$t 뒤에 다시 알릴게요 🌿",
                "$t 뒤에 찾아올게요 ☕",
                "$t 후 재확인할게요 🍃"
            )
            CoachingTone.PLAYFUL -> listOf(
                "$t 뒤에 다시 올게! 🐾",
                "$t 후에 다시 보자! 🐶",
                "$t 뒤에 올게! ✨"
            )
            CoachingTone.FIRM -> listOf(
                "$t 뒤에 재개입합니다 🛑",
                "$t 후 다시 확인합니다 ⏱️",
                "$t 뒤에 개입이 재개됩니다."
            )
        }
    }

    private fun reactDismissCandidates(tone: CoachingTone): List<String> {
        return when (tone) {
            CoachingTone.SOFT -> listOf(
                "코칭을 종료해요 🌿",
                "알림을 닫았어요 ☕",
                "다음에 찾아올게요 🍃"
            )
            CoachingTone.PLAYFUL -> listOf(
                "알림 닫힘! 다음에 봐! 🐾",
                "코칭 종료! 다음에 봐! 🐶",
                "닫혔어요! 다음에 만나요! ✨"
            )
            CoachingTone.FIRM -> listOf(
                "코칭이 종료되었습니다 🛑",
                "알림 닫힘 ⏱️",
                "자율적으로 시청을 조절하세요."
            )
        }
    }

    private fun missionCompleteCandidates(tone: CoachingTone): List<String> {
        return when (tone) {
            CoachingTone.SOFT -> listOf(
                "휴식을 완료했어요! 🌿\n뼈다귀를 눌러 보상을 받으세요.",
                "휴식 성공! ☕\n뼈다귀를 눌러 선물을 확인해요.",
                "미션 완료! 🍃\n물고 온 뼈다귀를 터치해 주세요."
            )
            CoachingTone.PLAYFUL -> listOf(
                "휴식 완료! 🐾\n뼈다귀를 눌러 보상을 받아요!",
                "미션 성공! 🐶\n뼈다귀를 터치해 보상을 챙겨요!",
                "휴식 끝! ✨\n뼈다귀를 탭하고 보상을 받아요!"
            )
            CoachingTone.FIRM -> listOf(
                "휴식 완료 🛑\n뼈다귀를 터치하여 보상을 수령하세요.",
                "휴식 완수 ⏱️\n뼈다귀를 눌러 보상을 받으세요.",
                "미션 성공 🎖️\n뼈다귀를 터치해 보상을 수령하세요."
            )
        }
    }

    private fun rewardClaimedCandidates(tone: CoachingTone, exp: Long?, points: Long?, level: Int?): List<String> {
        val rewardInfo = if (exp != null && points != null) "+$exp EXP · +$points P 획득" else "보상 획득 완료"
        val lvInfo = if (level != null) " (Lv.$level)" else ""
        return when (tone) {
            CoachingTone.SOFT -> listOf(
                "보상을 받았어요 🌿\n$rewardInfo$lvInfo",
                "보상 수령 완료 ☕\n$rewardInfo$lvInfo",
                "보상이 지급되었어요 🍃\n$rewardInfo$lvInfo"
            )
            CoachingTone.PLAYFUL -> listOf(
                "보상 획득 완료! 🐾\n$rewardInfo$lvInfo",
                "보상 챙기기 성공! 🐶\n$rewardInfo$lvInfo",
                "보상 획득! ✨\n$rewardInfo$lvInfo"
            )
            CoachingTone.FIRM -> listOf(
                "보상 수령 완료 🎖️\n$rewardInfo$lvInfo",
                "보상이 지급되었습니다 🛑\n$rewardInfo$lvInfo",
                "보상 획득 확인 ⏱️\n$rewardInfo$lvInfo"
            )
        }
    }

    private fun levelUpCandidates(tone: CoachingTone, level: Int?, exp: Long?, points: Long?): List<String> {
        val rewardInfo = if (exp != null && points != null) "+$exp EXP · +$points P 획득" else ""
        val lv = level ?: 1
        return when (tone) {
            CoachingTone.SOFT -> listOf(
                "레벨 업! Lv.$lv 달성 🎉\n$rewardInfo",
                "축하해요! Lv.$lv 달성 🌿\n$rewardInfo",
                "Lv.$lv 도달 ☕\n$rewardInfo"
            )
            CoachingTone.PLAYFUL -> listOf(
                "레벨 업! Lv.$lv 달성! 🎉\n$rewardInfo",
                "축하해! Lv.$lv 돌파! 🐶🐾\n$rewardInfo",
                "Lv.$lv 등극! ✨\n$rewardInfo"
            )
            CoachingTone.FIRM -> listOf(
                "레벨 업: Lv.$lv 달성 🎖️\n$rewardInfo",
                "승급 확인: Lv.$lv 도달 🛑\n$rewardInfo",
                "레벨 업: Lv.$lv ⏱️\n$rewardInfo"
            )
        }
    }
}
