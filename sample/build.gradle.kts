plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

import java.util.Properties

val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        load(localFile.inputStream())
    }
}

android {
    namespace = "cl.zea.glia.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "cl.zea.glia.sample"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Secure credential injection (reads from gitignored local.properties or system env vars)
        val zeaGateway = localProperties.getProperty("zea.gateway.url") ?: System.getenv("ZEA_GATEWAY_URL") ?: ""
        val zeaAppId = localProperties.getProperty("zea.app.id") ?: System.getenv("ZEA_APP_ID") ?: ""
        val zeaUserId = localProperties.getProperty("zea.user.id") ?: System.getenv("ZEA_USER_ID") ?: ""
        val zeaToken = localProperties.getProperty("zea.token") ?: System.getenv("ZEA_TOKEN") ?: ""
        val sseEndpoint = localProperties.getProperty("sse.endpoint.url") ?: System.getenv("SSE_ENDPOINT_URL") ?: ""
        val sseToken = localProperties.getProperty("sse.token") ?: System.getenv("SSE_TOKEN") ?: ""

        buildConfigField("String", "ZEA_GATEWAY_URL", "\"$zeaGateway\"")
        buildConfigField("String", "ZEA_APP_ID", "\"$zeaAppId\"")
        buildConfigField("String", "ZEA_USER_ID", "\"$zeaUserId\"")
        buildConfigField("String", "ZEA_TOKEN", "\"$zeaToken\"")
        buildConfigField("String", "SSE_ENDPOINT_URL", "\"$sseEndpoint\"")
        buildConfigField("String", "SSE_TOKEN", "\"$sseToken\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation(project(":glia"))

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("io.ktor:ktor-client-core:2.3.11")
    implementation("io.ktor:ktor-client-okhttp:2.3.11")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
