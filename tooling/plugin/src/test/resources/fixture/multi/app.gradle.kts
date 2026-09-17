// The application of the three-module fixture: ColdSpot applied, depends on :lib.
plugins {
    id("com.android.application")
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
}

@COLDSPOT@

dependencies {
    implementation(project(":lib"))
}
