package com.example.itv.domain.coaching

/**
 * 구버전 안드로이드(Android 12 이하) 및 좁은 말풍선/카드 폭에서도
 * 숫자·단위, 핵심 2어절 구문, 이모지, 마지막 1글자 어절이 어색하게 분리되어 줄바꿈되지 않도록
 * 줄바꿈 방지 공백(Non-Breaking Space, `\u00A0`)을 적용하는 텍스트 유틸리티.
 */
object KoreanTextWrapUtils {
    const val NBSP: Char = '\u00A0'
    private const val NBSP_STR: String = "\u00A0"

    // 1) 숫자 뒤의 단위·의존명사·시간 표현 결합 (예: "15 분" -> "15\u00A0분", "15분 전" -> "15분\u00A0전", "30초 뒤" -> "30초\u00A0뒤")
    private val NUMBER_UNIT_SPACE_REGEX = Regex(
        """(\d+(?:\.\d+)?)\s+(분|초|시간|편|개|회|번|배|점|%|lx|분째|편째|개째|회째|분전)"""
    )
    private val UNIT_FOLLOW_WORD_REGEX = Regex(
        """(\d+(?:\.\d+)?(?:분|초|시간|편|개|회|번|배))\s+(째|전|후|뒤|뒤에|내|이내|이상|미만|연속|휴식|동안|넘김|전환|재접속|초과)"""
    )

    // 2) 핵심 수식어/콘텐츠명 + 숫자 구문 결합 (예: "연속 30분" -> "연속\u00A030분", "취침 +35분" -> "취침\u00A0+35분")
    private val PREFIX_NUMBER_REGEX = Regex(
        """(연속|평균|취침|분당|평소|남은|추가|약|총|최소|최대|릴스|쇼츠|숏폼|영상|게임|피드|웹|암실|메신저|앱)\s+([+\-]?\d)"""
    )

    // 3) 고정 2어절 브랜드/맥락 구문 결합
    private val FIXED_PHRASES = listOf(
        "YouTube 쇼츠",
        "Instagram 릴스",
        "여러 앱",
        "어두운 곳",
        "어두운 방",
        "밝은 화면",
        "어두운 화면",
        "눈 피로",
        "취침 시간",
        "취침 시각",
        "취침 준비",
        "휴식 미션",
        "휴식 시간",
        "휴식 시작",
        "화면 끄기",
        "화면 끄고",
        "터치해 쉬기",
        "바로 쉬기",
        "잠시 후",
        "조금 뒤",
        "남은 시간"
    )

    // 4) 선행 이모지/기호와 바로 뒤 단어 결합 (예: "🌙 취침" -> "🌙\u00A0취침")
    private val LEADING_SYMBOL_REGEX = Regex(
        """(^|[\s·(])([\p{So}\u2600-\u27BF][\uFE0F\u200D\p{So}]*)\s+(\S)"""
    )

    // 5) 단어 뒤 후행 이모지 고아 방지 (예: "쉬어가자 🌿" -> "쉬어가자\u00A0🌿")
    private val TRAILING_EMOJI_REGEX = Regex(
        """(\S)\s+([\p{So}\u2600-\u27BF][\uFE0F\u200D\p{So}]*)(?=$|[.,!?)])"""
    )

    // 6) 중점(·) 뒤 공백을 NBSP로 묶어 중점이 줄 끝에 외톨이로 남지 않게 방지 (예: "· 터치해" -> "·\u00A0터치해")
    private val MIDDOT_AFTER_SPACE_REGEX = Regex("""·\s+""")

    // 7) 줄 끝의 1글자 한글 고아 어절 방지 (예: "잠깐 쉬어 봐" -> "잠깐 쉬어\u00A0봐")
    private val TRAILING_SINGLE_HANGUL_REGEX = Regex("""(\S+)\s+([가-힣][.!?]?)$""")

    /**
     * 줄바꿈(`\n`)은 그대로 유지하면서 각 줄 내부의 어색한 분절 지점에만 `\u00A0`를 적용합니다.
     */
    fun preventAwkwardWrap(text: String): String {
        if (text.isEmpty() || (!text.contains(' ') && !text.contains('\t'))) return text
        return text.split('\n').joinToString("\n") { line ->
            processLine(line)
        }
    }

    private fun processLine(line: String): String {
        if (line.isBlank() || !line.contains(' ')) return line
        var result = line

        for (phrase in FIXED_PHRASES) {
            if (result.contains(phrase)) {
                result = result.replace(phrase, phrase.replace(" ", NBSP_STR))
            }
        }

        result = NUMBER_UNIT_SPACE_REGEX.replace(result, "$1$NBSP_STR$2")
        result = UNIT_FOLLOW_WORD_REGEX.replace(result, "$1$NBSP_STR$2")
        result = PREFIX_NUMBER_REGEX.replace(result, "$1$NBSP_STR$2")
        result = MIDDOT_AFTER_SPACE_REGEX.replace(result, "·$NBSP_STR")
        result = LEADING_SYMBOL_REGEX.replace(result, "$1$2$NBSP_STR$3")
        result = TRAILING_EMOJI_REGEX.replace(result, "$1$NBSP_STR$2")
        result = TRAILING_SINGLE_HANGUL_REGEX.replace(result, "$1$NBSP_STR$2")

        return result
    }
}
