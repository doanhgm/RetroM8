plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.retrom8.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.retrom8.app"
        minSdk = 23
        targetSdk = 29 // Tối ưu đọc ROM trực tiếp từ bộ nhớ trên Android 10
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += "armeabi-v7a" // Chỉ đóng gói 32-bit cho HTC M8 để nhẹ APK
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true // Bắt buộc để Libretro load .so trên Android 10
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.github.Swordfish90:LibretroDroid:0.11.1")
}
