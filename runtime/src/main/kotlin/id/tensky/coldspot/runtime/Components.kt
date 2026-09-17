package id.tensky.coldspot.runtime

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.util.Log

/**
 * Startup, before `Application.onCreate`, as the manifest's provider (`${applicationId}.coldspot`, not exported):
 * installs [ColdSpot], which only registers listeners here. Never queried. Whatever fails here leaves ColdSpot idle
 * and the app starting as it would without it.
 */
public class ColdSpotProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        guarded("starting ColdSpot") { context?.let(ColdSpot::install) }
        return true
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}

/**
 * `adb shell am broadcast -a <applicationId>.coldspot.RESET -n <applicationId>/id.tensky.coldspot.runtime.ColdSpotResetReceiver`:
 * a clean start for scripts. Exported, but behind `android.permission.DUMP`, which the shell holds and no app
 * can be granted.
 */
public class ColdSpotResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        guarded("the reset broadcast") {
            Log.i(ColdSpot.TAG, "reset broadcast received")
            ColdSpot.reset()
        }
    }
}

/** `... -a <applicationId>.coldspot.DUMP ...`: logs the debug dump `checkDeviceRun` reads. Same guard as the reset. */
public class ColdSpotDumpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        guarded("the dump broadcast") { ColdSpot.dump() }
    }
}

/**
 * `... -a <applicationId>.coldspot.HIDE_BUBBLE ...` and `... -a <applicationId>.coldspot.SHOW_BUBBLE ...`, to
 * `<applicationId>/id.tensky.coldspot.runtime.ColdSpotBubbleReceiver`: the bubble out of the way of UI automation,
 * and back. The same as [ColdSpot.setBubbleVisible], so it holds across launches. Same guard as the reset.
 */
public class ColdSpotBubbleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        guarded("the bubble broadcast") {
            val visible = when (intent.action?.substringAfterLast('.')) {
                "SHOW_BUBBLE" -> true
                "HIDE_BUBBLE" -> false
                else -> return
            }
            Log.i(ColdSpot.TAG, "bubble broadcast received: ${if (visible) "show" else "hide"}")
            // Kept alive until the choice is on disk: the process may exist for this broadcast alone. Finished once,
            // whatever happens on the way: by Bubble.setVisible, or here when the call fails before it could hand the
            // finish on. A broadcast never finished would end in an ANR.
            val pending = goAsync()
            guarded("the bubble broadcast", { pending.finish() }) { ColdSpot.setBubbleVisible(visible, Runnable { pending.finish() }) }
        }
    }
}
