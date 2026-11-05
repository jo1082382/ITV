package com.example.itv.presentation.jev

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.itv.domain.jev.*
import com.example.itv.infrastructure.jev.*
import com.example.itv.presentation.coaching.CoachingAutoStart
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class JevTestViewModel(app: Application): AndroidViewModel(app) {
    val repository=JevRepository.get(app)
    val store=repository.store
    val runtime=JevRuntime.get(app)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    val busy=MutableStateFlow(false)
    val message=MutableStateFlow<String?>(null)
    val manualAnswer=MutableStateFlow<JevAnswer?>(null)
    private fun action(block: suspend ()->Unit) {
        if(busy.value)return
        busy.value=true;message.value=null
        scope.launch {
            try { block() }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { message.value=e.message ?: "처리 실패" }
            finally { busy.value=false }
        }
    }
    fun save(settings: JevSettings) = action {
        store.save(settings)
        CoachingAutoStart.setEnabled(getApplication(),settings.enabled && settings.collectionConsent)
        message.value="설정 저장 완료 · 새 판단부터 적용됩니다."
    }
    fun saveToken(value: String,onSaved: ()->Unit) = action { store.saveToken(value);onSaved();message.value="토큰 저장 완료" }
    fun deleteToken() = action { store.deleteToken();message.value="토큰 삭제 완료" }
    fun checkConnection() = action { message.value=repository.connectionStatus() }
    fun manual(context: JevContext) = action {
        manualAnswer.value=repository.evaluate(context,store.settings.value,"MANUAL","MANUAL_INPUT")
        message.value="수동 판단 완료 · 실제 개입은 실행하지 않았습니다."
    }
    fun retry(id: String) = action { manualAnswer.value=repository.retryTrial(id);message.value="동일 요청 결과 확인 완료" }
    fun feedback(id: String,score: Int?) = action { repository.feedback(id,score);message.value="평가 저장 완료 · 서버 전송 대기열에 등록했습니다." }
    fun retryUploads() = action { store.dao.retry();repository.flush();message.value="전송 재시도 완료 · 남은 대기 건수를 확인해 주세요." }
    override fun onCleared() { scope.cancel();super.onCleared() }
}
