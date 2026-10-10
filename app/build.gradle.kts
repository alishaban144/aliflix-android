plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
    id("org.jetbrains.kotlin.plugin.compose")
}

val mobileVersionCode = 245
val mobileVersionName = "3.1.155"

val tvVersionCode = 159
val tvVersionName = "3.1.69"
val releaseKeystoreFile = System.getenv("ALIFLIX_KEYSTORE_FILE")
val releaseKeystorePassword = System.getenv("ALIFLIX_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("ALIFLIX_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ALIFLIX_KEY_PASSWORD")
val releaseSigningConfigured = listOf(
    releaseKeystoreFile,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }
val githubReleaseBaseUrl =
    "https://github.com/alishaban144/aliflix-android/releases/latest/download"
val mobileUpdateManifestUrl = providers
    .gradleProperty("ALIFLIX_MOBILE_UPDATE_MANIFEST_URL")
    .orElse("$githubReleaseBaseUrl/update-mobile.json")
    .get()
val tvUpdateManifestUrl = providers
    .gradleProperty("ALIFLIX_TV_UPDATE_MANIFEST_URL")
    .orElse("$githubReleaseBaseUrl/update-tv.json")
    .get()

android {
    namespace = "com.aliflix.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aliflix.app"
        minSdk = 29
        targetSdk = 37
        versionCode = 175
        versionName = "3.1.85"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "RECOMMENDATION_AI_BASE_URL",
            "\"https://aliflix-recommendations.equable-equipment.workers.dev\""
        )
    }

    flavorDimensions += "formFactor"
    productFlavors {
        create("mobile") {
            dimension = "formFactor"
            proguardFile("mobile-proguard-rules.pro")
            versionCode = mobileVersionCode
            versionName = mobileVersionName
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
            versionCode = tvVersionCode
            versionName = tvVersionName
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

    val releaseSigning = if (releaseSigningConfigured) {
        signingConfigs.create("aliflixRelease") {
            storeFile = file(requireNotNull(releaseKeystoreFile))
            storePassword = requireNotNull(releaseKeystorePassword)
            keyAlias = requireNotNull(releaseKeyAlias)
            keyPassword = requireNotNull(releaseKeyPassword)
        }
    } else {
        signingConfigs.getByName("debug")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = releaseSigning
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

// The universal mobile APK carries all supported CPU ABIs. Compress native
// libraries for download; Android extracts them once at installation. TV keeps
// its existing packaging. This avoids a 135 MiB stored ONNX library payload.
androidComponents.onVariants(androidComponents.selector().withFlavor("formFactor" to "mobile")) { variant ->
    variant.packaging.jniLibs.useLegacyPackaging.set(true)
    variant.packaging.jniLibs.useLegacyPackagingFromBundle.set(true)
}

val validateReleaseSigning by tasks.registering {
    group = "verification"
    description = "Fails production release packaging when release signing credentials are unavailable."
    doLast {
        check(releaseSigningConfigured) {
            "Production release builds require ALIFLIX_KEYSTORE_FILE, ALIFLIX_KEYSTORE_PASSWORD, ALIFLIX_KEY_ALIAS, and ALIFLIX_KEY_PASSWORD."
        }
        check(file(requireNotNull(releaseKeystoreFile)).isFile) {
            "Production release keystore does not exist: $releaseKeystoreFile"
        }
    }
}

// Optional real Windows JNI validation; ordinary CI still runs the portable
// labelled-speech and alignment regressions. No emulator or JVM VAD substitute.
tasks.withType<Test>().matching { it.name.startsWith("testMobile") }.configureEach {
    // Filter on the task's name, not the project name. Argument providers append
    // these optional host paths after AGP's default JNI configuration.
    jvmArgumentProviders.add(org.gradle.process.CommandLineArgumentProvider {
        buildList {
            System.getenv("ALIFLIX_SYNC_HOST_CLASSES")?.let { add("-Xbootclasspath/a:$it") }
            System.getenv("ALIFLIX_SYNC_JNI_DIR")?.let {
                add("-Djava.library.path=$it")
                add("-Donnxruntime.native.path=$it")
            }
        }
    })
}

// The Firebase project currently registers only the mobile application ID.
// Account UI and Firebase initialization are intentionally mobile-only; keep
// the TV flavor buildable until a deliberate TV account experience is designed.
tasks.matching {
    it.name.startsWith("processTv") && it.name.endsWith("GoogleServices")
}.configureEach {
    enabled = false
}

dependencies {
    add("mobileImplementation", files("libs/webrtc-vad-2.0.10.aar"))
    add("mobileImplementation", "com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    val firebaseBom = platform("com.google.firebase:firebase-bom:34.18.0")
    implementation(composeBom)
    implementation(firebaseBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.webkit:webkit:1.17.0")
    implementation("com.google.firebase:firebase-auth")
    add("mobileImplementation", "androidx.credentials:credentials:1.6.0")
    add("mobileImplementation", "androidx.credentials:credentials-play-services-auth:1.6.0")
    add("mobileImplementation", "com.google.android.libraries.identity.googleid:googleid:1.2.0")
    add("mobileImplementation", "com.google.firebase:firebase-firestore")
    add("mobileImplementation", "org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    add("mobileImplementation", "androidx.fragment:fragment-ktx:1.8.9")
    listOf("exoplayer", "exoplayer-hls", "inspector", "session", "ui", "cast").forEach { module ->
        add("mobileImplementation", "androidx.media3:media3-$module:1.11.0")
    }

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jsoup:jsoup:1.19.1")

    add("testMobileImplementation", "org.robolectric:robolectric:4.16")
    add("testMobileImplementation", "androidx.media3:media3-test-utils-robolectric:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("org.json:json:20250107")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
