plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.kkwongal.drive"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kkwongal.drive"
        minSdk = 28 // 공모전 요구사항: API 28 이상
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        val leagueUrl = providers.gradleProperty("LEAGUE_API_BASE_URL").orElse("").get()
        require(leagueUrl.none { it == '\"' || it == '\\' || it.isISOControl() }) { "Invalid API URL" }
        buildConfigField("String", "LEAGUE_API_BASE_URL", "\"$leagueUrl\"")
        buildConfigField("boolean", "ALLOW_LOCAL_LEAGUE_HTTP", "false")
    }

    // 안드로이드 차량용 표준 API (속도, 기어, 주행 중 화면 제한)
    useLibrary("android.car")

    buildTypes {
        debug {
            buildConfigField("boolean", "ALLOW_LOCAL_LEAGUE_HTTP",
                providers.gradleProperty("LEAGUE_LOCAL_HTTP").orElse("false").get().toBooleanStrict().toString())
        }
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.pleos.navihelper)
    // 핸들 각도 (급조향 감지)
    implementation(libs.pleos.vehicle)
    // 감점 음성 안내 (Pleos Gleo AI TextToSpeech SDK)
    implementation(libs.pleos.tts)
    // 여행 지도: 오픈스트리트맵 지도 표시
    implementation(libs.osmdroid)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
}
