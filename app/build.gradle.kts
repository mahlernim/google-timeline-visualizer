plugins {
    id("com.android.application")
}

android {
    namespace = "dev.mahlernim.timelinevisualizer"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.mahlernim.timelinevisualizer"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "2.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    signingConfigs {
        val signingStore = System.getenv("ANDROID_SIGNING_STORE_FILE")
        if (!signingStore.isNullOrBlank()) {
            create("release") {
                storeFile = file(signingStore)
                storePassword = System.getenv("ANDROID_SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            buildConfigField("String", "UPDATE_URL", "\"https://github.com/mahlernim/google-timeline-visualizer/releases/latest\"")
            buildConfigField("String", "UPDATE_FALLBACK_URL", "\"https://github.com/mahlernim/google-timeline-visualizer/releases/latest\"")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("String", "UPDATE_URL", "\"market://details?id=dev.mahlernim.timelinevisualizer\"")
            buildConfigField("String", "UPDATE_FALLBACK_URL", "\"https://play.google.com/store/apps/details?id=dev.mahlernim.timelinevisualizer\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.12.4")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("com.google.android.material:material:1.13.0")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
