package id.tensky.coldspotspike

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

// Realistic-Compose noise subject. The only logic lines are the FeedRow
// branches; state, LazyColumn and lambdas are the structure being measured.
@Composable
fun Feed(items: List<String>) {
    var onlyA by remember { mutableStateOf(false) }
    val visible = if (onlyA) items.filter { it.startsWith("a") } else items
    Column {
        Button(onClick = { onlyA = !onlyA }) { Text("Toggle filter") }
        LazyColumn {
            items(visible) { item ->
                FeedRow(text = item, onClick = { Log.d("ColdSpot", "Feed: tapped $item") })
            }
        }
    }
}

// Branch lines: if -> 37-38, else -> 40-41
@Composable
fun FeedRow(text: String, onClick: () -> Unit) {
    if (text.startsWith("a")) {
        Log.d("ColdSpot", "FeedRow: a-branch first")
        Log.d("ColdSpot", "FeedRow: a-branch second")
    } else {
        Log.d("ColdSpot", "FeedRow: other-branch first")
        Log.d("ColdSpot", "FeedRow: other-branch second")
    }
    Text(text = text, modifier = Modifier.clickable(onClick = onClick))
}
