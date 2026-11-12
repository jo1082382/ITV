package com.example.itv

import android.app.Application
import com.example.itv.composition.AuthComposition
import com.kakao.sdk.common.KakaoSdk
import com.navercorp.nid.NidOAuth

class ItvApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AuthComposition.initialize(this)
        // 카카오 SDK 초기화 (BuildConfig에 정의된 네이티브 앱 키 사용)
        KakaoSdk.init(this, BuildConfig.KAKAO_NATIVE_APP_KEY)
        // 네이버 SDK 초기화 (Client ID, Client Secret)
        NidOAuth.initialize(
            context = this,
            clientId = BuildConfig.NAVER_CLIENT_ID,
            clientSecret = BuildConfig.NAVER_CLIENT_SECRET,
            clientName = "ITV"
        )
    }
}
