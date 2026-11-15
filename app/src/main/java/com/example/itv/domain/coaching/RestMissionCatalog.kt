package com.example.itv.domain.coaching

enum class UserPosture(val label: String) {
    SITTING_OR_STANDING("정면 파지 각도 (어깨·등 뭉침)"),
    LYING_DOWN("수평/하향 각도 (목 하중·거북목 부담)"),
    TABLE_OR_DESK("테이블 거치 각도 (고개숙임·식사 집중 필요)"),
    FACEDOWN("스마트폰 엎어둠 (완전 차단)"),
    UNKNOWN("각도 측정 중")
}

data class RestRecommendation(
    val title: String,
    val action: String,
    val icon: String = "",
    val targetPosture: UserPosture? = null,
    val isFacedownRequired: Boolean = false,
    val category: String = "일반",
    val targetBodyPart: String = "전신"
)

object RestMissionCatalog {
    // 1. 목(경추) 집중 이완 스트레칭 (폰을 아래로 눕혀 보거나 고개를 깊게 숙인 상태)
    val NECK_RECOMMENDATIONS = listOf(
        RestRecommendation(
            title = "목 뻐근함 풀기",
            action = "고개가 숙여져 목이 굳어있어요. 고개를 뒤로 천천히 젖히며 뻐근한 목을 시원하게 풀어주세요.",
            icon = "",
            targetPosture = UserPosture.LYING_DOWN,
            category = "목 풀기",
            targetBodyPart = "목"
        ),
        RestRecommendation(
            title = "목 좌우 늘려주기",
            action = "목이 굳지 않도록 손으로 머리를 잡고 옆으로 지그시 기울여 굳은 목 근육을 부드럽게 늘려주세요.",
            icon = "",
            targetPosture = UserPosture.LYING_DOWN,
            category = "목 풀기",
            targetBodyPart = "목"
        ),
        RestRecommendation(
            title = "목 가볍게 돌리기",
            action = "목에 실린 부담을 덜어주기 위해 고개를 천천히 둥글게 굴리며 목 주변 긴장을 풀어주세요.",
            icon = "",
            targetPosture = UserPosture.LYING_DOWN,
            category = "목 풀기",
            targetBodyPart = "목"
        ),
        RestRecommendation(
            title = "턱 당겨 자세 바로잡기",
            action = "숙여진 고개를 바로잡기 위해 턱을 목 쪽으로 가볍게 당겨 바른 자세를 만들어주세요.",
            icon = "",
            targetPosture = UserPosture.LYING_DOWN,
            category = "목 풀기",
            targetBodyPart = "목"
        )
    )

    // 2. 어깨·등 집중 이완 스트레칭 (장시간 고정된 각도로 폰을 쥐고 있는 상태)
    val SHOULDER_RECOMMENDATIONS = listOf(
        RestRecommendation(
            title = "뭉친 어깨 굴려주기",
            action = "폰을 쥐고 있느라 어깨가 뭉쳤어요. 어깨를 뒤로 크게 5번 굴려 굳은 어깨를 시원하게 풀어주세요.",
            icon = "",
            targetPosture = UserPosture.SITTING_OR_STANDING,
            category = "어깨 풀기",
            targetBodyPart = "어깨"
        ),
        RestRecommendation(
            title = "기지개 켜며 등 펴기",
            action = "두 팔을 머리 위로 쭉 뻗어 시원하게 기지개를 켜며 굽은 어깨와 등을 활짝 펴주세요.",
            icon = "",
            targetPosture = UserPosture.SITTING_OR_STANDING,
            category = "어깨 풀기",
            targetBodyPart = "어깨"
        ),
        RestRecommendation(
            title = "가슴과 어깨 활짝 열기",
            action = "양팔을 뒤로 뻗으며 굽어있던 어깨와 가슴을 활짝 열어 상체의 긴장을 털어내세요.",
            icon = "",
            targetPosture = UserPosture.SITTING_OR_STANDING,
            category = "어깨 풀기",
            targetBodyPart = "어깨"
        ),
        RestRecommendation(
            title = "팔 안아 당겨 어깨 풀기",
            action = "한쪽 팔을 가슴 앞으로 뻗고 반대 팔로 안아 당기며 어깨 뒤쪽을 시원하게 늘려주세요.",
            icon = "",
            targetPosture = UserPosture.SITTING_OR_STANDING,
            category = "어깨 풀기",
            targetBodyPart = "어깨"
        ),
        RestRecommendation(
            title = "손목과 손가락 털기",
            action = "스마트폰을 쥐느라 굳은 손을 가볍게 털고 주먹을 쥐었다 폈다 하며 손목을 풀어주세요.",
            icon = "",
            targetPosture = UserPosture.SITTING_OR_STANDING,
            category = "손목 풀기",
            targetBodyPart = "어깨"
        )
    )

    // 3. 허리·척추 집중 이완 스트레칭 (구부정한 자세 및 척추 긴장)
    val WAIST_RECOMMENDATIONS = listOf(
        RestRecommendation(
            title = "허리 곧게 펴기",
            action = "구부정한 자세로 허리에 부담이 가고 있어요. 허리를 곧게 펴고 깊게 숨을 들이마셔보세요.",
            icon = "",
            targetPosture = null,
            category = "허리 풀기",
            targetBodyPart = "허리"
        ),
        RestRecommendation(
            title = "허리 좌우 비틀기",
            action = "허리가 뻐근할 땐 상체를 좌우로 천천히 돌리며 굳어있는 허리와 척추를 부드럽게 풀어주세요.",
            icon = "",
            targetPosture = null,
            category = "허리 풀기",
            targetBodyPart = "허리"
        ),
        RestRecommendation(
            title = "일어나서 몸 가볍게 털기",
            action = "오래 앉아있어 온몸이 굳었어요. 자리에서 잠시 일어나 기지개를 켜며 가볍게 몸을 털어주세요.",
            icon = "",
            targetPosture = null,
            category = "전신 풀기",
            targetBodyPart = "허리"
        )
    )

    // 4. 테이블 거치 시 추천 (테이블 내려다봄 방지 및 식사 집중)
    val TABLE_RECOMMENDATIONS = listOf(
        RestRecommendation(
            title = "고개 들고 허리 펴기",
            action = "고개를 푹 숙인 채 내려다보고 있어요. 고개를 들고 허리를 곧게 펴주세요.",
            icon = "",
            targetPosture = UserPosture.TABLE_OR_DESK,
            category = "자세 바로잡기",
            targetBodyPart = "목"
        ),
        RestRecommendation(
            title = "뭉친 어깨 굴려주기",
            action = "식탁 앞에서 굳어있던 어깨를 뒤로 5번 천천히 굴려 피로를 풀어주세요.",
            icon = "",
            targetPosture = UserPosture.TABLE_OR_DESK,
            category = "어깨 풀기",
            targetBodyPart = "어깨"
        ),
        RestRecommendation(
            title = "먼 곳 바라보며 눈 쉬기",
            action = "화면에서 눈을 떼고 창밖이나 먼 벽을 15초간 멍하니 바라보며 눈의 피로를 덜어주세요.",
            icon = "",
            targetPosture = UserPosture.TABLE_OR_DESK,
            category = "눈 쉬기",
            targetBodyPart = "눈"
        ),
        RestRecommendation(
            title = "시원한 물 한 모금",
            action = "화면을 잠시 끄고 시원한 물 한 모금 마시며 차분하게 휴식해보세요.",
            icon = "",
            targetPosture = UserPosture.TABLE_OR_DESK,
            category = "휴식",
            targetBodyPart = "수분"
        )
    )

    // 5. 공통 안구·두뇌 쿨다운 (도파민 진정, 시신경 휴식)
    val GENERAL_RECOMMENDATIONS = listOf(
        RestRecommendation(
            title = "피로한 눈 감고 쉬기",
            action = "화면을 오래 보아 눈이 피로해요. 눈을 지그시 감고 10초간 편안하게 쉬어주세요.",
            icon = "",
            targetPosture = null,
            category = "눈 쉬기",
            targetBodyPart = "눈"
        ),
        RestRecommendation(
            title = "깊은 심호흡으로 이완",
            action = "숨을 깊게 들이마시고 천천히 내쉬며 굳어있던 몸 전체의 힘을 툭 빼보세요.",
            icon = "",
            targetPosture = null,
            category = "심호흡",
            targetBodyPart = "두뇌쿨다운"
        ),
        RestRecommendation(
            title = "손바닥으로 눈 덮기",
            action = "손바닥을 비벼 따뜻하게 만든 뒤 눈 위에 가볍게 얹어 눈의 피로를 녹여주세요.",
            icon = "",
            targetPosture = null,
            category = "눈 쉬기",
            targetBodyPart = "눈"
        ),
        RestRecommendation(
            title = "시원한 물 한 잔",
            action = "화면을 끄고 시원한 물 한 잔을 마시며 굳은 몸을 환기해주세요.",
            icon = "",
            targetPosture = null,
            category = "휴식",
            targetBodyPart = "수분"
        )
    )

    // 6. 강한 개입 전용: 스마트폰 엎어두기 강제 미션 (디지털 디톡스)
    val FACEDOWN_RECOMMENDATIONS = listOf(
        RestRecommendation(
            title = "스마트폰 엎어두기 · 기지개 켜기",
            action = "스마트폰을 바닥에 엎어두고, 허리를 곧게 펴며 두 팔을 쭉 뻗어 기지개를 켜주세요.",
            icon = "",
            isFacedownRequired = true,
            category = "엎어두고 휴식",
            targetBodyPart = "엎어둠"
        ),
        RestRecommendation(
            title = "스마트폰 엎어두기 · 어깨 풀기",
            action = "스마트폰을 바닥에 엎어두고, 뭉친 어깨를 뒤로 천천히 굴려 시원하게 풀어주세요.",
            icon = "",
            isFacedownRequired = true,
            category = "엎어두고 휴식",
            targetBodyPart = "엎어둠"
        ),
        RestRecommendation(
            title = "스마트폰 엎어두기 · 허리 펴기",
            action = "스마트폰을 바닥에 엎어두고, 허리를 꼿꼿이 세워 바른 자세로 잠시 쉬어주세요.",
            icon = "",
            isFacedownRequired = true,
            category = "엎어두고 휴식",
            targetBodyPart = "엎어둠"
        ),
        RestRecommendation(
            title = "스마트폰 엎어두기 · 눈 쉬기",
            action = "스마트폰을 바닥에 엎어두고, 눈을 편안하게 감은 채 굳은 몸에 힘을 빼주세요.",
            icon = "",
            isFacedownRequired = true,
            category = "엎어두고 휴식",
            targetBodyPart = "엎어둠"
        )
    )

    val ALL_RECOMMENDATIONS: List<RestRecommendation> by lazy {
        NECK_RECOMMENDATIONS + SHOULDER_RECOMMENDATIONS + WAIST_RECOMMENDATIONS + TABLE_RECOMMENDATIONS + GENERAL_RECOMMENDATIONS + FACEDOWN_RECOMMENDATIONS
    }

    fun pick(posture: UserPosture, isStrong: Boolean): RestRecommendation {
        if (isStrong) {
            return FACEDOWN_RECOMMENDATIONS.random()
        }
        val pool = when (posture) {
            UserPosture.LYING_DOWN -> NECK_RECOMMENDATIONS + WAIST_RECOMMENDATIONS + GENERAL_RECOMMENDATIONS
            UserPosture.SITTING_OR_STANDING -> SHOULDER_RECOMMENDATIONS + WAIST_RECOMMENDATIONS + GENERAL_RECOMMENDATIONS
            UserPosture.TABLE_OR_DESK -> TABLE_RECOMMENDATIONS + WAIST_RECOMMENDATIONS + GENERAL_RECOMMENDATIONS
            else -> NECK_RECOMMENDATIONS + SHOULDER_RECOMMENDATIONS + WAIST_RECOMMENDATIONS + GENERAL_RECOMMENDATIONS
        }
        return pool.random()
    }
}
