plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val githubReleaseBaseUrl =
    "https://github.com/alishaban144/aliflix-android/releases/latest/download"
val legacyUpdateManifestOverride = providers.gradleProperty("ALIFLIX_UPDATE_MANIFEST_URL")
val mobileUpdateManifestUrl = providers
    .gradleProperty("ALIFLIX_MOBILE_UPDATE_MANIFEST_URL")
    .orElse(legacyUpdateManifestOverride)
    .orElse("$githubReleaseBaseUrl/update-mobile.json")
    .get()
val tvUpdateManifestUrl = providers
    .gradleProperty("ALIFLIX_TV_UPDATE_MANIFEST_URL")
    .orElse(legacyUpdateManifestOverride)
    .orElse("$githubReleaseBaseUrl/update-tv.json")
    .get()

android {
    namespace = "com.aliflix.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aliflix.app"
        minSdk = 29
        targetSdk = 37
        versionCode = 68
        versionName = "2.9.17"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "RECOMMENDATION_AI_BASE_URL",
            "\"https://aliflix-recommendations.alishaban144.workers.dev\""
        )
    }

    flavorDimensions += "formFactor"
    productFlavors {
        create("mobile") {
            dimension = "formFactor"
            buildConfigField("boolean", "IS_TV", "false")
            buildConfigField(
                "String",
                "UPDATE_MANIFEST_URL",
                "\"${mobileUpdateManifestUrl.replace("\"", "\\\"")}\"",
            )
        }
        create("tv") {
            dimension = "formFactor"
            applicationIdSuffix = ".tv"
            versionNameSuffix = "-tv"
            minSdk = 30
            buildConfigField("boolean", "IS_TV", "true")
            buildConfigField(
                "String",
                "UPDATE_MANIFEST_URL",
                "\"${tvUpdateManifestUrl.replace("\"", "\\\"")}\"",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    val releaseKeystoreFile = System.getenv("ALIFLIX_KEYSTORE_FILE")
    val releaseKeystorePassword = System.getenv("ALIFLIX_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("ALIFLIX_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("ALIFLIX_KEY_PASSWORD")
    val releaseSigning = if (
        !releaseKeystoreFile.isNullOrBlank() &&
        !releaseKeystorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()
    ) {
        signingConfigs.create("aliflixRelease") {
            storeFile = file(releaseKeystoreFile)
            storePassword = releaseKeystorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    } else {
        null
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigning != null) {
                signingConfig = releaseSigning
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jsoup:jsoup:1.19.1")
    implementation("com.google.mediapipe:tasks-text:0.10.14")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("org.json:json:20250107")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
