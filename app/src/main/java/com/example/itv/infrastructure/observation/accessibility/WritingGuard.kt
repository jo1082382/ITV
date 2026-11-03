package com.example.itv.infrastructure.observation.accessibility

import android.os.SystemClock

internal object WritingGuard {
    @Volatile var connected = false
    @Volatile var checkedAt = 0L
    @Volatile var blocked = true
    @Volatile var reason = "접근성 작성 상태 확인이 필요합니다"
    fun blockReason(): String? {
        if (!connected || SystemClock.elapsedRealtime() - checkedAt > 3000L)
            return "작성 상태 확인 불가 · 개입 보류"
        return if (blocked) reason else null
    }
}

