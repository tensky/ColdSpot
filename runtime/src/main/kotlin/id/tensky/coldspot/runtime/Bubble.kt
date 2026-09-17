package id.tensky.coldspot.runtime

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import java.util.concurrent.Executor

/**
 * The floating bubble: ColdSpot's entry point inside the app (DECISIONS.md "Entry points"). One small view per
 * activity, added to the activity's own window once that is up, so it needs no permission and never shows over
 * another app. It covers its own bounds and nothing more: touches anywhere else go to the app as before.
 *
 * Whether it shows: `coldSpot { bubble }` from the build is the default; [setVisible] (the API, the adb
 * broadcasts and the switch on ColdSpot's screen) overrides it and is remembered across launches, but only for
 * the install it was said to: a new build installed forgets it, so that nobody is locked out of a bubble hidden
 * long ago. "Hide until restart" from its long-press menu holds for the life of the process and is never
 * written down. Where it rests is one [BubblePosition] for every activity, remembered across installs too. All
 * of it is main-thread state; the preferences are read and written on [background].
 */
internal class Bubble(private val app: Application, private val background: Executor, private val main: Handler) {
    private val views = HashMap<Activity, BubbleView>()
    private val resumed = LinkedHashSet<Activity>()

    /** Activities whose window is not up yet, each with the listener that waits for it. */
    private val waiting = HashMap<Activity, View.OnAttachStateChangeListener>()

    /** Null until the preferences and the build's default are read: nothing shows before that. */
    private var settings: Settings? = null

    /** What [setVisible] asked for while [settings] were still being read; it wins over what was read. */
    private var askedMeanwhile: Boolean? = null
    private var hiddenUntilRestart = false

    private class Settings(val buildDefault: Boolean, var override: Boolean?, var position: BubblePosition)

    private val visible: Boolean
        get() = settings?.let { !hiddenUntilRestart && (it.override ?: it.buildDefault) } ?: false

    /** Whether the bubble shows now, for the switch on ColdSpot's screen; false until the settings are read. Main thread. */
    val shown: Boolean get() = visible

    /** Told on the main thread whenever [shown] may have changed. */
    var onChange: (() -> Unit)? = null

    /** Every way in, from the app's lifecycle, the background thread, the views and their listeners, is [guarded]. */
    fun start() {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            // Resumed, not created: the activity has set its content and asked for its window features by now.
            override fun onActivityResumed(activity: Activity) {
                resumed += activity
                refresh(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                resumed -= activity
            }

            override fun onActivityDestroyed(activity: Activity) {
                resumed -= activity
                guarded("taking the bubble away") {
                    views.remove(activity)?.detach()
                    waiting.remove(activity)?.let(activity.window.decorView::removeOnAttachStateChangeListener)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
        background.execute {
            // Settings that cannot be read leave the bubble away, and the overview says why.
            val loaded = guarded("reading the bubble's settings", { null }) { load() } ?: return@execute
            main.post {
                guarded("showing the bubble") {
                    askedMeanwhile?.let { loaded.override = it }
                    settings = loaded
                    if (loaded.override == false) Log.i(ColdSpot.TAG, "the bubble is hidden, as asked earlier in this install; the switch on ColdSpot's screen, ColdSpot.setBubbleVisible(true) or the SHOW_BUBBLE broadcast brings it back")
                    resumed.toList().forEach(::refresh)
                    onChange?.invoke()
                }
            }
        }
    }

    /** From any thread. [done] runs on the main thread once the choice is on disk, or failed to get there: always, once. */
    fun setVisible(visible: Boolean, done: Runnable? = null) {
        main.post {
            guarded("showing or hiding the bubble") {
                if (visible) hiddenUntilRestart = false
                val current = settings
                if (current == null) askedMeanwhile = visible else current.override = visible
                refreshAll()
            }
            background.execute {
                guarded("saving whether the bubble shows") {
                    // commit, not apply: this is the background thread already, and [done] means "on disk"
                    @SuppressLint("ApplySharedPref")
                    val saved = preferences().edit().putBoolean(KEY_VISIBLE, visible).putLong(KEY_VISIBLE_INSTALL, installedAt).commit()
                    if (!saved) Log.w(ColdSpot.TAG, "could not save the bubble's visibility; it holds until the app restarts")
                }
                if (done != null) main.post(done)
            }
        }
    }

    private fun hideUntilRestart() {
        hiddenUntilRestart = true
        Log.i(ColdSpot.TAG, "the bubble is hidden until the app restarts")
        refreshAll()
    }

    private fun moved(position: BubblePosition) {
        settings?.position = position
        background.execute {
            guarded("saving where the bubble rests") {
                preferences().edit().putBoolean(KEY_RIGHT, position.right).putFloat(KEY_VERTICAL, position.vertical).apply()
            }
        }
    }

    private fun refreshAll() {
        (views.keys + resumed).toList().forEach(::refresh)
        onChange?.invoke()
    }

    /**
     * Puts [activity]'s bubble where and how the current state says: there at the shared position, or gone.
     *
     * Only ever into a decor view that is attached to its window, and never while it is being attached. An
     * activity resumes for the first time before its window is up, and a view added then is attached with the
     * others, by index, in one pass over the decor view's children. Anything that inserts a sibling during that
     * pass shifts the ones behind it, and the last is never attached: it draws and takes touches, but has no
     * accessibility node (no TalkBack, no uiautomator) and runs nothing posted to it. androidx.core 1.16 and
     * 1.17 do exactly that, from `ProtectionLayout`. So the bubble waits for the window.
     */
    private fun refresh(activity: Activity) {
        guarded("showing the bubble") { refreshNow(activity) }
    }

    private fun refreshNow(activity: Activity) {
        val current = settings ?: return
        val wanted = visible && !isColdSpots(activity) && !activity.isFinishing && !activity.window.isFloating
        if (!wanted) {
            views.remove(activity)?.detach()
            return
        }
        val decor = activity.window.decorView as? ViewGroup ?: return
        if (!decor.isAttachedToWindow) {
            whenAttached(activity, decor)
            return
        }
        val view = views[activity] ?: BubbleView(activity, onOpen = { ColdSpot.open(activity) }, onHide = ::hideUntilRestart, onMoved = ::moved).also { views[activity] = it }
        if (view.parent == null) view.attach(decor) // new, or the app emptied its decor view
        view.rest(current.position)
    }

    /** ColdSpot's own screens, where the bubble never shows: it would open what is open. */
    private fun isColdSpots(activity: Activity): Boolean = activity is ColdSpotActivity || activity is ColdSpotFileActivity

    /** [refresh] again once [decor] is attached, from the main thread's queue: after the pass that attaches its children. */
    private fun whenAttached(activity: Activity, decor: View) {
        if (activity in waiting) return
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                guarded("showing the bubble") {
                    decor.removeOnAttachStateChangeListener(this)
                    waiting.remove(activity)
                    main.post { refresh(activity) }
                }
            }

            override fun onViewDetachedFromWindow(view: View) {}
        }
        waiting[activity] = listener
        decor.addOnAttachStateChangeListener(listener)
    }

    private fun load(): Settings {
        val preferences = preferences()
        val position = BubblePosition(
            right = preferences.getBoolean(KEY_RIGHT, BubblePosition.DEFAULT.right),
            vertical = preferences.getFloat(KEY_VERTICAL, BubblePosition.DEFAULT.vertical),
        )
        return Settings(buildDefault, rememberedFor(installedAt, preferences), position)
    }

    /**
     * What [setVisible] was told last, if it was told to this install. Told to an earlier one, it is forgotten,
     * here and on disk: the bubble of a new build shows as that build says.
     */
    private fun rememberedFor(installedAt: Long, preferences: SharedPreferences): Boolean? {
        if (!preferences.contains(KEY_VISIBLE)) return null
        if (preferences.getLong(KEY_VISIBLE_INSTALL, NEVER) == installedAt) return preferences.getBoolean(KEY_VISIBLE, true)
        preferences.edit().remove(KEY_VISIBLE).remove(KEY_VISIBLE_INSTALL).apply()
        Log.i(ColdSpot.TAG, "a new install: what was said about the bubble earlier is forgotten, it shows as this build says")
        return null
    }

    /** When the package was installed last, which every install of a build moves: what tells this install from the one before. */
    private val installedAt: Long by lazy { ColdSpot.installedAt(app) }

    /** `coldSpot { bubble }`: the resource the plugin wrote over the library's default. */
    private val buildDefault: Boolean by lazy { app.resources.getBoolean(R.bool.coldspot_bubble) }

    private fun preferences() = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private companion object {
        const val PREFERENCES = "id.tensky.coldspot.runtime"
        const val KEY_VISIBLE = "bubble.visible"
        const val KEY_VISIBLE_INSTALL = "bubble.visible.install"
        const val NEVER = -1L
        const val KEY_RIGHT = "bubble.right"
        const val KEY_VERTICAL = "bubble.vertical"
    }
}

/**
 * The bubble in one activity: a child of the window's decor view, as large as itself, moved by translation.
 * A tap opens ColdSpot, a long press offers "Hide until restart", a drag moves it and letting go sends it to
 * the nearer edge. It stays inside the room the system bars and cutouts leave, in edge-to-edge windows too.
 */
@SuppressLint("ViewConstructor", "AppCompatCustomView")
internal class BubbleView(
    context: Context,
    private val onOpen: () -> Unit,
    private val onHide: () -> Unit,
    private val onMoved: (BubblePosition) -> Unit,
) : ImageView(context) {
    private val density = resources.displayMetrics.density
    private val size = Math.round(SIZE_DP * density)
    private val margin = Math.round(MARGIN_DP * density)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var position = BubblePosition.DEFAULT
    private var decor: ViewGroup? = null

    // One gesture: where it began, and what it has turned into.
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var longPressed = false
    private val longPress = Runnable {
        guarded("the bubble's long press") {
            longPressed = true
            isPressed = false
            performLongClick()
        }
    }
    private val onDecorLayout = OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        guarded("placing the bubble") { if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) settle() }
    }

    init {
        id = R.id.coldspot_bubble
        setImageResource(R.drawable.coldspot_mark_small)
        setBackgroundResource(R.drawable.coldspot_bubble_background)
        val padding = Math.round(ICON_PADDING_DP * density)
        setPadding(padding, padding, padding, padding)
        elevation = ELEVATION_DP * density
        contentDescription = resources.getString(R.string.coldspot_open)
        isClickable = true
        isLongClickable = true
        isFocusable = true
        if (Build.VERSION.SDK_INT >= 26) importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setOnClickListener { guarded("opening ColdSpot's screen") { onOpen() } }
        setOnLongClickListener {
            guarded("the bubble's menu") { showMenu() }
            true
        }
    }

    fun attach(decor: ViewGroup) {
        this.decor?.removeOnLayoutChangeListener(onDecorLayout)
        this.decor = decor
        // The window's own left, whatever the layout direction: the place is kept as left or right of the screen.
        @SuppressLint("RtlHardcoded")
        val params = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.LEFT)
        decor.addView(this, params)
        decor.addOnLayoutChangeListener(onDecorLayout)
    }

    fun detach() {
        removeCallbacks(longPress)
        animate().cancel()
        decor?.removeOnLayoutChangeListener(onDecorLayout)
        decor?.removeView(this)
        decor = null
    }

    /** Goes to [position]'s place, at once: on resume, and when another activity's bubble was dragged meanwhile. */
    fun rest(position: BubblePosition) {
        this.position = position
        settle()
    }

    /** Insets arrive here when bars come and go without the window changing size. */
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        guarded("placing the bubble") { settle() }
        return super.onApplyWindowInsets(insets)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        guarded("placing the bubble") {
            requestApplyInsets()
            settle()
        }
    }

    private fun settle() {
        if (dragging) return
        val geometry = geometry() ?: return
        animate().cancel()
        val (x, y) = geometry.place(position)
        translationX = x.toFloat()
        translationY = y.toFloat()
    }

    private fun geometry(): BubbleGeometry? {
        val parent = decor ?: return null
        if (parent.width == 0 || parent.height == 0) return null
        return BubbleGeometry(parent.width, parent.height, safeInsets(parent), size, margin)
    }

    @SuppressLint("ClickableViewAccessibility") // performClick and performLongClick are called below
    override fun onTouchEvent(event: MotionEvent): Boolean {
        guarded("moving the bubble") { touched(event) }
        return true
    }

    private fun touched(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animate().cancel()
                downRawX = event.rawX
                downRawY = event.rawY
                downX = translationX
                downY = translationY
                dragging = false
                longPressed = false
                isPressed = true
                // A scrolling parent must not take the gesture over once it moves.
                parent?.requestDisallowInterceptTouchEvent(true)
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && !longPressed && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                    dragging = true
                    isPressed = false
                    removeCallbacks(longPress)
                }
                if (dragging) follow(event)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                isPressed = false
                when {
                    dragging -> {
                        follow(event) // where the finger left the screen, which the last move may not have reached
                        release()
                    }
                    !longPressed -> performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                isPressed = false
                if (dragging) release()
            }
        }
    }

    /** Under the finger, as far as its room goes. */
    private fun follow(event: MotionEvent) {
        val (x, y) = geometry()?.clamp(downX + event.rawX - downRawX, downY + event.rawY - downRawY) ?: return
        translationX = x
        translationY = y
    }

    /** Let go: to the nearer edge, and that is where every activity's bubble rests from now on. */
    private fun release() {
        dragging = false
        val geometry = geometry() ?: return
        position = geometry.snap(translationX, translationY)
        val (x, y) = geometry.place(position)
        animate().translationX(x.toFloat()).translationY(y.toFloat()).setDuration(SNAP_MILLIS).start()
        onMoved(position)
    }

    private fun showMenu() {
        PopupMenu(context, this).apply {
            inflate(R.menu.coldspot_bubble)
            setOnMenuItemClickListener { item ->
                guarded("hiding the bubble") { if (item.itemId == R.id.coldspot_hide_until_restart) onHide() }
                true
            }
        }.show()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        guarded("describing the bubble") {
            info.className = "android.widget.Button"
            // What the long press does, in words, for a screen reader's actions menu.
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, resources.getString(R.string.coldspot_hide_until_restart)))
        }
    }

    private companion object {
        const val SIZE_DP = 48f // the smallest touch target the platform's guidance allows
        const val MARGIN_DP = 8f
        const val ICON_PADDING_DP = 5f // the mark 38 dp across, as the logo draws the bubble
        const val ELEVATION_DP = 6f
        const val SNAP_MILLIS = 150L
    }
}

/**
 * What the system bars and display cutouts take from [view]'s window, whether the window is laid out edge to
 * edge or not: from the root insets, which no view in the window can have consumed. Before API 23 there are no
 * root insets to ask, and the visible display frame stands in (it leaves out the bars, and the keyboard with them).
 */
internal fun safeInsets(view: View): Edges {
    if (Build.VERSION.SDK_INT >= 23) {
        val insets = view.rootWindowInsets ?: return Edges.NONE
        if (Build.VERSION.SDK_INT >= 30) {
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return Edges(safe.left, safe.top, safe.right, safe.bottom)
        }
        // Stable insets are the bars alone; the system window insets would count the keyboard in.
        @Suppress("DEPRECATION")
        val bars = Edges(insets.stableInsetLeft, insets.stableInsetTop, insets.stableInsetRight, insets.stableInsetBottom)
        if (Build.VERSION.SDK_INT < 28) return bars
        val cutout = insets.displayCutout ?: return bars
        return bars.union(Edges(cutout.safeInsetLeft, cutout.safeInsetTop, cutout.safeInsetRight, cutout.safeInsetBottom))
    }
    val root = view.rootView
    val frame = Rect().also(root::getWindowVisibleDisplayFrame)
    val origin = IntArray(2).also(root::getLocationOnScreen)
    return Edges(
        left = maxOf(0, frame.left - origin[0]),
        top = maxOf(0, frame.top - origin[1]),
        right = maxOf(0, origin[0] + root.width - frame.right),
        bottom = maxOf(0, origin[1] + root.height - frame.bottom),
    )
}
