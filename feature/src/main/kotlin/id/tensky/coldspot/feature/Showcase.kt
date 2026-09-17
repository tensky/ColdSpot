package id.tensky.coldspot.feature

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

/**
 * A line of every kind ColdSpot tells apart, in one file, so that its screens and the device checks have them
 * all to show. The app calls [Showcase.run] with `true` once, at launch; the comments say what each line is
 * then. This comment is changed lines too: lines with no code.
 */
object Showcase {
    fun run(flag: Boolean): String {
        val executed = "executed" // executed: every instruction of the line ran
        val partly = if (flag) "this arm" else "the other arm" // partly executed: one arm of two
        if (!flag) {
            return "not executed" // not executed: the app never passes false
        }
        // can't be measured: sortedBy is inline and wraps its lambda in a class of kotlin's own file
        val blind = listOf(3, 1, 2).sortedBy { it * 2 }
        return "$executed, $partly, $blind"
    }
}

/**
 * The class the sample can make stale: built with `-Pcoldspot.sample=stale`, the bytes that ship for it are not
 * the bytes that run (see feature/build.gradle.kts), and its lines are in error.
 */
class Stale {
    fun marker(): String = "stale-marker-A"
}

/** A preview: never runs in the app. */
@Preview
@Composable
fun ShowcasePreview() {
    Text(text = Showcase.run(true))
}
