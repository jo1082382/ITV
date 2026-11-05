package com.example.itv.application.records
import com.example.itv.domain.ai.*
/** Shadow only: cannot display overlays or alter the coaching policy. */
internal class InterventionInferenceService(private val predictor:InterventionPredictor){
 fun evaluate(features:InterventionFeatures)=predictor.predict(features)
}
