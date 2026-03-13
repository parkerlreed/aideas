plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.thermalviewer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.thermalviewer"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            // Build for arm64 (modern Android phones) and armeabi-v7a (older devices)
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
}
