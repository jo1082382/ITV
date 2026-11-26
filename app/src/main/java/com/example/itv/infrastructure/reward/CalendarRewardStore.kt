package com.example.itv.infrastructure.reward

import android.content.Context
import android.content.SharedPreferences
import com.example.itv.domain.records.DailyHealthAssessment
import com.example.itv.domain.records.DailyHealthTier
import com.example.itv.domain.reward.CalendarStreakPolicy
import com.example.itv.domain.reward.CalendarStreakSummary
import com.example.itv.domain.reward.MonthlyChestTier
import com.example.itv.domain.reward.RewardGrantResult
import com.example.itv.domain.reward.StreakProtectionSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth

internal data class CalendarRewardSnapshot(
    val streakShieldCount: Int = 1,
    val bestStreak: Int = 0,
    val protectedDates: Set<LocalDate> = emptySet(),
    val claimedChestKeys: Set<String> = emptySet(),
    val recentDayTiers: Map<LocalDate, DailyHealthTier> = emptyMap(),
    val lastAutoProtectedDate: LocalDate? = null,
    val lastAutoProtectionSource: StreakProtectionSource? = null
) {
    fun claimedTiersFor(yearMonth: YearMonth): Set<MonthlyChestTier> {
        val prefix = "$yearMonth:"
        return MonthlyChestTier.entries.filterTo(mutableSetOf()) { tier ->
            (prefix + tier.name) in claimedChestKeys
        }
    }
}

internal data class MonthlyChestClaimResult(
    val tier: MonthlyChestTier,
    val rewardResult: RewardGrantResult,
    val shieldsGained: Int,
    val overflowShieldBonusPoints: Long
)

/**
 * 캘린더 월간 보물상자 수령 상태, 건강 스탬프 스트릭, 자동 스트릭 보호(보호권 -> 부족 시 포인트 자동 사용)를 저장합니다.
 */
internal object CalendarRewardStore {
    internal const val PREF_NAME = "itv_calendar_reward_store_v1"
    private const val KEY_INITIALIZED = "key_initialized"
    private const val KEY_STREAK_SHIELDS = "key_streak_shields"
    private const val KEY_BEST_STREAK = "key_best_streak"
    private const val KEY_PROTECTED_DATES = "key_protected_dates"
    private const val KEY_CLAIMED_CHESTS = "key_claimed_chests"
    private const val KEY_RECENT_DAY_TIERS = "key_recent_day_tiers"
    private const val KEY_LAST_AUTO_SHIELD_DATE = "key_last_auto_shield_date"
    private const val KEY_LAST_AUTO_PROTECTION_SOURCE = "key_last_auto_protection_source"
    private const val DEFAULT_STARTER_SHIELDS = 1
    private const val RECENT_TIER_RETENTION_DAYS = 95L

    private val _state = MutableStateFlow<CalendarRewardSnapshot?>(null)
    val state: StateFlow<CalendarRewardSnapshot?> = _state.asStateFlow()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun getSnapshot(context: Context): CalendarRewardSnapshot {
        val p = prefs(context)
        if (!p.getBoolean(KEY_INITIALIZED, false)) {
            p.edit()
                .putBoolean(KEY_INITIALIZED, true)
                .putInt(KEY_STREAK_SHIELDS, DEFAULT_STARTER_SHIELDS)
                .apply()
        }
        val snapshot = readSnapshot(p)
        if (_state.value != snapshot) {
            _state.value = snapshot
        }
        return snapshot
    }

    /**
     * 캘린더 월간 평가 결과를 동기화하여:
     * 1. 월 경계에서도 스트릭이 끊기지 않도록 확정된 일별 스탬프 등급을 캐시합니다.
     * 2. 스트릭이 끊길 날이 되면 보유 보호권을 우선 자동 사용하고, 보호권이 없다면 포인트를 자동 차감해 스트릭을 지켜줍니다.
     * 3. 최고 연속 스트릭을 갱신합니다.
     */
    @Synchronized
    fun syncWithAssessments(
        context: Context,
        assessments: Map<LocalDate, DailyHealthAssessment>,
        today: LocalDate
    ): Pair<CalendarRewardSnapshot, CalendarStreakSummary> {
        val p = prefs(context)
        var snapshot = getSnapshot(context)

        val mergedTiers = snapshot.recentDayTiers.toMutableMap()
        val cutoff = today.minusDays(RECENT_TIER_RETENTION_DAYS)
        mergedTiers.keys.removeAll { it.isBefore(cutoff) }

        var tiersChanged = false
        for ((date, assessment) in assessments) {
            if (date.isBefore(cutoff)) continue
            if (assessment.tier != DailyHealthTier.NO_DATA && assessment.tier != DailyHealthTier.IN_PROGRESS) {
                if (mergedTiers[date] != assessment.tier) {
                    mergedTiers[date] = assessment.tier
                    tiersChanged = true
                }
            }
        }

        val todayAssessment = assessments[today]
        val todayProjectedTier = todayAssessment?.projectedTier
        val evalMap = mergedTiers.toMutableMap()
        assessments.forEach { (date, assessment) ->
            if (assessment.tier == DailyHealthTier.IN_PROGRESS) {
                evalMap[date] = DailyHealthTier.IN_PROGRESS
            }
        }

        var protectedDates = snapshot.protectedDates
        var shields = snapshot.streakShieldCount
        var lastAutoProtected = snapshot.lastAutoProtectedDate
        var lastAutoSource = snapshot.lastAutoProtectionSource
        val availablePoints = UserRewardStore.getReward(context).currentPoints

        var summary = CalendarStreakPolicy.evaluateStreak(
            dayTiers = evalMap,
            today = today,
            protectedDates = protectedDates,
            availableShields = shields,
            availablePoints = availablePoints,
            persistedBestStreak = snapshot.bestStreak,
            todayProjectedTier = todayProjectedTier
        )

        val autoTarget = summary.autoShieldCandidateDate
        var protectionApplied = false
        if (autoTarget != null && autoTarget !in protectedDates) {
            if (shields > 0) {
                shields = (shields - 1).coerceAtLeast(0)
                protectedDates = protectedDates + autoTarget
                lastAutoProtected = autoTarget
                lastAutoSource = StreakProtectionSource.SHIELD
                protectionApplied = true
            } else if (UserRewardStore.spendPoints(context, CalendarStreakPolicy.SHIELD_COST_POINTS)) {
                protectedDates = protectedDates + autoTarget
                lastAutoProtected = autoTarget
                lastAutoSource = StreakProtectionSource.POINTS
                protectionApplied = true
            }
            if (protectionApplied) {
                summary = CalendarStreakPolicy.evaluateStreak(
                    dayTiers = evalMap,
                    today = today,
                    protectedDates = protectedDates,
                    availableShields = shields,
                    availablePoints = UserRewardStore.getReward(context).currentPoints,
                    persistedBestStreak = snapshot.bestStreak,
                    todayProjectedTier = todayProjectedTier
                )
            }
        }

        val nextBest = maxOf(snapshot.bestStreak, summary.bestStreak)
        if (tiersChanged || protectionApplied || nextBest != snapshot.bestStreak) {
            val editor = p.edit()
            if (tiersChanged) {
                editor.putStringSet(
                    KEY_RECENT_DAY_TIERS,
                    mergedTiers.entries.map { "${it.key}=${it.value.name}" }.toSet()
                )
            }
            if (protectionApplied) {
                editor.putInt(KEY_STREAK_SHIELDS, shields)
                editor.putStringSet(KEY_PROTECTED_DATES, protectedDates.map { it.toString() }.toSet())
                editor.putString(KEY_LAST_AUTO_SHIELD_DATE, lastAutoProtected?.toString())
                editor.putString(KEY_LAST_AUTO_PROTECTION_SOURCE, lastAutoSource?.name)
            }
            if (nextBest != snapshot.bestStreak) {
                editor.putInt(KEY_BEST_STREAK, nextBest)
            }
            editor.apply()

            snapshot = snapshot.copy(
                streakShieldCount = shields,
                bestStreak = nextBest,
                protectedDates = protectedDates,
                recentDayTiers = mergedTiers,
                lastAutoProtectedDate = lastAutoProtected,
                lastAutoProtectionSource = lastAutoSource
            )
            _state.value = snapshot
        }

        return snapshot to summary
    }

    @Synchronized
    fun claimMonthlyChest(
        context: Context,
        yearMonth: YearMonth,
        tier: MonthlyChestTier,
        happyCount: Int
    ): MonthlyChestClaimResult? {
        if (happyCount < tier.requiredHappyStamps) return null
        val p = prefs(context)
        val snapshot = getSnapshot(context)
        val claimKey = chestKey(yearMonth, tier)
        if (claimKey in snapshot.claimedChestKeys) return null

        val currentShields = snapshot.streakShieldCount
        val roomForShields = (CalendarStreakPolicy.MAX_STREAK_SHIELDS - currentShields).coerceAtLeast(0)
        val shieldsGained = tier.bonusShields.coerceAtMost(roomForShields)
        val overflowShields = (tier.bonusShields - shieldsGained).coerceAtLeast(0)
        val overflowBonusPoints = overflowShields * CalendarStreakPolicy.OVERFLOW_SHIELD_BONUS_POINTS

        val totalPointsToGrant = tier.rewardPoints + overflowBonusPoints
        val rewardResult = UserRewardStore.grantBonusReward(
            context = context,
            expGain = tier.rewardExp,
            pointGain = totalPointsToGrant
        )

        val nextClaimed = snapshot.claimedChestKeys + claimKey
        val nextShields = (currentShields + shieldsGained).coerceAtMost(CalendarStreakPolicy.MAX_STREAK_SHIELDS)

        p.edit()
            .putStringSet(KEY_CLAIMED_CHESTS, nextClaimed)
            .putInt(KEY_STREAK_SHIELDS, nextShields)
            .apply()

        _state.value = snapshot.copy(
            claimedChestKeys = nextClaimed,
            streakShieldCount = nextShields
        )

        return MonthlyChestClaimResult(
            tier = tier,
            rewardResult = rewardResult,
            shieldsGained = shieldsGained,
            overflowShieldBonusPoints = overflowBonusPoints
        )
    }

    @Synchronized
    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
        _state.value = null
    }

    /**
     * Restores streak shields, best streak, auto-protected dates, claimed monthly chests, and recent
     * day tiers from Cloud Firestore.
     * - [mergeWithLocal] = true (Guest -> Social linking): merges cloud & local without losing progress.
     * - [mergeWithLocal] = false (Fresh install / new device): replaces local state with cloud state.
     */
    @Synchronized
    fun restoreFromCloud(
        context: Context,
        remote: CalendarRewardSnapshot,
        mergeWithLocal: Boolean
    ) {
        val p = prefs(context)
        val local = if (mergeWithLocal) getSnapshot(context) else CalendarRewardSnapshot(streakShieldCount = 0)
        val merged = mergeSnapshots(local = local, remote = remote, mergeWithLocal = mergeWithLocal)

        val editor = p.edit()
            .putBoolean(KEY_INITIALIZED, true)
            .putInt(KEY_STREAK_SHIELDS, merged.streakShieldCount)
            .putInt(KEY_BEST_STREAK, merged.bestStreak)
            .putStringSet(KEY_PROTECTED_DATES, merged.protectedDates.map { it.toString() }.toSet())
            .putStringSet(KEY_CLAIMED_CHESTS, merged.claimedChestKeys)
            .putStringSet(
                KEY_RECENT_DAY_TIERS,
                merged.recentDayTiers.entries.map { "${it.key}=${it.value.name}" }.toSet()
            )
        if (merged.lastAutoProtectedDate != null) {
            editor.putString(KEY_LAST_AUTO_SHIELD_DATE, merged.lastAutoProtectedDate.toString())
        } else {
            editor.remove(KEY_LAST_AUTO_SHIELD_DATE)
        }
        if (merged.lastAutoProtectionSource != null) {
            editor.putString(KEY_LAST_AUTO_PROTECTION_SOURCE, merged.lastAutoProtectionSource.name)
        } else {
            editor.remove(KEY_LAST_AUTO_PROTECTION_SOURCE)
        }
        editor.apply()
        _state.value = merged
    }

    internal fun mergeSnapshots(
        local: CalendarRewardSnapshot,
        remote: CalendarRewardSnapshot,
        mergeWithLocal: Boolean
    ): CalendarRewardSnapshot {
        val maxShields = CalendarStreakPolicy.MAX_STREAK_SHIELDS
        if (!mergeWithLocal) {
            return remote.copy(
                streakShieldCount = remote.streakShieldCount.coerceIn(0, maxShields),
                bestStreak = remote.bestStreak.coerceAtLeast(0)
            )
        }
        val mergedProtected = local.protectedDates + remote.protectedDates
        val mergedClaimed = local.claimedChestKeys + remote.claimedChestKeys
        val mergedTiers = buildMap {
            putAll(remote.recentDayTiers)
            putAll(local.recentDayTiers)
        }
        val (latestDate, latestSource) = when {
            local.lastAutoProtectedDate == null -> remote.lastAutoProtectedDate to remote.lastAutoProtectionSource
            remote.lastAutoProtectedDate == null -> local.lastAutoProtectedDate to local.lastAutoProtectionSource
            remote.lastAutoProtectedDate.isAfter(local.lastAutoProtectedDate) ->
                remote.lastAutoProtectedDate to remote.lastAutoProtectionSource
            else -> local.lastAutoProtectedDate to local.lastAutoProtectionSource
        }
        return CalendarRewardSnapshot(
            streakShieldCount = maxOf(local.streakShieldCount, remote.streakShieldCount).coerceIn(0, maxShields),
            bestStreak = maxOf(local.bestStreak, remote.bestStreak).coerceAtLeast(0),
            protectedDates = mergedProtected,
            claimedChestKeys = mergedClaimed,
            recentDayTiers = mergedTiers,
            lastAutoProtectedDate = latestDate,
            lastAutoProtectionSource = latestSource
        )
    }

    internal fun serializeToJsonObject(
        snapshot: CalendarRewardSnapshot,
        cutoffDateStr: String,
        cutoffMonthStr: String,
        includeRecentTiers: Boolean = true
    ): JSONObject {
        val obj = JSONObject()
        obj.put("shields", snapshot.streakShieldCount.coerceIn(0, CalendarStreakPolicy.MAX_STREAK_SHIELDS))
        obj.put("bestStreak", snapshot.bestStreak.coerceAtLeast(0))

        val protectedArr = JSONArray()
        snapshot.protectedDates
            .map { it.toString() }
            .filter { it >= cutoffDateStr }
            .sorted()
            .takeLast(60)
            .forEach { protectedArr.put(it) }
        obj.put("protectedDates", protectedArr)

        val claimedArr = JSONArray()
        snapshot.claimedChestKeys
            .filter { key ->
                val ym = key.substringBefore(':', "")
                ym.length == 7 && ym >= cutoffMonthStr
            }
            .sorted()
            .takeLast(30)
            .forEach { claimedArr.put(it) }
        obj.put("claimedChests", claimedArr)

        if (includeRecentTiers && snapshot.recentDayTiers.isNotEmpty()) {
            val tiersObj = JSONObject()
            snapshot.recentDayTiers.entries
                .filter { it.key.toString() >= cutoffDateStr }
                .sortedBy { it.key }
                .takeLast(100)
                .forEach { (d, tier) -> tiersObj.put(d.toString(), tier.name) }
            obj.put("recentDayTiers", tiersObj)
        }

        snapshot.lastAutoProtectedDate
            ?.takeIf { it.toString() >= cutoffDateStr }
            ?.let { obj.put("lastAutoProtectedDate", it.toString()) }
        snapshot.lastAutoProtectionSource?.let { obj.put("lastAutoProtectionSource", it.name) }
        return obj
    }

    internal fun parseFromJsonObject(
        obj: JSONObject,
        cutoffDateStr: String,
        cutoffMonthStr: String
    ): CalendarRewardSnapshot {
        val shields = obj.optInt("shields", DEFAULT_STARTER_SHIELDS)
            .coerceIn(0, CalendarStreakPolicy.MAX_STREAK_SHIELDS)
        val best = obj.optInt("bestStreak", 0).coerceAtLeast(0)

        val protected = mutableSetOf<LocalDate>()
        obj.optJSONArray("protectedDates")?.let { arr ->
            for (i in 0 until arr.length()) {
                val raw = arr.optString(i)
                if (raw.isNotBlank() && raw >= cutoffDateStr) {
                    runCatching { LocalDate.parse(raw) }.getOrNull()?.let { protected.add(it) }
                }
            }
        }

        val claimed = mutableSetOf<String>()
        obj.optJSONArray("claimedChests")?.let { arr ->
            for (i in 0 until arr.length()) {
                val key = arr.optString(i)
                val ym = key.substringBefore(':', "")
                val tierName = key.substringAfter(':', "")
                if (ym.length == 7 && ym >= cutoffMonthStr &&
                    MonthlyChestTier.entries.any { it.name == tierName }
                ) {
                    claimed.add(key)
                }
            }
        }

        val recentTiers = mutableMapOf<LocalDate, DailyHealthTier>()
        obj.optJSONObject("recentDayTiers")?.let { tiersObj ->
            tiersObj.keys().forEach { dateStr ->
                if (dateStr >= cutoffDateStr) {
                    val d = runCatching { LocalDate.parse(dateStr) }.getOrNull()
                    val t = runCatching { DailyHealthTier.valueOf(tiersObj.optString(dateStr)) }.getOrNull()
                    if (d != null && t != null) recentTiers[d] = t
                }
            }
        }

        val lastAutoDate = obj.optString("lastAutoProtectedDate")
            .takeIf { it.isNotBlank() && it >= cutoffDateStr }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val lastAutoSource = obj.optString("lastAutoProtectionSource")
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { StreakProtectionSource.valueOf(it) }.getOrNull() }

        return CalendarRewardSnapshot(
            streakShieldCount = shields,
            bestStreak = best,
            protectedDates = protected,
            claimedChestKeys = claimed,
            recentDayTiers = recentTiers,
            lastAutoProtectedDate = lastAutoDate,
            lastAutoProtectionSource = lastAutoSource
        )
    }

    private fun chestKey(yearMonth: YearMonth, tier: MonthlyChestTier): String = "$yearMonth:${tier.name}"

    private fun readSnapshot(p: SharedPreferences): CalendarRewardSnapshot {
        val shields = p.getInt(KEY_STREAK_SHIELDS, DEFAULT_STARTER_SHIELDS)
            .coerceIn(0, CalendarStreakPolicy.MAX_STREAK_SHIELDS)
        val best = p.getInt(KEY_BEST_STREAK, 0).coerceAtLeast(0)
        val protected = p.getStringSet(KEY_PROTECTED_DATES, emptySet())
            ?.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.toSet()
            ?: emptySet()
        val claimed = p.getStringSet(KEY_CLAIMED_CHESTS, emptySet())?.toSet() ?: emptySet()
        val recentTiers = buildMap {
            p.getStringSet(KEY_RECENT_DAY_TIERS, emptySet())?.forEach { entry ->
                val parts = entry.split('=')
                if (parts.size == 2) {
                    val date = runCatching { LocalDate.parse(parts[0]) }.getOrNull()
                    val tier = runCatching { DailyHealthTier.valueOf(parts[1]) }.getOrNull()
                    if (date != null && tier != null) put(date, tier)
                }
            }
        }
        val lastAuto = p.getString(KEY_LAST_AUTO_SHIELD_DATE, null)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val lastAutoSource = p.getString(KEY_LAST_AUTO_PROTECTION_SOURCE, null)
            ?.let { runCatching { StreakProtectionSource.valueOf(it) }.getOrNull() }

        return CalendarRewardSnapshot(
            streakShieldCount = shields,
            bestStreak = best,
            protectedDates = protected,
            claimedChestKeys = claimed,
            recentDayTiers = recentTiers,
            lastAutoProtectedDate = lastAuto,
            lastAutoProtectionSource = lastAutoSource
        )
    }
}
