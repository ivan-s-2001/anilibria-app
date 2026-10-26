plugins { id("com.android.application") }

android {
    namespace = "tv.aniliberty.next"
    compileSdk = 36
    buildFeatures { resValues = true }
    defaultConfig {
        applicationId = "app.anihall.preview"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.1"
        resValue("string", "app_name", "AniHall Preview")
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".ci"
            versionNameSuffix = "-ci"
            resValue("string", "app_name", "AniHall Preview CI")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.testLogging.exceptionFormat =
                org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.coil-kt.coil3:coil:3.3.0")
    implementation("io.coil-kt.coil3:coil-svg:3.3.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
