package id.tensky.coldspot.runtime

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

// What ColdSpot's two screens share. Plain platform views and nothing else: no AndroidX in a team's app on
// ColdSpot's account (DECISIONS.md "Runtime compatibility").

/** Every marker has a shape of its own, so that none is told from another by colour alone. */
internal val Marker.icon: Int
    get() = when (this) {
        Marker.EXECUTED -> R.drawable.coldspot_ic_executed
        Marker.PARTIAL -> R.drawable.coldspot_ic_partial
        Marker.NOT_EXECUTED -> R.drawable.coldspot_ic_not_executed
        Marker.BLIND -> R.drawable.coldspot_ic_blind
        Marker.NO_CODE -> R.drawable.coldspot_ic_no_code
        Marker.PREVIEW -> R.drawable.coldspot_ic_preview
        Marker.ERROR -> R.drawable.coldspot_ic_error
    }

/** The tint behind a changed line: a second cue, faint enough for the text to keep its contrast. */
internal val Marker.background: Int
    get() = when (this) {
        Marker.EXECUTED -> R.color.coldspot_executed_background
        Marker.PARTIAL, Marker.BLIND -> R.color.coldspot_partial_background
        Marker.NOT_EXECUTED -> R.color.coldspot_not_executed_background
        Marker.NO_CODE, Marker.PREVIEW -> R.color.coldspot_neutral_background
        Marker.ERROR -> R.color.coldspot_error_background
    }

internal val FileMarker.icon: Int
    get() = when (this) {
        FileMarker.ERROR -> R.drawable.coldspot_ic_error
        FileMarker.NOT_EXECUTED -> R.drawable.coldspot_ic_not_executed
        FileMarker.PARTIAL -> R.drawable.coldspot_ic_partial
        FileMarker.EXECUTED -> R.drawable.coldspot_ic_executed
        FileMarker.NEUTRAL -> R.drawable.coldspot_ic_no_code
    }

internal val FileMarker.color: Int
    get() = when (this) {
        FileMarker.ERROR -> R.color.coldspot_error
        FileMarker.NOT_EXECUTED -> R.color.coldspot_not_executed
        FileMarker.PARTIAL -> R.color.coldspot_partial
        FileMarker.EXECUTED -> R.color.coldspot_executed
        FileMarker.NEUTRAL -> R.color.coldspot_neutral
    }

internal val Banner.Kind.icon: Int
    get() = when (this) {
        Banner.Kind.ERROR, Banner.Kind.STALE -> R.drawable.coldspot_ic_error
        Banner.Kind.WARNING -> R.drawable.coldspot_ic_warning
    }

internal val Banner.Kind.background: Int
    get() = when (this) {
        Banner.Kind.ERROR, Banner.Kind.STALE -> R.color.coldspot_error_background
        Banner.Kind.WARNING -> R.color.coldspot_partial_background
    }

/**
 * The last resort of a ColdSpot screen that cannot show even an error state, its layout gone wrong: what failed, in
 * a toast, and the screen closed. The app, underneath, goes on.
 */
internal fun Activity.closeWith(e: Throwable) {
    catching({ Toast.makeText(this, "ColdSpot cannot open this screen: ${describe(e)}", Toast.LENGTH_LONG).show() }) {}
    finish()
}

/** A moment as the reader's locale writes date and time. */
internal fun formatTime(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

/**
 * The window of a ColdSpot screen. Where windows are laid out edge to edge (enforced from API 35 for apps that
 * target it), the bars are see-through and their icons must be dark on the light theme; whatever insets reach
 * [root], the bars, the cutout and the keyboard, go as padding to [top] and [bottom], so that the bar above clears
 * the status bar and the last row clears the navigation bar. A window laid out inside the bars gets none.
 */
internal fun Activity.fitWindow(root: View, top: View, bottom: View) {
    val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    if (Build.VERSION.SDK_INT >= 30) {
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (night) 0 else light, light)
    }
    val topPadding = top.paddingTop
    val bottomPadding = bottom.paddingBottom
    root.setOnApplyWindowInsetsListener { view, insets ->
        val edges = if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            Edges(bars.left, bars.top, bars.right, bars.bottom)
        } else {
            @Suppress("DEPRECATION")
            Edges(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        view.setPadding(edges.left, 0, edges.right, 0)
        top.setPadding(top.paddingLeft, topPadding + edges.top, top.paddingRight, top.paddingBottom)
        bottom.setPadding(bottom.paddingLeft, bottom.paddingTop, bottom.paddingRight, bottomPadding + edges.bottom)
        insets
    }
}
