plugins {
    alias(libs.plugins.android.application)
}

val embeddedSettings = providers.gradleProperty("embedded").orElse("false").get().toBoolean()
val nonRoot = embeddedSettings || providers.gradleProperty("nonRoot").orElse("false").get().toBoolean()

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
        buildConfigField("boolean", "NON_ROOT", nonRoot.toString())
        buildConfigField("boolean", "EMBEDDED", embeddedSettings.toString())
    }

    buildFeatures {
        buildConfig = true
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
