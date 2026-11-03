package com.example.itv.domain.records

import java.time.*

data class SleepConfirmation(val date:String, val zoneId:String, val choice:String,
    val startMs:Long?, val endMs:Long?, val origin:String, val updatedAt:Long)
interface SleepConfirmationStore {
    suspend fun read(date:String):SleepConfirmation?
    suspend fun save(value:SleepConfirmation)
    suspend fun reset(date:String)
}
object SleepConfirmationPolicy {
    fun validate(v:SleepConfirmation, now:Long) {
        LocalDate.parse(v.date);val zone=ZoneId.of(v.zoneId)
        require(v.choice=="SLEEP" || v.choice=="NOT_SLEEP")
        if(v.choice=="NOT_SLEEP") { require(v.startMs==null && v.endMs==null);return }
        val start=requireNotNull(v.startMs);val end=requireNotNull(v.endMs)
        require(start<end) { "종료는 시작보다 늦어야 합니다." }
        require(end<=now) { "미래의 종료 시각은 저장할 수 없습니다." }
        require(Instant.ofEpochMilli(end).atZone(zone).toLocalDate().toString()==v.date) {
            "종료 날짜가 기록 날짜와 같아야 합니다. 종료일로 기록 날짜를 바꿔 주세요."
        }
    }
}
