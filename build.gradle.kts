// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false

    // 파이어베이스 로그인를 위해 추가한 코드
    id("com.google.gms.google-services") version "4.4.2" apply false
}