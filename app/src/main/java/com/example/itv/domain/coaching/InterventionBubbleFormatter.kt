package com.example.itv.domain.coaching

import kotlin.random.Random

/**
 * 개입 말풍선 상단에 표시되는 0.5초 스캔용 핵심 이유 칩 배지.
 *
 * @property text 12~14자 이내의 짧은 핵심 이유 + 수치 (예: "🌙 취침 +35분 · 암실", "🎬 릴스 22분")
 * @property isAlert 취침 지연·암실·과열·평균 초과 등 경고성 요인이면 true (코랄 틴트), 일반 시청 시간 등은 false (민트 틴트)
 */
data class ReasonBadge(
    val text: String,
    val isAlert: Boolean
)

/**
 * 1번(핵심 이유 칩 배지) + 2번(15자 이내 초단문 헤드라인 · 연한 보조 안내) 조합 말풍선 모델.
 */
data class OfferBubbleContent(
    val badges: List<ReasonBadge>,
    val headline: String,
    val subline: String?
) {
    /** 텍스트 전용 폴백/로그 표현 */
    fun toPlainText(): String = buildString {
        if (badges.isNotEmpty()) {
            append(badges.joinToString("  ") { "[${it.text}]" })
            append('\n')
        }
        append(headline)
        if (!subline.isNullOrBlank()) {
            append('\n')
            append(subline)
        }
    }
}

/**
 * [InterventionReasonResolver]가 추출한 사유 문장들을 애매한 줄바꿈 없이 한눈에 읽히는
 * [OfferBubbleContent](상단 핵심 배지 최대 2개 + 15자 이내 헤드라인 1줄 + 짧은 보조 안내 1줄)로 변환합니다.
 */
object InterventionBubbleFormatter {

    private val NUM_MIN_REGEX = Regex("""(\d+)\s*분""")
    private val NUM_SEC_REGEX = Regex("""(\d+)\s*초""")
    private val NUM_RATIO_REGEX = Regex("""(\d+(?:\.\d+)?)\s*배""")
    private val NUM_PIECE_REGEX = Regex("""(\d+)\s*편""")
    private val NUM_COUNT_REGEX = Regex("""(\d+)\s*[개회번]""")
    private val AVG_OVER_REGEX = Regex("""평균\(\d+분\)보다\s*(\d+)분""")

    fun buildOfferBubble(
        intensity: CharacterIntensity,
        reasons: List<String>,
        tone: CoachingTone = CoachingTone.SOFT,
        random: Random = Random.Default
    ): OfferBubbleContent {
        val maxBadges = when (intensity) {
            CharacterIntensity.GENTLE -> 2
            CharacterIntensity.NORMAL, CharacterIntensity.STRONG -> 2
        }
        val badges = reasons
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { toBadge(it) }
            .distinctBy { it.text }
            .take(maxBadges)

        val headline = pickShortHeadline(intensity, tone, random)
        val subline = pickShortSubline(intensity, tone)

        return OfferBubbleContent(
            badges = badges,
            headline = headline,
            subline = subline
        )
    }

    /**
     * 상세 개입 사유 문장을 말풍선 상단 칩 배지(12~14자 이내)로 압축합니다.
     */
    fun toBadge(reason: String): ReasonBadge {
        val r = reason.trim()
        val hasDark = r.contains("어두") || r.contains("암실") || r.contains("불을 끈")

        // 1. 취침 시각 지연 / 취침 시간 (+ 암실 결합)
        if (r.contains("취침")) {
            val min = NUM_MIN_REGEX.find(r)?.groupValues?.get(1)
            return when {
                r.contains("전") && min != null && hasDark -> ReasonBadge("🌙 취침 ${min}분전·암실", isAlert = true)
                r.contains("전") && min != null -> ReasonBadge("🌙 취침 ${min}분 전", isAlert = true)
                r.contains("전") && hasDark -> ReasonBadge("🌙 취침 전·암실", isAlert = true)
                min != null && hasDark -> ReasonBadge("🌙 취침 +${min}분·암실", isAlert = true)
                min != null -> ReasonBadge("🌙 취침 +${min}분", isAlert = true)
                hasDark -> ReasonBadge("🌙 취침시간·암실", isAlert = true)
                else -> ReasonBadge("🌙 취침 시간", isAlert = true)
            }
        }
        if (r.contains("늦은 밤")) {
            return if (hasDark) ReasonBadge("🌙 심야·어두운 곳", isAlert = true)
            else ReasonBadge("🌙 늦은 밤", isAlert = true)
        }
        if (r.contains("자던 시간")) {
            return ReasonBadge("🌙 취침 시간 초과", isAlert = true)
        }

        // 2. 어두운 환경 / 암실 단독
        if (hasDark) {
            val min = NUM_MIN_REGEX.find(r)?.groupValues?.get(1)
            return when {
                r.contains("밝은 화면") -> ReasonBadge("⚠️ 암실·밝은 화면", isAlert = true)
                r.contains("어두운 화면") -> ReasonBadge("🌙 암실·어두운 화면", isAlert = true)
                min != null -> ReasonBadge("🌑 암실 ${min}분", isAlert = true)
                else -> ReasonBadge("🌑 어두운 환경", isAlert = true)
            }
        }

        // 3. 평소 평균 세션 초과
        if (r.contains("평균") || r.contains("평소 보던")) {
            val overMin = AVG_OVER_REGEX.find(r)?.groupValues?.get(1)
            val ratio = NUM_RATIO_REGEX.find(r)?.groupValues?.get(1)
            return when {
                overMin != null -> ReasonBadge("📈 평균 +${overMin}분", isAlert = true)
                ratio != null -> ReasonBadge("📈 평소 ${ratio}배", isAlert = true)
                else -> ReasonBadge("📈 평균 세션 초과", isAlert = true)
            }
        }

        // 4. 롱폼 연속 영상 전환
        if (r.contains("편째") || (r.contains("연달아") && r.contains("영상"))) {
            val pieces = NUM_PIECE_REGEX.find(r)?.groupValues?.get(1)
            return if (pieces != null) ReasonBadge("🔁 영상 ${pieces}편 연속", isAlert = true)
            else ReasonBadge("🔁 연속 시청 중", isAlert = true)
        }

        // 5. 스와이프 과열 / 누적 스와이프
        if (r.contains("분당") || r.contains("빠르게") || r.contains("휙휙")) {
            val spm = NUM_COUNT_REGEX.find(r)?.groupValues?.get(1)
            return if (spm != null) ReasonBadge("🔥 분당 ${spm}회 넘김", isAlert = true)
            else ReasonBadge("🔥 빠른 스와이프", isAlert = true)
        }
        if (r.contains("개째") && (r.contains("넘겨") || r.contains("숏폼"))) {
            val cnt = NUM_COUNT_REGEX.find(r)?.groupValues?.get(1)
            return if (cnt != null) ReasonBadge("⚡ 숏폼 ${cnt}개 전환", isAlert = true)
            else ReasonBadge("⚡ 숏폼 반복 전환", isAlert = true)
        }

        // 6. 잦은 재접속
        if (r.contains("다시 켰") || r.contains("재접속")) {
            val cnt = Regex("""(\d+)\s*(?:번|회)""").find(r)?.groupValues?.get(1)
            return if (cnt != null) ReasonBadge("🔄 ${cnt}회 재접속", isAlert = true)
            else ReasonBadge("🔄 잦은 재접속", isAlert = true)
        }

        // 7. 콘텐츠별 연속 시청 시간 (분/초 단위)
        val watchMin = NUM_MIN_REGEX.find(r)?.groupValues?.get(1)
        val watchSec = NUM_SEC_REGEX.find(r)?.groupValues?.get(1)
        if (r.contains("여러 앱")) {
            return if (watchMin != null) ReasonBadge("📱 여러 앱 ${watchMin}분", isAlert = false)
            else ReasonBadge("📱 복합 연속 사용", isAlert = false)
        }
        if (r.contains("릴스") || r.contains("Reels")) {
            return if (watchMin != null) ReasonBadge("🎬 릴스 ${watchMin}분", isAlert = false)
            else ReasonBadge("🎬 릴스 몰입", isAlert = false)
        }
        if (r.contains("쇼츠") || r.contains("Shorts")) {
            return if (watchMin != null) ReasonBadge("⚡ 쇼츠 ${watchMin}분", isAlert = false)
            else ReasonBadge("⚡ 쇼츠 몰입", isAlert = false)
        }
        if (r.contains("숏폼")) {
            return if (watchMin != null) ReasonBadge("⚡ 숏폼 ${watchMin}분", isAlert = false)
            else ReasonBadge("⚡ 숏폼 몰입", isAlert = false)
        }
        if (r.contains("유튜브 영상") || r.contains("롱폼")) {
            return if (watchMin != null) ReasonBadge("📺 영상 ${watchMin}분", isAlert = false)
            else ReasonBadge("📺 영상 연속 시청", isAlert = false)
        }
        if (r.contains("게임")) {
            return if (watchMin != null) ReasonBadge("🎮 게임 ${watchMin}분", isAlert = false)
            else ReasonBadge("🎮 게임 연속 이용", isAlert = false)
        }
        if (r.contains("피드") || r.contains("SNS")) {
            return if (watchMin != null) ReasonBadge("📱 피드 ${watchMin}분", isAlert = false)
            else ReasonBadge("📱 피드 연속 탐색", isAlert = false)
        }
        if (r.contains("메신저") || r.contains("문서")) {
            return if (watchMin != null) ReasonBadge("💬 메신저 ${watchMin}분", isAlert = false)
            else ReasonBadge("💬 메신저 연속 사용", isAlert = false)
        }
        if (r.contains("웹·화면")) {
            return if (watchMin != null) ReasonBadge("🌐 웹 ${watchMin}분", isAlert = false)
            else ReasonBadge("🌐 웹 연속 사용", isAlert = false)
        }
        if (watchMin != null) {
            return ReasonBadge("⏱ 연속 ${watchMin}분", isAlert = false)
        }
        if (watchSec != null) {
            return ReasonBadge("⏱ 연속 ${watchSec}초", isAlert = false)
        }

        // 8. 눈 깜빡임 / 일반 폴백 (최대 13자 깔끔 축약)
        if (r.contains("눈 깜빡임")) {
            return ReasonBadge("👀 눈 피로 누적", isAlert = false)
        }
        val cleaned = r
            .replace("!", "")
            .replace(".", "")
            .trim()
        val compact = if (cleaned.length <= 13) cleaned else cleaned.take(12).trimEnd() + "…"
        return ReasonBadge("💡 $compact", isAlert = false)
    }

    private fun pickShortHeadline(
        intensity: CharacterIntensity,
        tone: CoachingTone,
        random: Random
    ): String {
        val candidates = when (intensity) {
            CharacterIntensity.GENTLE -> when (tone) {
                CoachingTone.SOFT -> listOf(
                    "잠깐 눈 좀 쉬어갈까? 🌿",
                    "잠시 화면에서 눈 떼요 ☕",
                    "눈을 잠깐 쉬어 볼까요? 🍃"
                )
                CoachingTone.PLAYFUL -> listOf(
                    "잠깐만 쉬어갈래? 🐾",
                    "눈 좀 쉬고 다시 보자! 🐶",
                    "잠깐 스트레칭할 시간! ✨"
                )
                CoachingTone.FIRM -> listOf(
                    "잠시 눈을 쉬어 주세요 🛑",
                    "짧은 휴식이 필요해요 ⏱️",
                    "잠깐 화면을 내려놔요 🛑"
                )
            }
            CharacterIntensity.NORMAL -> CoachingSpeechCatalog.getCandidates(
                SpeechSituation.NORMAL_OFFER_TITLE,
                tone
            )
            CharacterIntensity.STRONG -> when (tone) {
                CoachingTone.SOFT -> listOf(
                    "지금은 꼭 쉬어가자 🌿",
                    "이제 화면을 잠시 꺼요 ☕",
                    "잠시 눈을 감고 쉬어요 🍃"
                )
                CoachingTone.PLAYFUL -> listOf(
                    "연속 시청 잠깐 스톱! 🐾",
                    "지금 바로 쉬고 오자! 🐶",
                    "휴식 타임 출발하자! ✨"
                )
                CoachingTone.FIRM -> listOf(
                    "지금 화면을 꺼 주세요 🛑",
                    "연속 시청을 멈춰 주세요 ⏱️",
                    "지금 바로 휴식하세요 🛑"
                )
            }
        }
        return candidates.random(random)
    }

    private fun pickShortSubline(
        intensity: CharacterIntensity,
        tone: CoachingTone
    ): String = when (intensity) {
        CharacterIntensity.GENTLE -> when (tone) {
            CoachingTone.SOFT -> "강아지를 터치하면 바로 쉬어요"
            CoachingTone.PLAYFUL -> "나를 톡 누르면 휴식 시작!"
            CoachingTone.FIRM -> "강아지를 터치해 휴식을 시작하세요"
        }
        CharacterIntensity.NORMAL -> when (tone) {
            CoachingTone.SOFT -> "집으로 옮기면 휴식이 시작돼요"
            CoachingTone.PLAYFUL -> "나를 집으로 옮겨 쉬어 보자!"
            CoachingTone.FIRM -> "강아지를 집으로 옮겨 쉬세요"
        }
        CharacterIntensity.STRONG -> when (tone) {
            CoachingTone.SOFT -> "강아지를 터치하면 미션이 시작돼요"
            CoachingTone.PLAYFUL -> "나를 터치하면 바로 미션 시작!"
            CoachingTone.FIRM -> "강아지를 터치해 미션을 시작하세요"
        }
    }
}
