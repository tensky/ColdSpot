// A library left out of ColdSpot: debug and release only, no coverage build type.
plugins {
    id("com.android.library")
}

android {
    namespace = "fixture.plain"
    compileSdk {
        version = release(36)
    }
    defaultConfig {
        minSdk = 24
    }
}
