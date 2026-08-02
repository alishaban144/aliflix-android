plugins {
    id("com.android.test")
}

android {
    namespace = "com.aliflix.benchmark"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    flavorDimensions += "formFactor"
    productFlavors {
        create("mobile") {
            dimension = "formFactor"
        }
        create("tv") {
            dimension = "formFactor"
        }
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    beforeVariants(selector().all()) { variantBuilder ->
        val isMobile = variantBuilder.productFlavors.any { (_, flavor) ->
            flavor == "mobile"
        }
        variantBuilder.enable =
            variantBuilder.buildType == "benchmark" && isMobile
    }
}

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test:runner:1.7.0")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
}
