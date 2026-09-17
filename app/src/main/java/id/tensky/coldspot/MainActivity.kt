package id.tensky.coldspot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import dagger.hilt.android.AndroidEntryPoint
import id.tensky.coldspot.feature.FeatureScreen
import id.tensky.coldspot.feature.Greeter
import id.tensky.coldspot.feature.Pricing
import id.tensky.coldspot.feature.Showcase
import id.tensky.coldspot.feature.Stale
import id.tensky.coldspot.feature.ThemePreviews
import id.tensky.coldspot.ui.theme.ColdSpotTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var greeter: Greeter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        insertSiblingWhileAttaching()
        // Exercises one arm of each branch in :feature, so the coverage dump has something to show.
        val price = Pricing().price(3)
        val showcase = Showcase.run(true) + Stale().marker().length
        setContent {
            ColdSpotTheme {
                Scaffold(modifier = Modifier.fillMaxSize().logTaps("main")) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding)) {
                        Greeting(name = greeter.greet("ColdSpot"))
                        Text(text = "3 cost $price")
                        Text(text = showcase)
                        FeatureScreen(items = 3)
                        Button(onClick = { startActivity(Intent(this@MainActivity, SecondActivity::class.java)) }) {
                            Text(text = "Second screen")
                        }
                    }
                }
            }
        }
    }
}

/**
 * What androidx.core 1.16 and 1.17 do to a window that holds a `ProtectionLayout`: while
 * the decor view is being attached, a child of it inserts another child at index 0. The children behind shift by
 * one, and the pass that attaches them, which goes by index, never reaches the last. ColdSpot's bubble must not
 * be that last child: the device checks find it by its accessibility label, which a view that was never attached
 * does not have. Here on purpose, so that the sample is as hostile as a real app.
 */
private fun Activity.insertSiblingWhileAttaching() {
    val decor = window.decorView as ViewGroup
    decor.addView(
        object : View(this) {
            override fun onAttachedToWindow() {
                super.onAttachedToWindow()
                decor.addView(View(context), 0, ViewGroup.LayoutParams(0, 0))
            }
        },
        ViewGroup.LayoutParams(0, 0),
    )
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "$name!",
        modifier = modifier
    )
}

// The previews below never run in the app: the bundle lists their lines as preview lines, neutral, never red.

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    ColdSpotTheme {
        Greeting("ColdSpot")
    }
}

/** A multipreview from :feature, so the annotation has to be found on the compile classpath. */
@ThemePreviews
@Composable
fun GreetingThemePreview() {
    ColdSpotTheme {
        Greeting("Themes")
    }
}

/** Preview-only, like the preview it feeds. */
class GreetingNames : PreviewParameterProvider<String> {
    override val values = sequenceOf("Ada", "Grace")
}

@Preview
@Composable
fun GreetingNamesPreview(@PreviewParameter(GreetingNames::class) name: String) {
    ColdSpotTheme {
        Greeting(name)
    }
}
