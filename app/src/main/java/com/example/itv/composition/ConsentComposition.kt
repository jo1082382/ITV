package com.example.itv.composition

import android.content.Context
import com.example.itv.domain.consent.ConsentStore
import com.example.itv.infrastructure.consent.FileConsentStore

fun consentStore(context: Context): ConsentStore = FileConsentStore(context)
