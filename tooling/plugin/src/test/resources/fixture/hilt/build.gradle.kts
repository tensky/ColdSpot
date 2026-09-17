// A Hilt application under ColdSpot, the smallest that makes Hilt's aggregating task run, injecting a class from
// a library module as the sample app does (see ColdSpotHiltTest). Plugins come without versions: KSP and Hilt
// ride on the plugin-under-test classpath with AGP (see the plugin's build file), so that all three share one
// classloader.
plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("io.github.tensky.coldspot")
}

android {
    namespace = "fixture.app" // also the application ID, which the manifest merger wants dotted
    compileSdk {
        version = release(36)
    }
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":lib"))
    implementation("com.google.dagger:hilt-android:@HILT@")
    ksp("com.google.dagger:hilt-android-compiler:@HILT@")
}
