package com.example.itv.infrastructure.observation.accessibility

import android.os.SystemClock

internal object WritingGuard {
    @Volatile var connected = false
    @Volatile var checkedAt = 0L
    @Volatile var blocked = true
    @Volatile var reason = "접근성 작성 상태 확인이 필요합니다"
    @Volatile var inAppForeground = false

    fun blockReason(): String? {
        if (inAppForeground) return "ITV 내부 화면 · 구조 탐색 생략 / 개입 보류"
        if (!connected || SystemClock.elapsedRealtime() - checkedAt > 3000L)
            return "작성 상태 확인 불가 · 개입 보류"
        return if (blocked) reason else null
    }
}

