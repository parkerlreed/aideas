plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.aideas.questusbcam"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aideas.questusbcam"
        // LSPosed itself requires 27+; Quest 2/3 are well past this.
        minSdk = 27
        targetSdk = 36
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

dependencies {
    // Stubs only - the real bridge is provided by LSPosed at runtime.
    compileOnly(libs.xposed.api)
}
