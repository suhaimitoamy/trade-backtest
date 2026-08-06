plugins {
    id("com.android.application") version "8.7.3"
}

val configuredWebUrl = providers.gradleProperty("BACKTEST_WEB_URL")
    .orElse("https://trade-backtest.vercel.app")
val configuredVersionName = providers.gradleProperty("APP_VERSION_NAME")
    .orElse("2.1.0-preview")
val configuredVersionCode = providers.gradleProperty("APP_VERSION_CODE")
    .orElse("2100")
val configuredAppLabel = providers.gradleProperty("APP_LABEL")
    .orElse("Sweep Acceptance Lab")

android {
    namespace = "com.amy.tradebacktest"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.amy.sweepacceptancelab"
        minSdk = 23
        targetSdk = 35
        versionCode = configuredVersionCode.get().toInt()
        versionName = configuredVersionName.get()

        manifestPlaceholders["appLabel"] = configuredAppLabel.get()
        buildConfigField("String", "WEB_URL", "\"${configuredWebUrl.get()}\"")
    }

    sourceSets {
        getByName("main") {
            // Package the same dashboard used by Vercel directly inside the APK.
            assets.srcDir("../../public")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
