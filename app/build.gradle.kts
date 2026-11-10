plugins {
    id("com.android.application")
}

android {
    namespace = "com.mibebe.registro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mibebe.registro"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
