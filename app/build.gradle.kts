plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.eduqode.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.eduqode.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "2.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SITE_URL", "\"https://demo.lms.eduqode.com/demo\"")
        manifestPlaceholders["cleartext"] = "false"
    }

    // Test-only signing key kept in the repo, so each new test APK installs over the
    // previous one without uninstalling. The Play Store release uses a separate private key.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Play Store upload key. Comes from GitHub secrets, never from the repo.
        val uploadKey = System.getenv("UPLOAD_KEYSTORE_FILE")
        if (!uploadKey.isNullOrBlank() && file(uploadKey).exists()) {
            create("upload") {
                storeFile = file(uploadKey)
                storePassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // Only for automated end-to-end tests on an emulator against a local test WordPress
        // (10.0.2.2 is the computer running the emulator). Never published.
        create("e2e") {
            initWith(getByName("debug"))
            buildConfigField("String", "SITE_URL", "\"http://10.0.2.2:8099\"")
            manifestPlaceholders["cleartext"] = "true"
            matchingFallbacks += listOf("debug")
        }
        release {
            signingConfigs.findByName("upload")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    testBuildType = if (project.hasProperty("e2e")) "e2e" else "debug"
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.browser:browser:1.8.0")

    // Offline learning
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// Print every unit test result in the build log.
tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
