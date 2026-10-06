plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.qoder.sogousym"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.qoder.sogousym"
        minSdk = 24
        targetSdk = 36
        versionCode = 96
        versionName = "0.86"
        testInstrumentationRunner = "com.qoder.sogousym.LifecycleInstrumentation"
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    testImplementation(libs.junit)
    testImplementation("de.robv.android.xposed:api:82")
}
