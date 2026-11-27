package com.example.itv.domain.support

/**
 * Curated, ITV-specific FAQ entries and pure search/filter logic.
 */
object SupportFaqCatalog {

    val items: List<FaqItem> = listOf(
        // 권한·접근성
        FaqItem(
            id = "faq_perm_accessibility_why",
            category = FaqCategory.PERMISSIONS,
            question = "접근성 권한은 왜 필요한가요? 꼭 켜야 하나요?",
            answer = "접근성 권한은 유튜브 쇼츠·인스타그램 릴스 화면 전환을 정확히 인식하고, " +
                "메시지나 댓글을 입력하는 중에는 방해하지 않도록 개입을 보류하기 위해 사용됩니다.\n\n" +
                "선택 권한이므로 끄고도 홈·수면 기록·목표 설정은 모두 사용할 수 있지만, " +
                "숏폼 시청 중 실시간 캐릭터 코칭을 받으려면 허용이 필요합니다.",
            keywords = listOf("접근성", "권한", "필수", "릴스", "쇼츠", "개입"),
        ),
        FaqItem(
            id = "faq_perm_privacy",
            category = FaqCategory.PERMISSIONS,
            question = "접근성 권한을 켜면 내 메시지나 화면 내용을 읽나요?",
            answer = "아니요. ITV는 메시지 본문, 입력 중인 문자, 비밀번호, 화면 이미지를 일체 읽거나 저장·전송하지 않습니다.\n\n" +
                "기기 내부에서만 현재 앱 화면 구조와 입력창 포커스 여부만 순간적으로 확인하여 " +
                "'작성 중 방해 금지'와 숏폼 구간 판별에만 사용합니다.",
            keywords = listOf("개인정보", "보안", "메시지", "채팅", "읽기", "저장", "접근성"),
        ),
        FaqItem(
            id = "faq_perm_lag",
            category = FaqCategory.PERMISSIONS,
            question = "접근성을 켠 뒤 앱이 끊기거나 배터리가 많이 닳나요?",
            answer = "ITV 앱을 직접 보고 있는 동안에는 접근성 이벤트 처리를 자동으로 휴면 모드로 전환해 " +
                "화면 스크롤이나 애니메이션에 부담을 주지 않도록 설계되어 있습니다.\n\n" +
                "그래도 특정 기기에서 버벅임이 느껴지신다면 1:1 문의에서 '기기 진단 정보 첨부'를 체크해 제보해 주세요.",
            keywords = listOf("렉", "끊김", "버벅임", "느림", "배터리", "접근성", "성능"),
        ),
        FaqItem(
            id = "faq_perm_overlay",
            category = FaqCategory.PERMISSIONS,
            question = "'다른 앱 위에 표시' 권한을 끄면 어떻게 되나요?",
            answer = "오버레이 권한이 꺼져 있으면 숏폼 위에 강아지 캐릭터 말풍선을 띄울 수 없어 " +
                "자동으로 '알림으로만 코칭 받기' 방식으로 안전하게 전환됩니다.\n\n" +
                "캐릭터 개입을 원하시면 설정 › 권한에서 '다른 앱 위에 표시'를 허용해 주세요.",
            keywords = listOf("오버레이", "다른 앱 위에 표시", "알림", "캐릭터", "말풍선"),
        ),
        FaqItem(
            id = "faq_perm_usage_reset",
            category = FaqCategory.PERMISSIONS,
            question = "권한을 켰는데 자꾸 꺼지거나 미허용으로 보여요.",
            answer = "일부 안드로이드 제조사(삼성·샤오미 등)의 배터리 절전 기능이 백그라운드 접근성 서비스를 " +
                "자동으로 중지시키는 경우가 있습니다.\n\n" +
                "기기 설정 › 애플리케이션 › ITV › 배터리에서 '제한 없음'으로 설정하면 훨씬 안정적으로 유지됩니다.",
            keywords = listOf("배터리", "절전", "꺼짐", "미허용", "자동 중지", "삼성", "갤럭시"),
        ),

        // 코칭·개입
        FaqItem(
            id = "faq_coaching_frequency",
            category = FaqCategory.COACHING,
            question = "코칭 개입이 너무 자주 뜨거나, 반대로 잘 안 떠요.",
            answer = "설정 › 코칭 설정에서 개입 방식(캐릭터 / 알림만), 개입 강도, 캐릭터 말투를 원하는 대로 조절할 수 있습니다.\n\n" +
                "개입이 전혀 뜨지 않는다면 설정 › 권한에서 '사용정보 수집 동의', '사용 정보 접근', " +
                "'접근성 서비스'가 모두 허용되어 있는지 먼저 확인해 주세요.",
            keywords = listOf("코칭", "개입", "빈도", "안 뜸", "자주", "강도", "말투"),
        ),
        FaqItem(
            id = "faq_coaching_dark_room",
            category = FaqCategory.COACHING,
            question = "밝은 곳에서도 어두운 환경(암실)으로 판정될 수 있나요?",
            answer = "ITV는 단순히 조도 센서 값 하나만 보지 않고, 화면 밝기와 주변 조도 변화를 함께 확인해 " +
                "손이나 그림자로 잠시 가려진 상황은 암실로 오판하지 않도록 보정하고 있습니다.\n\n" +
                "취침 시간이 가까운 밤에 실제 어두운 환경에서 시청할 때만 눈 건강 보호를 위해 부드럽게 안내합니다.",
            keywords = listOf("암실", "조도", "밝기", "그림자", "어두운", "사전 개입"),
        ),
        FaqItem(
            id = "faq_coaching_typing",
            category = FaqCategory.COACHING,
            question = "카톡이나 댓글을 입력하는 중에 캐릭터가 뜨면 어떡하죠?",
            answer = "키보드가 올라와 있거나 입력창에 포커스가 있는 동안에는 ITV가 자동으로 개입을 보류합니다.\n\n" +
                "중요한 대화를 나누거나 글을 쓰는 흐름을 방해하지 않는 것이 가장 우선입니다.",
            keywords = listOf("키보드", "입력", "댓글", "채팅", "카톡", "방해"),
        ),

        // 수면·캘린더
        FaqItem(
            id = "faq_sleep_tracking",
            category = FaqCategory.SLEEP_CALENDAR,
            question = "취침 시간과 수면 기록은 어떻게 측정되나요?",
            answer = "하단 '취침' 탭에서 평소 목표 취침·기상 시간을 설정할 수 있으며, " +
                "밤 시간대 화면 사용 패턴과 직접 입력한 기록을 바탕으로 하루 건강 리포트를 정리해 드립니다.",
            keywords = listOf("취침", "수면", "기상", "리포트", "기록"),
        ),
        FaqItem(
            id = "faq_calendar_report",
            category = FaqCategory.SLEEP_CALENDAR,
            question = "캘린더의 하루 건강 리포트와 도장은 언제 반영되나요?",
            answer = "오늘의 사용량과 수면 진행 상황은 실시간으로 캘린더에 표시되며, " +
                "어제 하루를 마무리한 도장은 다음 날 아침 앱을 열 때 자동으로 찍어 드립니다.\n\n" +
                "캘린더에서 날짜를 누르면 해당 날의 상세 건강 리포트를 언제든 다시 볼 수 있습니다.",
            keywords = listOf("캘린더", "건강 리포트", "도장", "아침", "날짜"),
        ),

        // 도장·우편함
        FaqItem(
            id = "faq_rewards_chest",
            category = FaqCategory.REWARDS,
            question = "보물상자는 어떻게 얻고 열 수 있나요?",
            answer = "하루 목표를 지켜 도장을 모으면 누적 달성 기준에 따라 동·은·금 보물상자가 열립니다.\n\n" +
                "하단 '캐릭터' 탭에서 모은 도장과 보물상자를 확인하고, 강아지가 물어다 주는 열쇠로 상자를 열어보세요!",
            keywords = listOf("보물상자", "열쇠", "도장", "캐릭터", "보상", "동", "은", "금"),
        ),
        FaqItem(
            id = "faq_rewards_mailbox",
            category = FaqCategory.REWARDS,
            question = "우편함의 주간 편지는 언제 도착하나요?",
            answer = "지난 일주일(월~일) 동안의 건강한 습관 기록을 모아 매주 월요일 아침 우편함으로 주간 코치 편지가 배달됩니다.\n\n" +
                "우편함의 '공지' 탭에서는 업데이트 소식과 이벤트 안내도 함께 받아보실 수 있습니다.",
            keywords = listOf("우편함", "편지", "주간", "월요일", "공지"),
        ),

        // 계정·백업
        FaqItem(
            id = "faq_account_backup",
            category = FaqCategory.ACCOUNT,
            question = "기기를 바꾸면 도장이나 기록을 옮길 수 있나요?",
            answer = "설정 › 계정 관리에서 소셜 계정을 연동해 두시면 로그인 정보와 연동 상태를 안전하게 관리할 수 있습니다.\n\n" +
                "단, 안드로이드 권한(접근성·오버레이·사용정보)과 수집 동의 여부는 보안상 새 기기에서 직접 다시 허용해 주셔야 합니다.",
            keywords = listOf("기기 변경", "백업", "복구", "계정", "로그인", "연동"),
        ),
        FaqItem(
            id = "faq_account_delete",
            category = FaqCategory.ACCOUNT,
            question = "계정 삭제나 데이터 초기화는 어디에서 하나요?",
            answer = "설정 › 계정 관리 메뉴에서 계정 삭제를 진행하실 수 있습니다.\n\n" +
                "삭제된 계정 정보는 복구하기 어려우므로 진행 전 안내 사항을 꼭 확인해 주세요.",
            keywords = listOf("탈퇴", "계정 삭제", "초기화", "데이터"),
        ),
    )

    fun filter(
        category: FaqCategory,
        query: String,
        source: List<FaqItem> = items,
    ): List<FaqItem> {
        val normalizedQuery = query.trim().lowercase()
        return source.filter { item ->
            val categoryMatch = category == FaqCategory.ALL || item.category == category
            if (!categoryMatch) return@filter false
            if (normalizedQuery.isEmpty()) return@filter true
            item.question.lowercase().contains(normalizedQuery) ||
                item.answer.lowercase().contains(normalizedQuery) ||
                item.keywords.any { it.lowercase().contains(normalizedQuery) }
        }
    }
}
