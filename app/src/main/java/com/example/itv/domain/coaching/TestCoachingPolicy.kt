package com.example.itv.domain.coaching

/** Existing test settings, NOT finalized SDS production rules. */
internal object TestCoachingPolicy {
    const val INTERVENTION_MS = 60_000L
    const val REMINDER_MS = 300_000L
    const val RESPONSE_MS = 45_000L
    const val MISSION_START_MS = 15_000L
    const val REST_MS = 30_000L
    const val SAFE_MS = 5_000L
}
internal enum class TestMissionWake { NONE, INCOMPLETE, SUCCESS }
internal class TestRestMission {
    var id: String? = null
    var startDeadline = 0L
    var started = false
    var offAt: Long? = null
    var pending = false
    fun begin(value: String, now: Long) {
        id = value; offAt = null; pending = false; started = false
        startDeadline = now + TestCoachingPolicy.MISSION_START_MS
    }
    fun isLate(now: Long) = id != null && !started && !pending && now > startDeadline
    fun failStart() { id = null; offAt = null; startDeadline = 0L }
    fun screenOff(now: Long): Boolean {
        if (id == null || pending || offAt != null) return false
        val first = !started
        started = true; offAt = now
        return first
    }
    fun screenOn(now: Long): Pair<TestMissionWake, Long> {
        val start = offAt ?: return TestMissionWake.NONE to 0L
        offAt = null
        if (id == null) return TestMissionWake.NONE to 0L
        val duration = now - start
        return if (duration >= TestCoachingPolicy.REST_MS) {
            pending = true; TestMissionWake.SUCCESS to duration
        } else TestMissionWake.INCOMPLETE to duration
    }
    fun displayed() { pending = false; id = null }
    fun clear() { id = null; pending = false; offAt = null }
}
