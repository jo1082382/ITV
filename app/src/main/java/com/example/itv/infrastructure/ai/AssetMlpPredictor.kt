package com.example.itv.infrastructure.ai
import android.content.Context
import com.example.itv.domain.ai.*
import org.json.JSONArray
import org.json.JSONObject
/** One ReLU hidden layer and one sigmoid output. No network or runtime dependency. */
internal class AssetMlpPredictor(context:Context):InterventionPredictor {
 private val doc=context.assets.open("ai/intervention_mlp.json").bufferedReader().use {JSONObject(it.readText())}
 private val expected=listOf("session_minutes","instagram_today_minutes","youtube_today_minutes","local_hour")
 private fun JSONArray.vector()=DoubleArray(length()){getDouble(it).also {v->require(v.isFinite())}}
 init {
  check(doc.getInt("schemaVersion")==1){"지원하지 않는 모델 형식"}
  check(doc.getBoolean("trained")){"학습 모델 미등록 · MLP 예측 보류"}
  check(doc.getString("target")=="rest_started_within_120s"){"모델 예측 목표 불일치"}
  val names=doc.getJSONArray("featureNames")
  check(names.length()==4 && expected.indices.all {names.getString(it)==expected[it]}){"입력 순서 불일치"}
  check(doc.getString("modelVersion").isNotBlank())
 }
 private val mean=doc.getJSONArray("mean").vector()
 private val scale=doc.getJSONArray("scale").vector()
 private val rows=doc.getJSONArray("w1")
 private val w1=Array(rows.length()){rows.getJSONArray(it).vector()}
 private val b1=doc.getJSONArray("b1").vector()
 private val w2=doc.getJSONArray("w2").vector()
 private val b2=doc.getDouble("b2").also {require(it.isFinite())}
 init {require(w1.size in 1..128);MlpMath.forward(doubleArrayOf(0.0,0.0,0.0,0.0),mean,scale,w1,b1,w2,b2)}
 override fun predict(features:InterventionFeatures)=InterventionPrediction(
  MlpMath.forward(features.values(),mean,scale,w1,b1,w2,b2),doc.getString("modelVersion"))
}
