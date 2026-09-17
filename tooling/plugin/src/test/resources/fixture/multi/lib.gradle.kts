// A library with ColdSpot applied, depending on one without it.
plugins {
    id("com.android.library")
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
}

dependencies {
    implementation(project(":plain"))
    @LIB_DEPENDENCIES@
}
