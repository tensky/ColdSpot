package id.tensky.coldspot

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import id.tensky.coldspot.ui.theme.ColdSpotTheme

/**
 * A second screen, so that the device checks can tell what ColdSpot's bubble does across activities. Exported
 * for `adb shell am start` alone.
 */
class SecondActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ColdSpotTheme {
                Scaffold(modifier = Modifier.fillMaxSize().logTaps("second")) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding)) {
                        Text(text = "Second screen")
                    }
                }
            }
        }
    }
}

/**
 * Logs every tap that reaches the app's own content, as `TAP <screen> <x>,<y>` in window pixels under the tag
 * `ColdSpotSample`: how testbeds/sample/device-checks.sh tells that a tap next to the bubble went to the app, and
 * a tap on the bubble did not.
 */
fun Modifier.logTaps(screen: String): Modifier = pointerInput(screen) {
    detectTapGestures { Log.i("ColdSpotSample", "TAP $screen ${it.x.toInt()},${it.y.toInt()}") }
}
