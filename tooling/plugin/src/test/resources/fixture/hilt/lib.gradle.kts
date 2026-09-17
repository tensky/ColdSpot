// The library of the Hilt fixture: Hilt and ColdSpot applied, holding the injected class.
plugins {
    id("com.android.library")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("io.github.tensky.coldspot")
}

android {
    namespace = "fixture.lib"
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
    implementation("com.google.dagger:hilt-android:@HILT@")
    ksp("com.google.dagger:hilt-android-compiler:@HILT@")
}
