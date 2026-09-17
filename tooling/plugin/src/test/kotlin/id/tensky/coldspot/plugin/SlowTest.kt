package id.tensky.coldspot.plugin

/**
 * JUnit category of the tests too slow for `test` and `check`: whole Kotlin/KSP/Hilt builds under TestKit.
 * `./gradlew slowTest` runs them, and `testbeds/release-check.sh` runs that.
 */
interface SlowTest
