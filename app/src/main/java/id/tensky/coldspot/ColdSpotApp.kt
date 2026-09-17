package id.tensky.coldspot

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import java.io.File

@HiltAndroidApp
class ColdSpotApp : Application() {
    override fun onCreate() {
        // testbeds/sample/device-checks.sh step 19: a crash during startup, ColdSpot started and its background thread
        // still loading what earlier launches saved. Asked for by a file the check puts in place; gone with the crash.
        if (File(filesDir, CRASH_ON_START).delete()) throw IllegalStateException("crash on start, asked for by device-checks")
        super.onCreate()
    }

    // A class with no line information: a private companion object holding only a constant, whose one method (the
    // constructor) JaCoCo filters. The device skips such a class, and checkDeviceRun must as well (Finding 5).
    private companion object {
        const val CRASH_ON_START = "coldspot-crash-on-start"
    }
}
