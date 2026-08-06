plugins {
    id("com.android.application") version "8.7.3"
}

val configuredVersionName = providers.gradleProperty("APP_VERSION_NAME").orElse("2.5.0-preview")
val configuredVersionCode = providers.gradleProperty("APP_VERSION_CODE").orElse("2500")
val configuredAppLabel = providers.gradleProperty("APP_LABEL").orElse("Trading Method Lab")
val keystorePath = providers.gradleProperty("TML_KEYSTORE_PATH")
val keystorePassword = providers.gradleProperty("TML_KEYSTORE_PASSWORD").orElse("changeit-preview")
val configuredKeyAlias = providers.gradleProperty("TML_KEY_ALIAS").orElse("trading-method-lab")
val configuredKeyPassword = providers.gradleProperty("TML_KEY_PASSWORD").orElse("changeit-preview")
val hasPreviewSigning = keystorePath.isPresent

android {
    namespace = "com.amy.tradebacktest"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.amy.sweepacceptancelab"
        minSdk = 24
        targetSdk = 35
        versionCode = configuredVersionCode.get().toInt()
        versionName = configuredVersionName.get()
        manifestPlaceholders["appLabel"] = configuredAppLabel.get()
        buildConfigField("String", "WEB_URL", "\"https://trade-backtest.vercel.app\"")
    }

    sourceSets { getByName("main") { assets.srcDir("../../public") } }

    signingConfigs {
        if (hasPreviewSigning) {
            create("preview") {
                storeFile = file(keystorePath.get())
                storePassword = keystorePassword.get()
                keyAlias = configuredKeyAlias.get()
                keyPassword = configuredKeyPassword.get()
            }
        }
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (hasPreviewSigning) signingConfigs.getByName("preview") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core:1.15.0")
}
