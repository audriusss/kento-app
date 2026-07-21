plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "lt.sturmanas.bajeristas"
    compileSdk = 35

    defaultConfig {
        applicationId = "lt.sturmanas.bajeristas"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Phase 2: add GOOGLE_MAPS_API_KEY=your_key in local.properties,
        // then uncomment the meta-data block in AndroidManifest.xml.
        manifestPlaceholders["GOOGLE_MAPS_API_KEY"] = ""
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

    // Phase 2: Google Navigation SDK — enable after adding API key
    // implementation("com.google.android.libraries.navigation:navigation:5.2.2")

    // Phase 3: OkHttp WebSocket for OpenAI Realtime
    // implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(libs.junit)

    debugImplementation(libs.androidx.ui.tooling)
}
