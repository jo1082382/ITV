package com.example.itv.domain.coaching

import com.example.itv.infrastructure.observation.InferredAppCategory
import java.time.LocalTime

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
    val boldPhrases: List<String> = emptyList(),
    val icon: String = "",
    val targetPosture: UserPosture? = null,
    val isFacedownRequired: Boolean = false,
    val category: String = "일반",
    val targetBodyPart: String = "전신",
    val isCombo: Boolean = false,
    val targetCategoryTag: String = "ALL"
)

/**
 * 센서 환경, 앱 카테고리, 지속 시간 등 미션 선정에 필요한 실시간 컨텍스트
 */
data class MissionSelectionContext(
    val appCategory: InferredAppCategory = InferredAppCategory.GENERAL_APP,
    val isShortform: Boolean = false,
    val isLongform: Boolean = false,
    val isGame: Boolean = false,
    val isStationary: Boolean = false,
    val isDarkOrBedtime: Boolean = false,
    val continuousUsedMs: Long = 0L,
    val currentHour: Int = LocalTime.now().hour,
    val posture: UserPosture = UserPosture.UNKNOWN
)

/**
 * 최근 추천되거나 완료된 미션의 반복 노출을 방지하는 쿨다운 트래커 (최근 3회 FIFO)
 */
object MissionCooldownTracker {
    private const val MAX_HISTORY = 3
    private val history = java.util.ArrayDeque<String>()

    @Synchronized
    fun isCooldown(title: String): Boolean = history.contains(title)

    @Synchronized
    fun record(title: String) {
        history.remove(title)
        history.addLast(title)
        while (history.size > MAX_HISTORY) {
            history.removeFirst()
        }
    }

    @Synchronized
    fun clear() {
        history.clear()
    }

    @Synchronized
    fun getHistory(): List<String> = history.toList()
}

object RestMissionCatalog {

    // =========================================================================
    // 0. [1순위] 원스톱 콤보 미션 (롱폼 시청 + 미동 없는 고정 자세 시 눈+몸 동시 이완)
    // =========================================================================
    val COMBO_RECOMMENDATIONS = listOf(
        RestRecommendation(
            title = "눈 감고 기지개 켜기",
            action = "화면을 끄고 눈을 편안히 감은 채, 두 팔을 머리 위로 쭉 뻗어 15초간 시원하게 기지개를 켜주세요.",
            boldPhrases = listOf("눈을 편안히 감은 채, 두 팔을 머리 위로 쭉 뻗어 15초간 시원하게 기지개를 켜주세요."),
            icon = "",
            targetPosture = null,
            category = "눈·몸 복합 이완",
            targetBodyPart = "눈+전신",
            isCombo = true,
            targetCategoryTag = "COMBO"
        ),
        RestRecommendation(
            title = "먼 곳 바라보며 어깨 굴리기",
            action = "화면에서 눈을 떼 창밖이나 먼 벽을 멍하니 바라보며, 굳어있던 어깨를 뒤로 천천히 5번 굴려주세요.",
            boldPhrases = listOf("창밖이나 먼 벽을 멍하니 바라보며, 굳어있던 어깨를 뒤로 천천히 5번 굴려주세요."),
            icon = "",
            targetPosture = null,
            category = "눈·몸 복합 이완",
            targetBodyPart = "눈+어깨",
            isCombo = true,
            targetCategoryTag = "COMBO"
        ),
        RestRecommendation(
            title = "눈 감고 목 뒤로 젖히기",
            action = "지친 눈을 감고 턱을 가볍게 들어, 고개를 뒤로 천천히 젖히며 뻐근한 목과 시신경을 함께 풀어주세요.",
            boldPhrases = listOf("지친 눈을 감고 턱을 가볍게 들어, 고개를 뒤로 천천히 젖히며"),
            icon = "",
            targetPosture = null,
            category = "눈·몸 복합 이완",
            targetBodyPart = "눈+목",
            isCombo = true,
            targetCategoryTag = "COMBO"
        ),
        RestRecommendation(
            title = "손바닥 온기 눈 덮고 어깨 풀기",
            action = "양 손바닥을 빠르게 비벼 따뜻하게 만든 뒤 눈 위에 얹고, 어깨와 목의 힘을 툭 빼며 숨을 깊게 내쉬어보세요.",
            boldPhrases = listOf("따뜻하게 만든 뒤 눈 위에 얹고, 어깨와 목의 힘을 툭 빼며"),
            icon = "",
            targetPosture = null,
            category = "눈·몸 복합 이완",
            targetBodyPart = "눈+어깨",
            isCombo = true,
            targetCategoryTag = "COMBO"
        ),
        RestRecommendation(
            title = "눈 감고 심호흡 · 허리 세우기",
            action = "눈을 지그시 감고 숨을 깊게 들이마시며, 구부정했던 허리와 척추를 곧게 세워 15초간 편안히 머물러보세요.",
            boldPhrases = listOf("눈을 지그시 감고 숨을 깊게 들이마시며, 구부정했던 허리와 척추를 곧게 세워"),
            icon = "",
            targetPosture = null,
            category = "눈·몸 복합 이완",
            targetBodyPart = "눈+허리",
            isCombo = true,
            targetCategoryTag = "COMBO"
        )
    )

    // =========================================================================
    // 1. 게임 특화 스트레칭 (손목, 엄지손가락, 전완근, 어깨 긴장 완화)
    // =========================================================================
    val GAME_RECOMMENDATIONS = listOf(
        RestRecommendation(
            title = "손가락 깍지 끼고 밀기",
            action = "조작하느라 굳은 손가락 깍지를 끼고, 손바닥을 앞으로 쭉 밀며 손가락 마디와 손목을 시원하게 펴주세요.",
            boldPhrases = listOf("손바닥을 앞으로 쭉 밀며 손가락 마디와 손목을 시원하게 펴주세요."),
            icon = "",
            targetPosture = null,
            category = "손목·손가락 풀기",
            targetBodyPart = "손목",
            targetCategoryTag = "GAME"
        ),
        RestRecommendation(
            title = "엄지손가락 및 손목 털기",
            action = "연타 조작으로 긴장된 엄지 관절을 부드럽게 주무르고, 양 손목에 힘을 빼고 10초간 가볍게 털어주세요.",
            boldPhrases = listOf("엄지 관절을 부드럽게 주무르고, 양 손목에 힘을 빼고 10초간 가볍게 털어주세요."),
            icon = "",
            targetPosture = null,
            category = "손목·손가락 풀기",
            targetBodyPart = "손목",
            targetCategoryTag = "GAME"
        ),
        RestRecommendation(
            title = "손목 젖혀 당겨주기",
            action = "한쪽 팔을 앞으로 뻗고 반대 손으로 손가락을 몸 쪽으로 지그시 당겨, 굳어있는 손목과 팔 안쪽을 늘려주세요.",
            boldPhrases = listOf("반대 손으로 손가락을 몸 쪽으로 지그시 당겨, 굳어있는 손목과 팔 안쪽을 늘려주세요."),
            icon = "",
            targetPosture = null,
            category = "전완근 이완",
            targetBodyPart = "손목",
            targetCategoryTag = "GAME"
        ),
        RestRecommendation(
            title = "어깨 으쓱 올렸다 내리기",
            action = "게임에 집중하느라 바짝 솟아있던 어깨를 귀 가까이 힘껏 올렸다가, 숨을 후 내쉬며 힘을 툭 떨어뜨려주세요.",
            boldPhrases = listOf("귀 가까이 힘껏 올렸다가, 숨을 후 내쉬며 힘을 툭 떨어뜨려주세요."),
            icon = "",
            targetPosture = null,
            category = "어깨 풀기",
            targetBodyPart = "어깨",
            targetCategoryTag = "GAME"
        ),
        RestRecommendation(
            title = "주먹 쥐었다 펴기",
            action = "스마트폰을 꽉 쥐고 있던 손의 긴장을 풀기 위해, 주먹을 꽉 쥐었다가 손가락을 활짝 펴기를 10회 반복해주세요.",
            boldPhrases = listOf("주먹을 꽉 쥐었다가 손가락을 활짝 펴기를 10회 반복해주세요."),
            icon = "",
            targetPosture = null,
            category = "손 풀기",
            targetBodyPart = "손목",
            targetCategoryTag = "GAME"
        )
    )

    // =========================================================================
    // 2. 숏폼 특화 미션 (도파민 쿨다운, 시신경 급격한 피로 회복)
    // =========================================================================
    val SHORTFORM_RECOMMENDATIONS = listOf(
        RestRecommendation(
            title = "먼 곳 바라보며 눈 쉬기 (20-20-20)",
            action = "빠른 화면 전환에 지친 눈을 위해 창밖이나 먼 벽을 20초간 멍하니 바라보며 초점 근육을 완전히 풀어주세요.",
            boldPhrases = listOf("창밖이나 먼 벽을 20초간 멍하니 바라보며 초점 근육을 완전히 풀어주세요."),
            icon = "",
            targetPosture = null,
            category = "시신경 쿨다운",
            targetBodyPart = "눈",
            targetCategoryTag = "SHORTFORM"
        ),
        RestRecommendation(
            title = "도파민 쿨다운 심호흡",
            action = "끝없는 영상 스크롤로 과열된 뇌를 식혀주세요. 눈을 감고 코로 깊게 숨을 들이마시고 입으로 천천히 내쉬세요.",
            boldPhrases = listOf("눈을 감고 코로 깊게 숨을 들이마시고 입으로 천천히 내쉬세요."),
            icon = "",
            targetPosture = null,
            category = "두뇌 쿨다운",
            targetBodyPart = "두뇌쿨다운",
            targetCategoryTag = "SHORTFORM"
        ),
        RestRecommendation(
            title = "관자놀이 지그시 누르기",
            action = "시각 자극으로 긴장된 눈가를 풀기 위해, 양 검지로 관자놀이를 둥글게 원을 그리며 지그시 마사지해주세요.",
            boldPhrases = listOf("양 검지로 관자놀이를 둥글게 원을 그리며 지그시 마사지해주세요."),
            icon = "",
            targetPosture = null,
            category = "시신경 쿨다운",
            targetBodyPart = "눈",
            targetCategoryTag = "SHORTFORM"
        )
    )

    // =========================================================================
    // 3. 시간대별 성격 분리 미션 (주간 활동형 vs 야간 수면유도형)
    // =========================================================================
    val DAY_RECOMMENDATIONS = listOf(
        RestRecommendation(
            title = "자리에서 일어나 걷기",
            action = "장시간 앉아있어 하체 혈류가 정체되었어요. 자리에서 잠시 일어나 기지개를 켜고 주변을 가볍게 한 바퀴 걸어보세요.",
            boldPhrases = listOf("자리에서 잠시 일어나 기지개를 켜고 주변을 가볍게 한 바퀴 걸어보세요."),
            icon = "",
            targetPosture = null,
            category = "전신 순환",
            targetBodyPart = "전신",
            targetCategoryTag = "DAY"
        ),
        RestRecommendation(
            title = "시원한 물 한 잔 마시기",
            action = "화면을 끄고 시원한 물 한 잔을 마시며 갈증을 해소하고 몸 전체에 수분을 보충해주세요.",
            boldPhrases = listOf("시원한 물 한 잔을 마시며 갈증을 해소하고"),
            icon = "",
            targetPosture = null,
            category = "수분 보충",
            targetBodyPart = "수분",
            targetCategoryTag = "DAY"
        )
    )

    val NIGHT_RECOMMENDATIONS = listOf(
        RestRecommendation(
            title = "4-7-8 수면 호흡법",
            action = "몸을 수면 상태로 전환하기 위해 4초간 숨을 들이마시고, 7초간 멈추고, 8초간 천천히 내쉬며 이완해보세요.",
            boldPhrases = listOf("4초간 숨을 들이마시고, 7초간 멈추고, 8초간 천천히 내쉬며 이완해보세요."),
            icon = "",
            targetPosture = null,
            category = "수면 유도 이완",
            targetBodyPart = "두뇌쿨다운",
            targetCategoryTag = "NIGHT"
        ),
        RestRecommendation(
            title = "어두운 방 눈 온찜질",
            action = "따뜻하게 비빈 손바닥을 눈 위에 가볍게 얹고, 어두운 암실에서 블루라이트에 지친 눈을 편안히 녹여주세요.",
            boldPhrases = listOf("따뜻하게 비빈 손바닥을 눈 위에 가볍게 얹고, 어두운 암실에서"),
            icon = "",
            targetPosture = null,
            category = "수면 안구 휴식",
            targetBodyPart = "눈",
            targetCategoryTag = "NIGHT"
        )
    )

    // =========================================================================
    // 4. 목(경추) 집중 이완 스트레칭 (폰을 아래로 눕혀 보거나 고개를 깊게 숙인 상태)
    // =========================================================================
    val NECK_RECOMMENDATIONS = listOf(
        RestRecommendation(
            title = "목 뻐근함 풀기",
            action = "고개가 숙여져 목이 굳어있어요. 고개를 뒤로 천천히 젖히며 뻐근한 목을 시원하게 풀어주세요.",
            boldPhrases = listOf("고개를 뒤로 천천히 젖히며 뻐근한 목을 시원하게 풀어주세요."),
            icon = "",
            targetPosture = UserPosture.LYING_DOWN,
            category = "목 풀기",
            targetBodyPart = "목"
        ),
        RestRecommendation(
            title = "목 좌우 늘려주기",
            action = "목이 굳지 않도록 손으로 머리를 잡고 옆으로 지그시 기울여 굳은 목 근육을 부드럽게 늘려주세요.",
            boldPhrases = listOf("손으로 머리를 잡고 옆으로 지그시 기울여 굳은 목 근육을 부드럽게 늘려주세요."),
            icon = "",
            targetPosture = UserPosture.LYING_DOWN,
            category = "목 풀기",
            targetBodyPart = "목"
        ),
        RestRecommendation(
            title = "목 가볍게 돌리기",
            action = "목에 실린 부담을 덜어주기 위해 고개를 천천히 둥글게 굴리며 목 주변 긴장을 풀어주세요.",
            boldPhrases = listOf("고개를 천천히 둥글게 굴리며 목 주변 긴장을 풀어주세요."),
            icon = "",
            targetPosture = UserPosture.LYING_DOWN,
            category = "목 풀기",
            targetBodyPart = "목"
        ),
        RestRecommendation(
            title = "턱 당겨 자세 바로잡기",
            action = "숙여진 고개를 바로잡기 위해 턱을 목 쪽으로 가볍게 당겨 바른 자세를 만들어주세요.",
            boldPhrases = listOf("턱을 목 쪽으로 가볍게 당겨 바른 자세를 만들어주세요."),
            icon = "",
            targetPosture = UserPosture.LYING_DOWN,
            category = "목 풀기",
            targetBodyPart = "목"
        )
    )

    // =========================================================================
    // 5. 어깨·등 집중 이완 스트레칭 (장시간 고정된 각도로 폰을 쥐고 있는 상태)
    // =========================================================================
    val SHOULDER_RECOMMENDATIONS = listOf(
        RestRecommendation(
            title = "뭉친 어깨 굴려주기",
            action = "폰을 쥐고 있느라 어깨가 뭉쳤어요. 어깨를 뒤로 크게 5번 굴려 굳은 어깨를 시원하게 풀어주세요.",
            boldPhrases = listOf("어깨를 뒤로 크게 5번 굴려 굳은 어깨를 시원하게 풀어주세요."),
            icon = "",
            targetPosture = UserPosture.SITTING_OR_STANDING,
            category = "어깨 풀기",
            targetBodyPart = "어깨"
        ),
        RestRecommendation(
            title = "기지개 켜며 등 펴기",
            action = "오래 같은 자세로 있어 어깨와 등이 굽었어요. 두 팔을 머리 위로 쭉 뻗어 시원하게 기지개를 켜주세요.",
            boldPhrases = listOf("두 팔을 머리 위로 쭉 뻗어 시원하게 기지개를 켜주세요."),
            icon = "",
            targetPosture = UserPosture.SITTING_OR_STANDING,
            category = "어깨 풀기",
            targetBodyPart = "어깨"
        ),
        RestRecommendation(
            title = "가슴과 어깨 활짝 열기",
            action = "상체가 앞으로 말려 굳어있어요. 양팔을 뒤로 뻗으며 굽어있던 어깨와 가슴을 활짝 열어주세요.",
            boldPhrases = listOf("양팔을 뒤로 뻗으며 굽어있던 어깨와 가슴을 활짝 열어주세요."),
            icon = "",
            targetPosture = UserPosture.SITTING_OR_STANDING,
            category = "어깨 풀기",
            targetBodyPart = "어깨"
        ),
        RestRecommendation(
            title = "팔 안아 당겨 어깨 풀기",
            action = "어깨 뒤쪽 근육이 긴장되어 있어요. 한쪽 팔을 가슴 앞으로 뻗고 반대 팔로 안아 당겨 늘려주세요.",
            boldPhrases = listOf("한쪽 팔을 가슴 앞으로 뻗고 반대 팔로 안아 당겨 늘려주세요."),
            icon = "",
            targetPosture = UserPosture.SITTING_OR_STANDING,
            category = "어깨 풀기",
            targetBodyPart = "어깨"
        ),
        RestRecommendation(
            title = "손목과 손가락 털기",
            action = "스마트폰을 쥐느라 굳은 손을 가볍게 털고 주먹을 쥐었다 폈다 하며 손목을 풀어주세요.",
            boldPhrases = listOf("손을 가볍게 털고 주먹을 쥐었다 폈다 하며 손목을 풀어주세요."),
            icon = "",
            targetPosture = UserPosture.SITTING_OR_STANDING,
            category = "손목 풀기",
            targetBodyPart = "어깨"
        )
    )

    // =========================================================================
    // 6. 허리·척추 집중 이완 스트레칭 (구부정한 자세 및 척추 긴장)
    // =========================================================================
    val WAIST_RECOMMENDATIONS = listOf(
        RestRecommendation(
            title = "허리 곧게 펴기",
            action = "구부정한 자세로 허리에 부담이 가고 있어요. 허리를 곧게 펴고 깊게 숨을 들이마셔보세요.",
            boldPhrases = listOf("허리를 곧게 펴고 깊게 숨을 들이마셔보세요."),
            icon = "",
            targetPosture = null,
            category = "허리 풀기",
            targetBodyPart = "허리"
        ),
        RestRecommendation(
            title = "허리 좌우 비틀기",
            action = "허리가 뻐근할 땐 상체를 좌우로 천천히 돌리며 굳어있는 허리와 척추를 부드럽게 풀어주세요.",
            boldPhrases = listOf("상체를 좌우로 천천히 돌리며 굳어있는 허리와 척추를 부드럽게 풀어주세요."),
            icon = "",
            targetPosture = null,
            category = "허리 풀기",
            targetBodyPart = "허리"
        ),
        RestRecommendation(
            title = "일어나서 몸 가볍게 털기",
            action = "오래 앉아있어 온몸이 굳었어요. 자리에서 잠시 일어나 기지개를 켜며 가볍게 몸을 털어주세요.",
            boldPhrases = listOf("자리에서 잠시 일어나 기지개를 켜며 가볍게 몸을 털어주세요."),
            icon = "",
            targetPosture = null,
            category = "전신 풀기",
            targetBodyPart = "허리"
        )
    )

    // =========================================================================
    // 7. 테이블 거치 시 추천 (테이블 내려다봄 방지 및 식사 집중)
    // =========================================================================
    val TABLE_RECOMMENDATIONS = listOf(
        RestRecommendation(
            title = "고개 들고 허리 펴기",
            action = "고개를 푹 숙인 채 내려다보고 있어요. 고개를 들고 허리를 곧게 펴주세요.",
            boldPhrases = listOf("고개를 들고 허리를 곧게 펴주세요."),
            icon = "",
            targetPosture = UserPosture.TABLE_OR_DESK,
            category = "자세 바로잡기",
            targetBodyPart = "목"
        ),
        RestRecommendation(
            title = "뭉친 어깨 굴려주기",
            action = "식탁 앞에서 굳어있던 어깨를 뒤로 5번 천천히 굴려 피로를 풀어주세요.",
            boldPhrases = listOf("어깨를 뒤로 5번 천천히 굴려 피로를 풀어주세요."),
            icon = "",
            targetPosture = UserPosture.TABLE_OR_DESK,
            category = "어깨 풀기",
            targetBodyPart = "어깨"
        ),
        RestRecommendation(
            title = "먼 곳 바라보며 눈 쉬기",
            action = "화면에서 눈을 떼고 창밖이나 먼 벽을 15초간 멍하니 바라보며 눈의 피로를 덜어주세요.",
            boldPhrases = listOf("화면에서 눈을 떼고 창밖이나 먼 벽을 15초간 멍하니 바라보며"),
            icon = "",
            targetPosture = UserPosture.TABLE_OR_DESK,
            category = "눈 쉬기",
            targetBodyPart = "눈"
        ),
        RestRecommendation(
            title = "시원한 물 한 모금",
            action = "화면을 잠시 끄고 시원한 물 한 모금 마시며 차분하게 휴식해보세요.",
            boldPhrases = listOf("화면을 잠시 끄고 시원한 물 한 모금 마시며"),
            icon = "",
            targetPosture = UserPosture.TABLE_OR_DESK,
            category = "휴식",
            targetBodyPart = "수분"
        )
    )

    // =========================================================================
    // 8. 공통 안구·두뇌 쿨다운 (도파민 진정, 시신경 휴식)
    // =========================================================================
    val GENERAL_RECOMMENDATIONS = listOf(
        RestRecommendation(
            title = "피로한 눈 감고 쉬기",
            action = "화면을 오래 보아 눈이 피로해요. 눈을 지그시 감고 10초간 편안하게 쉬어주세요.",
            boldPhrases = listOf("눈을 지그시 감고 10초간 편안하게 쉬어주세요."),
            icon = "",
            targetPosture = null,
            category = "눈 쉬기",
            targetBodyPart = "눈"
        ),
        RestRecommendation(
            title = "깊은 심호흡으로 이완",
            action = "몸의 긴장을 풀기 위해 숨을 깊게 들이마시고 천천히 내쉬며 굳어있던 몸 전체의 힘을 툭 빼보세요.",
            boldPhrases = listOf("숨을 깊게 들이마시고 천천히 내쉬며 굳어있던 몸 전체의 힘을 툭 빼보세요."),
            icon = "",
            targetPosture = null,
            category = "심호흡",
            targetBodyPart = "두뇌쿨다운"
        ),
        RestRecommendation(
            title = "손바닥으로 눈 덮기",
            action = "지친 눈을 달래기 위해 손바닥을 비벼 따뜻하게 만든 뒤 눈 위에 가볍게 얹어 눈의 피로를 녹여주세요.",
            boldPhrases = listOf("손바닥을 비벼 따뜻하게 만든 뒤 눈 위에 가볍게 얹어"),
            icon = "",
            targetPosture = null,
            category = "눈 쉬기",
            targetBodyPart = "눈"
        ),
        RestRecommendation(
            title = "시원한 물 한 잔",
            action = "잠시 기분을 환기할 수 있도록 화면을 끄고 시원한 물 한 잔을 마시며 굳은 몸을 깨워주세요.",
            boldPhrases = listOf("화면을 끄고 시원한 물 한 잔을 마시며"),
            icon = "",
            targetPosture = null,
            category = "휴식",
            targetBodyPart = "수분"
        )
    )

    // =========================================================================
    // 9. 강한 개입 전용: 스마트폰 엎어두기 강제 미션 (디지털 디톡스)
    // =========================================================================
    val FACEDOWN_RECOMMENDATIONS = listOf(
        RestRecommendation(
            title = "스마트폰 엎어두기 · 기지개 켜기",
            action = "화면에서 완전히 손을 떼어주세요. 스마트폰을 바닥에 엎어두고, 허리를 곧게 펴며 두 팔을 쭉 뻗어 기지개를 켜주세요.",
            boldPhrases = listOf("스마트폰을 바닥에 엎어두고, 허리를 곧게 펴며 두 팔을 쭉 뻗어 기지개를 켜주세요."),
            icon = "",
            isFacedownRequired = true,
            category = "엎어두고 휴식",
            targetBodyPart = "엎어둠"
        ),
        RestRecommendation(
            title = "스마트폰 엎어두기 · 어깨 풀기",
            action = "잠시 스마트폰을 내려놓을 시간이에요. 스마트폰을 바닥에 엎어두고, 뭉친 어깨를 뒤로 천천히 굴려 시원하게 풀어주세요.",
            boldPhrases = listOf("스마트폰을 바닥에 엎어두고, 뭉친 어깨를 뒤로 천천히 굴려 시원하게 풀어주세요."),
            icon = "",
            isFacedownRequired = true,
            category = "엎어두고 휴식",
            targetBodyPart = "엎어둠"
        ),
        RestRecommendation(
            title = "스마트폰 엎어두기 · 허리 펴기",
            action = "구부정한 자세를 바로잡아 주세요. 스마트폰을 바닥에 엎어두고, 허리를 꼿꼿이 세워 바른 자세로 잠시 쉬어주세요.",
            boldPhrases = listOf("스마트폰을 바닥에 엎어두고, 허리를 꼿꼿이 세워 바른 자세로 잠시 쉬어주세요."),
            icon = "",
            isFacedownRequired = true,
            category = "엎어두고 휴식",
            targetBodyPart = "엎어둠"
        ),
        RestRecommendation(
            title = "스마트폰 엎어두기 · 눈 쉬기",
            action = "피로해진 눈과 뇌를 쉬게 해주세요. 스마트폰을 바닥에 엎어두고, 눈을 편안하게 감은 채 굳은 몸에 힘을 빼주세요.",
            boldPhrases = listOf("스마트폰을 바닥에 엎어두고, 눈을 편안하게 감은 채 굳은 몸에 힘을 빼주세요."),
            icon = "",
            isFacedownRequired = true,
            category = "엎어두고 휴식",
            targetBodyPart = "엎어둠"
        )
    )

    val ALL_RECOMMENDATIONS: List<RestRecommendation> by lazy {
        COMBO_RECOMMENDATIONS +
            GAME_RECOMMENDATIONS +
            SHORTFORM_RECOMMENDATIONS +
            DAY_RECOMMENDATIONS +
            NIGHT_RECOMMENDATIONS +
            NECK_RECOMMENDATIONS +
            SHOULDER_RECOMMENDATIONS +
            WAIST_RECOMMENDATIONS +
            TABLE_RECOMMENDATIONS +
            GENERAL_RECOMMENDATIONS +
            FACEDOWN_RECOMMENDATIONS
    }

    /**
     * 콤보 미션 1순위 판정, 센서/앱 카테고리 가중치 산정, 미션 쿨다운(최근 중복 배제)을
     * 종합적으로 적용하여 최적의 맞춤형 휴식 미션을 선정합니다.
     */
    fun pick(
        posture: UserPosture,
        isStrong: Boolean,
        context: MissionSelectionContext? = null
    ): RestRecommendation {
        if (isStrong) {
            val available = FACEDOWN_RECOMMENDATIONS.filterNot { MissionCooldownTracker.isCooldown(it.title) }
                .ifEmpty { FACEDOWN_RECOMMENDATIONS }
            val chosen = available.random()
            MissionCooldownTracker.record(chosen.title)
            return chosen
        }

        val ctx = context ?: MissionSelectionContext(posture = posture)

        // 1단계: 롱폼 시청 중 + 미동 없음/장시간 고정 자세인 경우 ──> [콤보 미션 (눈+몸 동시 이완)] 1순위 선정!
        val isLongformStatic = (ctx.isLongform || ctx.continuousUsedMs >= 20 * 60 * 1000L) &&
            (ctx.isStationary || posture == UserPosture.LYING_DOWN || posture == UserPosture.SITTING_OR_STANDING)

        if (isLongformStatic) {
            // 롱폼과 미동 없는 고정 자세가 중첩되면 콤보 미션(눈+몸 복합 이완)을 1순위로 즉시 선정
            val availableCombos = COMBO_RECOMMENDATIONS.filterNot { MissionCooldownTracker.isCooldown(it.title) }
            val pool = if (availableCombos.isNotEmpty()) availableCombos else COMBO_RECOMMENDATIONS
            val chosen = pool.random()
            MissionCooldownTracker.record(chosen.title)
            return chosen
        }

        val candidatePool = mutableListOf<RestRecommendation>()

        // 2단계: 앱 카테고리별 특화 미션 가중치 반영
        if (ctx.isGame || ctx.appCategory == InferredAppCategory.GAME) {
            candidatePool.addAll(GAME_RECOMMENDATIONS)
            candidatePool.addAll(GAME_RECOMMENDATIONS)
            candidatePool.addAll(GAME_RECOMMENDATIONS) // 게임 손목/엄지 특화 미션 우선 가중치 (3x)
        } else if (ctx.isShortform) {
            candidatePool.addAll(SHORTFORM_RECOMMENDATIONS)
            candidatePool.addAll(SHORTFORM_RECOMMENDATIONS)
            candidatePool.addAll(SHORTFORM_RECOMMENDATIONS) // 숏폼 눈/도파민 특화 미션 우선 가중치 (3x)
        }

        // 3단계: 시간대 및 센서(암실/조도) 가중치 반영
        if (ctx.isDarkOrBedtime || ctx.currentHour >= 21 || ctx.currentHour < 5) {
            // 야간 / 암실 / 취침 임박: 이완 및 눈 피로 미션 가중치 부여 (2x 가중치)
            val nightMissions = NIGHT_RECOMMENDATIONS + GENERAL_RECOMMENDATIONS.filter { it.targetBodyPart == "눈" || it.targetBodyPart == "두뇌쿨다운" }
            candidatePool.addAll(nightMissions)
            candidatePool.addAll(nightMissions)
        } else if (ctx.currentHour in 7..18) {
            // 주간 활동 시간: 활력, 기지개, 척추, 수분 섭취 가중치 부여 (2x 가중치)
            candidatePool.addAll(DAY_RECOMMENDATIONS)
            candidatePool.addAll(DAY_RECOMMENDATIONS)
        }

        // 4단계: 물리적 자세(UserPosture) 기본 풀 추가
        when (posture) {
            UserPosture.LYING_DOWN -> candidatePool.addAll(NECK_RECOMMENDATIONS + WAIST_RECOMMENDATIONS)
            UserPosture.SITTING_OR_STANDING -> candidatePool.addAll(SHOULDER_RECOMMENDATIONS + WAIST_RECOMMENDATIONS)
            UserPosture.TABLE_OR_DESK -> candidatePool.addAll(TABLE_RECOMMENDATIONS)
            else -> candidatePool.addAll(NECK_RECOMMENDATIONS + SHOULDER_RECOMMENDATIONS + WAIST_RECOMMENDATIONS)
        }

        if (candidatePool.isEmpty()) {
            candidatePool.addAll(ALL_RECOMMENDATIONS.filterNot { it.isFacedownRequired })
        }

        // 5단계: [쿨다운 시스템 적용] 최근 N회(기본 3회) 이내에 추천/수행된 미션 제외
        val filteredPool = candidatePool.filterNot { MissionCooldownTracker.isCooldown(it.title) }
        val finalPool = if (filteredPool.isNotEmpty()) filteredPool else candidatePool

        val chosen = finalPool.random()
        MissionCooldownTracker.record(chosen.title)
        return chosen
    }
}
