import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

@Suppress("UnstableApiUsage", "DEPRECATION")
android {
    namespace = "dev.janakhpon.monocr"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.janakhpon.monocr"
        minSdk = 24
        targetSdk = 36
        versionCode = 5
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // The feedback-service key is a runtime secret, not source. It was a
        // string literal in SyncWorker.kt from 2026-04-11 to 2026-08-16, in a
        // public repository, next to the production endpoint — treat that value
        // as burned regardless of this change, because it is also in the git
        // history and in every shipped APK.
        //
        // Supplied the same way the signing credentials below are: from
        // local.properties, or the environment in CI. Absent, it stays empty and
        // SyncWorker skips sync rather than failing the build, so a contributor
        // who has no key can still build and run the app.
        val secrets = Properties()
        val secretsFile = rootProject.file("local.properties")
        if (secretsFile.exists()) {
            FileInputStream(secretsFile).use { secrets.load(it) }
        }
        val syncApiKey = secrets.getProperty("SYNC_API_KEY")
            ?: System.getenv("SYNC_API_KEY")
            ?: ""
        buildConfigField("String", "SYNC_API_KEY", "\"$syncApiKey\"")
    }

    signingConfigs {
        create("release") {
            val localProperties = Properties()
            val localPropertiesFile = rootProject.file("local.properties")
            if (localPropertiesFile.exists()) {
                FileInputStream(localPropertiesFile).use { localProperties.load(it) }
            }

            // Absolute path to one laptop until 2026-08-16, which made the
            // release variant unbuildable by anyone else. Configurable now, with
            // the old location as the default so existing setups keep working.
            storeFile = file(
                localProperties.getProperty("RELEASE_STORE_FILE")
                    ?: System.getenv("RELEASE_STORE_FILE")
                    ?: "/Users/zinmin/Documents/ocrandroid.jks"
            )
            storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD") ?: System.getenv("RELEASE_STORE_PASSWORD")
            keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS") ?: System.getenv("RELEASE_KEY_ALIAS")
            keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD") ?: System.getenv("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            ndk.debugSymbolLevel = "FULL"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        
        // Custom build type for sharing with testers (Production quality, Debug signed)
        create("staging") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
            keepDebugSymbols += "**/*.so"
        }
    }

    androidResources {
        noCompress += listOf("onnx", "ort")
        localeFilters += listOf("en", "my", "mnw")
    }

    // Explicitly link KSP generated directories since AGP 9 blocks auto-injection
    sourceSets {
        getByName("main") {
            java.srcDirs(
                "build/generated/ksp/main/kotlin",
                "build/generated/ksp/main/java"
            )
        }
        getByName("debug") {
            java.srcDirs(
                "build/generated/ksp/debug/kotlin",
                "build/generated/ksp/debug/java"
            )
        }
        getByName("release") {
            java.srcDirs(
                "build/generated/ksp/release/kotlin",
                "build/generated/ksp/release/java"
            )
        }
        getByName("test") {
            // The tiling fixture is generated from the reference implementation in
            // monocr-onnx and shared with web, iOS and Rust. Wired in from its one
            // location rather than copied here, because a copy is a copy that goes
            // stale and then agrees with the wrong answer.
            resources.srcDir(rootProject.file("../../shared/segmentation-fixtures"))
        }
    }
}


dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.material)
    implementation(libs.androidx.core.splashscreen)

    implementation(libs.onnxruntime.android)
    implementation(libs.coil.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    
    // Observability (Uncomment below to integrate)
    // implementation(libs.sentry.android)
    // implementation(platform(libs.firebase.bom))
    // implementation("com.google.firebase:firebase-crashlytics-ktx")

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    // Reads the shared tiling fixture. Test-only: `org.json` is in the mockable
    // android.jar, so every call throws in a plain JVM unit test.
    testImplementation(libs.gson)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
