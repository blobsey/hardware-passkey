plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

ktlint {
    android.set(true)
    outputColorName.set("RED")
}

// Release signing: keystore lives at app/hardware-passkey-release.keystore
// the RELEASE_KEYSTORE_PASSWORD env var must be set!
val releaseKeystoreFile = file("hardware-passkey-release.keystore")
val releaseKeystorePassword: String? = System.getenv("RELEASE_KEYSTORE_PASSWORD")

android {
    namespace = "com.blobsey.hardwarepasskey"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.blobsey.hardwarepasskey"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (releaseKeystoreFile.exists() && releaseKeystorePassword != null) {
                storeFile = releaseKeystoreFile
                storePassword = releaseKeystorePassword
                keyAlias = "hardware-passkey"
                keyPassword = releaseKeystorePassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.credentials)
    implementation(libs.cbor)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
