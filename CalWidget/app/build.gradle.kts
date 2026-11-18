plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "app.calwidget"
    compileSdk = 34
    defaultConfig {
        applicationId = "app.calwidget"; minSdk = 26; targetSdk = 34
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore"); storePassword = "android"; keyAlias = "key"; keyPassword = "android"
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
