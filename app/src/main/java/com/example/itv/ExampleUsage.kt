package com.example.itv

import android.content.Context
import com.example.itv.composition.goalService
import com.example.itv.data.local.ItvDatabase

// Invoke from viewModelScope.launch, lifecycleScope.launch or another coroutine.
// Guest sample intentionally avoids pretending server restore has already completed.
suspend fun createDemoGoals(context: Context) {
    val db = ItvDatabase.get(context)
    val repository = goalService(db)
    val guestId = "demo-guest" // Demo only: production must persist a generated guest UUID.
    repository.createLocalOwner(guestId)
    repository.saveSettings(guestId, coachingEnabled = false)
    repository.saveDailyUsageGoal(guestId, seconds = 3 * 60 * 60L)
    repository.saveBedtimeGoal(guestId, hour = 23, minute = 30)
    repository.saveUsageBreakGoal(guestId, usageSeconds = 30 * 60L, breakSeconds = 5 * 60L)
}
