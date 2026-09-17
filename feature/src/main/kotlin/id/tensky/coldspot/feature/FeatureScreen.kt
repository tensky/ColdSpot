package id.tensky.coldspot.feature

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** A composable with a branch: the spike's second subject for a library module. */
@Composable
fun FeatureScreen(items: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        if (items == 0) {
            Text(text = "Nothing yet")
        } else {
            Text(text = "$items items")
        }
    }
}
