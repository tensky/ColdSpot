package id.tensky.coldspot.feature

import javax.inject.Inject

/** Injected into the app's activity by Hilt. */
class Greeter @Inject constructor() {
    fun greet(name: String): String = "Hello, $name"
}
