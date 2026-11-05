package com.example.itv.domain.ai
/** Fixed order/units form a model contract. Missing data must not be replaced by zero. */
internal data class InterventionFeatures(val sessionMinutes:Double,val instagramTodayMinutes:Double,
 val youtubeTodayMinutes:Double,val localHour:Double){
 fun values()=doubleArrayOf(sessionMinutes,instagramTodayMinutes,youtubeTodayMinutes,localHour)
}
internal data class InterventionPrediction(val probability:Double,val modelVersion:String)
internal interface InterventionPredictor {fun predict(features:InterventionFeatures):InterventionPrediction}
internal object MlpMath {
 fun forward(input:DoubleArray,mean:DoubleArray,scale:DoubleArray,w1:Array<DoubleArray>,b1:DoubleArray,w2:DoubleArray,b2:Double):Double {
  require(input.size==4 && input.all {it.isFinite() && it>=0})
  require(mean.size==4 && scale.size==4 && scale.all {it.isFinite() && it>0})
  require(w1.isNotEmpty() && w1.size==b1.size && w2.size==b1.size && w1.all {it.size==4})
  val normalized=DoubleArray(4){(input[it]-mean[it])/scale[it]}
  val hidden=DoubleArray(b1.size){i->maxOf(0.0,b1[i]+normalized.indices.sumOf {j->normalized[j]*w1[i][j]})}
  val z=b2+hidden.indices.sumOf {hidden[it]*w2[it]}
  require(z.isFinite())
  return if(z>=0)1.0/(1.0+kotlin.math.exp(-z)) else kotlin.math.exp(z)/(1.0+kotlin.math.exp(z))
 }
}
