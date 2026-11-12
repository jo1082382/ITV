package com.example.itv.domain.coaching

/**
 * 사용자 설정: 개입 방식
 */
enum class InterventionType(val title: String, val description: String) {
    CHARACTER_OVERLAY(
        title = "캐릭터 개입 (오버레이 + 알림)",
        description = "숏폼 시청 중 강아지 캐릭터가 화면 위에 나타나 대화와 미션으로 휴식을 권합니다."
    ),
    NOTIFICATION_ONLY(
        title = "알림만 받기 (푸시 알림)",
        description = "화면을 가리지 않고 상단 푸시 알림으로만 가볍게 리마인드합니다."
    )
}

/**
 * 사용자 설정: 코칭 강도 (개입 빈도, 주기, 임계값 3단계)
 */
enum class CoachingIntensity(val title: String, val subtitle: String, val description: String) {
    GENTLE(
        title = "약하게",
        subtitle = "자율적 조절 중심",
        description = "개입 빈도가 낮고 쿨타임이 깁니다. 알림과 개입이 사용자의 몰입을 최소한으로 방해합니다."
    ),
    NORMAL(
        title = "보통",
        subtitle = "표준 밸런스 권장",
        description = "연속 시청 시간과 누적 피로도를 균형 있게 반영하여 적정 시점에 부드럽게 개입합니다."
    ),
    STRONG(
        title = "강하게",
        subtitle = "적극적 과몰입 차단",
        description = "피로도와 연속 시청에 민감하게 반응하여 더 빠르고 짧은 주기로 휴식을 유도합니다."
    )
}

/**
 * 사용자 설정: 캐릭터 개입 강도 (화면 등장 비율 및 모션 크기 3단계)
 */
enum class CharacterIntensity(val title: String, val subtitle: String, val description: String) {
    GENTLE(
        title = "약하게",
        subtitle = "화면 구석 / 얌전한 동작",
        description = "화면 모서리에 작게 빼꼼 고개를 내밀며, 영상을 많이 가리지 않고 얌전하게 휴식을 제안합니다."
    ),
    NORMAL(
        title = "보통",
        subtitle = "적정 크기 / 부드러운 이동",
        description = "화면 가장자리에서 적정 크기로 걸어 나와 말풍선과 함께 균형 있게 시선을 유도합니다."
    ),
    STRONG(
        title = "강하게",
        subtitle = "화면 중앙 점유 / 적극적인 모션",
        description = "화면 중앙에 큼직하게 자리 잡고 활발하게 움직이며, 숏폼 시청 루프를 확실하게 차단합니다."
    )
}

/**
 * 사용자 설정: 캐릭터 개입 시 말투 (3단계)
 */
enum class CoachingTone(val title: String, val subtitle: String, val sampleDialog: String) {
    SOFT(
        title = "부드럽게",
        subtitle = "따뜻하고 공감하는 어조",
        sampleDialog = "“오늘도 정말 고생 많으셨어요. 눈이 조금 피로해 보이는데, 잠깐 물 한 잔 마시고 오는 건 어때요? 🌿”"
    ),
    PLAYFUL(
        title = "장난스럽게",
        subtitle = "유쾌하고 친구 같은 어조",
        sampleDialog = "“어라?! 손가락이 스와이프 모터 달았나요? 벌써 몇 편째예요! 저랑 같이 1분만 기지개 켜기 대결해요! 🐾”"
    ),
    FIRM(
        title = "강하게",
        subtitle = "단호하고 직관적인 어조",
        sampleDialog = "“잠깐 멈추세요! 목표 시간을 넘겼어요. 지금 화면을 끄지 않으면 후회할지도 몰라요. 당장 스톱! 🛑”"
    )
}

/**
 * 코칭 종합 설정 엔티티
 */
data class UserCoachingSettings(
    val interventionType: InterventionType = InterventionType.CHARACTER_OVERLAY,
    val coachingIntensity: CoachingIntensity = CoachingIntensity.NORMAL,
    val characterIntensity: CharacterIntensity = CharacterIntensity.NORMAL,
    val tone: CoachingTone = CoachingTone.SOFT
) {
    // 하위 호환성 유지 프로퍼티
    val intensity: CoachingIntensity get() = coachingIntensity
}
