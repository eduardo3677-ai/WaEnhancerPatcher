import java.util.Base64

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.yukihookapi)
    alias(libs.plugins.gropify)
}

android {
    namespace = "com.waenhancer.patcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.waenhancer.patcher"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = project.findProperty("androidStoreFile")?.toString()
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = project.findProperty("androidStorePassword")?.toString()
                keyAlias = project.findProperty("androidKeyAlias")?.toString()
                keyPassword = project.findProperty("androidKeyPassword")?.toString()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    compileOnly(libs.yukihookapi.api)
    ksp(libs.yukihookapi.ksp)
    implementation(libs.dexkit)
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.android)
    implementation(libs.moshi.companion.api)
    implementation(libs.gropify)
}
