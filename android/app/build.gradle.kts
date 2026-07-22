import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Read GOOGLE_MAPS_API_KEY from local.properties (never committed to source control).
val localProperties = Properties().also { props ->
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) props.load(localFile.inputStream())
}
val googleMapsApiKey: String = localProperties.getProperty("GOOGLE_MAPS_API_KEY", "")
// OpenAI API key — must be set in local.properties (never committed to source control).
// Compiled into BuildConfig.OPENAI_API_KEY. Stays empty string when not set;
// KentasChat.askKentas() returns a Lithuanian error message in that case.
val openAiApiKey: String = localProperties.getProperty("OPENAI_API_KEY", "")

android {
    namespace = "lt.sturmanas.bajeristas"
    compileSdk = 35

    defaultConfig {
        applicationId = "lt.sturmanas.bajeristas"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Inject API key into AndroidManifest.xml and BuildConfig.
        // Stays empty string when local.properties has no key → MockNavigationEngine is used.
        manifestPlaceholders["GOOGLE_MAPS_API_KEY"] = googleMapsApiKey
        buildConfigField("String", "GOOGLE_MAPS_API_KEY", "\"$googleMapsApiKey\"")
        buildConfigField("String", "OPENAI_API_KEY", "\"$openAiApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Google Navigation SDK 7.8.0
    // Requires: Navigation SDK enabled in Google Cloud Console + valid API key in local.properties
    // If local.properties has no key, app falls back to MockNavigationEngine automatically.
    implementation("com.google.android.libraries.navigation:navigation:7.8.0")

    // Coroutines (for MockNavigationEngine simulation and suspend functions)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Phase 3: OkHttp WebSocket for OpenAI Realtime
    // implementation("com.squareup.okhttp3:okhttp:4.12.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    debugImplementation(libs.androidx.ui.tooling)
}
