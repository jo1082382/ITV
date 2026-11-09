plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)

    // 파이어베이스 로그인을 위해 추가한 기능
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.itv"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.itv"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        buildConfigField("boolean", "JEV_TESTING", "false")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Instrumented tests target only the isolated QA variant.
    testBuildType = "qa"

    buildTypes {
        getByName("debug") { buildConfigField("boolean", "JEV_TESTING", "true") }
        create("teamTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".teamtest"
            matchingFallbacks += listOf("debug")
            resValue("string", "itv_launcher_app_name", "ITV 공동 테스트")
        }
        create("qa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qa"
            matchingFallbacks += listOf("debug")
        }
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
}

dependencies {
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    add("qaImplementation", libs.androidx.compose.ui.test.manifest)
    add("qaImplementation", libs.androidx.compose.ui.tooling)

    // Firebase BOM (버전 한번에 관리)
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    // Firebase Authentication
    implementation("com.google.firebase:firebase-auth")
    // Google 로그인용 Credential Manager
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    // Google/Firebase가 쓰는 "Task" 방식의 비동기 결과를, Kotlin의 "코루틴(suspend 함수)" 방식으로 변환해주는 다리(어댑터) 역할
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

}

// Explicitly enable host tests on AGP 9.5 (also required by Compose preview runfiles).
androidComponents {
    beforeVariants(selector().all()) { variant ->
        (variant as com.android.build.api.variant.HasUnitTestBuilder).enableUnitTest = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}