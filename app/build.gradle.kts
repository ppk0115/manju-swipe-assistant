import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Credentials stay outside source control and are never packaged into the APK.
val releaseSigningFile = rootProject.file(".signing/release.properties")
val releaseSigning = Properties().apply {
    if (releaseSigningFile.isFile) releaseSigningFile.inputStream().use { load(it) }
}
if (!releaseSigningFile.isFile && gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }) {
    throw GradleException("Release signing is missing. Restore .signing/release.properties and the existing release keystore; see docs/release.md.")
}

android {
    namespace = "com.example.autoswipeassistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.autoswipeassistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 28
        versionName = "0.5.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningFile.isFile) {
            create("production") {
                storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
                storeType = releaseSigning.getProperty("storeType", "PKCS12")
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
                enableV1Signing = false // minSdk 26 supports APK signature v2.
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isJniDebuggable = false
            signingConfig = signingConfigs.findByName("production")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
