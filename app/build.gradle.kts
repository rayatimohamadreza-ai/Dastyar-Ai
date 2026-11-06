plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "ir.smmammad.ai"
    compileSdk = 35
    defaultConfig {
        applicationId = "ir.smmammad.ai"
        minSdk = 23
        // Keep API 28 while the app relies on legacy microphone foreground-service
        // behavior; raising targetSdk requires a full Android 14+ FGS migration.
        targetSdk = 28
        versionCode = 2
        versionName = "0.2.0"
    }
    lint {
        // This APK is sideloaded, not published on Google Play. Keep the compatibility
        // target for now and suppress only the Play Store target-level policy check.
        disable += "ExpiredTargetSdkVersion"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
