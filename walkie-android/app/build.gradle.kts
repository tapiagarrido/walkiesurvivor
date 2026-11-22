import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.radiobarrio.walkie"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.radiobarrio.walkie"
        minSdk = 29
        targetSdk = 36
        versionCode = 12
        versionName = "0.8.2"
    }
    buildTypes {
        release {
            // Versión optimizada (R8): mucho más fluida que la de depuración
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")   // zxing trae una versión vieja
}
