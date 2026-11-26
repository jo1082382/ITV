package com.example.itv.infrastructure.jev

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import androidx.room.*
import com.example.itv.BuildConfig
import com.example.itv.domain.jev.JevSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object JevFeature {
    fun available(): Boolean = BuildConfig.JEV_TESTING
}

@Entity(tableName="jev_trials")
data class JevTrial(
    @PrimaryKey val id: String, val testerId: String, val source: String, val payload: String,
    val createdAt: Long, val state: String = "PENDING", val probability: Double? = null,
    val model: String? = null, val inputTokens: Long? = null, val latencyMs: Long? = null,
    val error: String? = null, val displayedAt: Long? = null, val feedbackStatus: String? = null,
    val feedbackScore: Int? = null, val feedbackPrompted: Boolean = false
)
@Entity(tableName="jev_outbox",indices=[Index(value=["eventId"],unique=true)])
data class JevOutbox(
    @PrimaryKey(autoGenerate=true) val sequence: Long=0,
    val eventId: String, val payload: String, val attempts: Int=0,
    val retryAt: Long=0, val error: String?=null, val permanent: Boolean=false
)
@Dao
interface JevDao {
    @Upsert suspend fun saveTrial(trial: JevTrial)
    @Query("SELECT * FROM jev_trials WHERE id=:id") suspend fun trial(id: String): JevTrial?
    @Query("SELECT * FROM jev_trials ORDER BY createdAt DESC LIMIT 30") fun trials(): Flow<List<JevTrial>>
    @Query("SELECT * FROM jev_trials WHERE displayedAt IS NOT NULL AND feedbackStatus='PENDING' AND feedbackPrompted=0 ORDER BY displayedAt LIMIT 1")
    suspend fun nextFeedback(): JevTrial?
    @Query("SELECT * FROM jev_trials WHERE displayedAt IS NOT NULL AND feedbackStatus='PENDING' ORDER BY displayedAt")
    fun pendingFeedback(): Flow<List<JevTrial>>
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun enqueue(event: JevOutbox): Long
    @Update suspend fun update(event: JevOutbox)
    @Delete suspend fun remove(event: JevOutbox)
    @Query("SELECT * FROM jev_outbox WHERE permanent=0 ORDER BY sequence LIMIT 1") suspend fun head(): JevOutbox?
    @Query("SELECT COUNT(*) FROM jev_outbox") suspend fun count(): Int
    @Query("SELECT COUNT(*) FROM jev_outbox") fun pendingCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM jev_outbox WHERE permanent=1") fun failedCount(): Flow<Int>
    @Query("UPDATE jev_outbox SET permanent=0,retryAt=0") suspend fun retry()
}
@Database(entities=[JevTrial::class,JevOutbox::class],version=1,exportSchema=true)
abstract class JevDatabase: RoomDatabase() { abstract fun dao(): JevDao }

/** Only the AES key is in Keystore; the encrypted token is in a backup-excluded AtomicFile. */
private class TestToken(context: Context) {
    private val file=AtomicFile(File(context.noBackupFilesDir,"jev_test_token_v1"))
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("itv_jev_test_token",null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("itv_jev_test_token",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun read(): String? {
        if(!file.baseFile.exists()) return null
        val json=JSONObject(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(json.getString("iv"),Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(json.getString("value"),Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
    @Synchronized fun write(value: String) {
        require(value.isNotBlank() && value.length<=5000 && value.none { it=='\n' || it=='\r' }) { "유효한 테스트 토큰을 입력해 주세요." }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val json=JSONObject().put("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
            .put("value",Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP))
        val stream=file.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8));file.finishWrite(stream) }
        catch(e:Exception) { file.failWrite(stream);throw e }
    }
    @Synchronized fun clear() { file.delete() }
}
internal fun JevSettings.json(): JSONObject = JSONObject().apply {
    put("enabled",enabled);put("collectionConsent",collectionConsent);put("actualIntervention",actualIntervention)
    put("testerId",testerId);put("instagram",instagram);put("youtube",youtube);put("allApps",allApps)
    put("continuousEnabled",continuousEnabled);put("continuousSeconds",continuousSeconds)
    put("returnsEnabled",returnsEnabled);put("returnsThreshold",returnsThreshold)
    put("bedtimeEnabled",bedtimeEnabled);put("bedtimeDelayMinutes",bedtimeDelayMinutes);put("bedtimeWindowMinutes",bedtimeWindowMinutes)
    put("probabilityThreshold",probabilityThreshold);put("reevaluateSeconds",reevaluateSeconds)
    put("additionalViewingSeconds",additionalViewingSeconds);put("resultTtlSeconds",resultTtlSeconds)
    put("laterSeconds",laterSeconds);put("dailyLimit",dailyLimit);put("personalizedEnabled",personalizedEnabled);put("revision",revision)
}
private fun decode(j: JSONObject) = JevSettings(
    enabled=j.optBoolean("enabled"),collectionConsent=j.optBoolean("collectionConsent"),actualIntervention=j.optBoolean("actualIntervention"),
    testerId=j.optString("testerId","T01"),instagram=j.optBoolean("instagram",true),youtube=j.optBoolean("youtube",true),
    allApps=j.optBoolean("allApps",true),
    continuousEnabled=j.optBoolean("continuousEnabled",true),continuousSeconds=j.optInt("continuousSeconds",900),
    returnsEnabled=j.optBoolean("returnsEnabled",true),returnsThreshold=j.optInt("returnsThreshold",3),
    bedtimeEnabled=j.optBoolean("bedtimeEnabled",true),bedtimeDelayMinutes=j.optInt("bedtimeDelayMinutes",0),
    bedtimeWindowMinutes=j.optInt("bedtimeWindowMinutes",480),probabilityThreshold=j.optDouble("probabilityThreshold",0.75),
    reevaluateSeconds=j.optInt("reevaluateSeconds",300),additionalViewingSeconds=j.optInt("additionalViewingSeconds",300),
    resultTtlSeconds=j.optInt("resultTtlSeconds",30),laterSeconds=j.optInt("laterSeconds",300),
    dailyLimit=j.optInt("dailyLimit",20),personalizedEnabled=j.optBoolean("personalizedEnabled",true),revision=j.optLong("revision",1)
).validated()

class JevStore private constructor(context: Context) {
    private val appContext=context.applicationContext
    private val prefs=appContext.getSharedPreferences("jev_test_settings_v1",Context.MODE_PRIVATE)
    private val tokenFile=TestToken(appContext)
    val db=Room.databaseBuilder(appContext,JevDatabase::class.java,"jev-testing.db").build()
    val dao=db.dao()
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutableSettings=MutableStateFlow(JevSettings())
    val settings: StateFlow<JevSettings> = mutableSettings.asStateFlow()
    private val mutableToken=MutableStateFlow(false)
    val tokenConfigured: StateFlow<Boolean> = mutableToken.asStateFlow()
    val notice=MutableStateFlow<String?>(null)
    val initialized=MutableStateFlow(false)
    @Volatile var ready=false
        private set
    init {
        scope.launch {
            try {
                mutableSettings.value=decode(JSONObject(prefs.getString("settings","{}") ?: "{}"))
                mutableToken.value=!tokenFile.read().isNullOrEmpty()
            } catch(_:Exception) { notice.value="테스트 설정 또는 토큰을 읽지 못했습니다. 확인 후 다시 저장해 주세요." }
            finally { ready=true;initialized.value=true }
        }
    }
    suspend fun save(settings: JevSettings) = withContext(Dispatchers.IO) {
        val next=settings.validated().copy(revision=mutableSettings.value.revision+1)
        check(prefs.edit().putString("settings",next.json().toString()).commit()) { "설정 저장 실패" }
        mutableSettings.value=next
    }
    suspend fun saveToken(value: String) = withContext(Dispatchers.IO) { tokenFile.write(value.trim());mutableToken.value=true }
    suspend fun deleteToken() = withContext(Dispatchers.IO) { tokenFile.clear();mutableToken.value=false }
    internal fun token(): String = tokenFile.read()?.takeIf { it.isNotBlank() } ?: error("테스트 토큰을 입력해 주세요.")
    internal fun readControl(key: String): String? = prefs.getString("control_$key",null)
    internal suspend fun writeControl(key: String, value: String?) = withContext(Dispatchers.IO) {
        prefs.edit().putString("control_$key", value).apply()
    }
    companion object {
        @Volatile private var instance: JevStore?=null
        fun get(context: Context): JevStore = instance ?: synchronized(this) {
            instance ?: JevStore(context).also { instance=it }
        }
    }
}
