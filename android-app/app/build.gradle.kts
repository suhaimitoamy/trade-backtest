plugins {
    id("com.android.application") version "8.7.3"
}

val configuredWebUrl = providers.gradleProperty("BACKTEST_WEB_URL")
    .orElse("https://trade-backtest.vercel.app")

android {
    namespace = "com.amy.tradebacktest"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.amy.tradebacktest"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField(
            "String",
            "WEB_URL",
            "\"${configuredWebUrl.get()}\""
        )
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
