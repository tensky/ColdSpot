package id.tensky.coldspot.runtime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView

/**
 * ColdSpot's overview: what was built and from what, whatever is wrong with it, how many of the changed lines
 * have executed, and the changed files, worst first. A file opens [ColdSpotFileActivity]. The analysis runs when
 * the screen opens and on Refresh, off the main thread; until the first result there is a progress state, and
 * a refresh keeps what is there until the new result replaces it.
 *
 * One list holds everything ([overviewItems]), so that a build with a thousand changed files scrolls as one with
 * ten. A plain platform activity; the bubble never shows here.
 */
public class ColdSpotActivity : Activity() {
    private lateinit var list: ListView
    private lateinit var loading: View
    private lateinit var progress: View
    private lateinit var refresh: View
    private val rows = Rows()
    private var report: Report? = null
    private var overview: Overview? = null
    private val expanded = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        guarded("opening ColdSpot's screen", { closeWith(it) }) {
            setContentView(R.layout.coldspot_overview)
            list = findViewById(R.id.coldspot_list)
            loading = findViewById(R.id.coldspot_loading)
            progress = findViewById(R.id.coldspot_progress)
            refresh = findViewById(R.id.coldspot_refresh)
            fitWindow(findViewById(R.id.coldspot_root), top = findViewById(R.id.coldspot_bar), bottom = list)
            savedInstanceState?.getStringArrayList(EXPANDED)?.let(expanded::addAll)
            list.adapter = rows
            list.setOnItemClickListener { _, _, position, _ -> guarded("opening a file") { tapped(rows.getItem(position)) } }
            refresh.setOnClickListener { guarded("refreshing") { analyze() } }
            findViewById<View>(R.id.coldspot_share).setOnClickListener { guarded("sharing") { share() } }
            analyze()
        }
    }

    override fun onResume() {
        super.onResume()
        ColdSpot.bubble?.onChange = { rows.notifyDataSetChanged() }
    }

    override fun onPause() {
        super.onPause()
        ColdSpot.bubble?.onChange = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(EXPANDED, ArrayList(expanded))
    }

    private fun analyze() {
        // The first time there is nothing to keep on the screen; later the list stays until the new result is in.
        loading.visibility = if (report == null) View.VISIBLE else View.GONE
        list.visibility = if (report == null) View.GONE else View.VISIBLE
        progress.visibility = if (report == null) View.GONE else View.VISIBLE
        refresh.visibility = if (report == null) View.VISIBLE else View.GONE
        ColdSpot.analyze { result ->
            if (isDestroyed) return@analyze
            // What cannot be shown is the error state: the report came, and something here failed with it.
            logged("showing the analysis", { e -> overview = cannotShow(e) }) {
                report = result
                overview = overview(result, ColdSpot.installedAt(this), ::formatTime)
            }
            show()
            loading.visibility = View.GONE
            list.visibility = View.VISIBLE
            progress.visibility = View.GONE
            refresh.visibility = View.VISIBLE
        }
    }

    private fun show() {
        val current = overview ?: return
        rows.items = logged("showing the analysis", { e -> overviewItems(cannotShow(e), expanded) }) { overviewItems(current, expanded) }
        rows.notifyDataSetChanged()
    }

    private fun cannotShow(e: Throwable) = Overview.Failed("ColdSpot cannot show this", listOf("Showing the analysis failed: ${describe(e)}"), null)

    private fun tapped(item: OverviewItem) {
        when (item) {
            is OverviewItem.Toggle -> if (item.enabled) {
                if (!expanded.remove(item.key)) expanded += item.key
                show()
            }
            is OverviewItem.File -> startActivity(Intent(this, ColdSpotFileActivity::class.java).putExtra(ColdSpotFileActivity.PATH, item.row.path))
            else -> Unit
        }
    }

    private fun share() {
        val result = report ?: return
        val from = SharedFrom(
            device = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ").replaceFirstChar { it.uppercase() },
            android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            installedAt = ColdSpot.installedAt(this),
        )
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.coldspot_share_subject))
            .putExtra(Intent.EXTRA_TEXT, shareText(result, from, ::formatTime))
        startActivity(Intent.createChooser(send, getString(R.string.coldspot_share)))
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.coldspot_reset_title)
            .setMessage(R.string.coldspot_reset_message)
            .setNegativeButton(R.string.coldspot_cancel, null)
            .setPositiveButton(R.string.coldspot_reset) { _, _ ->
                guarded("resetting") {
                    // Both go to the runtime's one background thread, in this order: the analysis sees the clean start.
                    ColdSpot.reset()
                    analyze()
                }
            }
            .show()
    }

    /** The list's rows, one layout per kind of [OverviewItem], recycled. */
    private inner class Rows : BaseAdapter() {
        var items: List<OverviewItem> = emptyList()

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): OverviewItem = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount(): Int = LAYOUTS.size
        override fun getItemViewType(position: Int): Int = kind(items[position])
        override fun areAllItemsEnabled(): Boolean = false

        /** Only what does something when tapped takes a tap, and is announced as doing so. */
        override fun isEnabled(position: Int): Boolean = when (val item = items[position]) {
            is OverviewItem.Toggle -> item.enabled
            is OverviewItem.File -> true
            else -> false
        }

        /** A row that cannot be shown is left empty, and logged. */
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            logged("showing a row of the overview", { View(this@ColdSpotActivity) }) { row(position, convertView, parent) }

        private fun row(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = items[position]
            val view = convertView ?: LayoutInflater.from(this@ColdSpotActivity).inflate(LAYOUTS[kind(item)], parent, false)
            when (item) {
                is OverviewItem.Head -> bind(view, item.header)
                is OverviewItem.Toggle -> {
                    view.findViewById<TextView>(R.id.coldspot_toggle_title).text = item.title
                    view.findViewById<ImageView>(R.id.coldspot_toggle_icon).apply {
                        setImageResource(if (item.expanded) R.drawable.coldspot_ic_collapse else R.drawable.coldspot_ic_expand)
                        visibility = if (item.enabled) View.VISIBLE else View.INVISIBLE
                    }
                    view.contentDescription = if (!item.enabled) item.title else getString(if (item.expanded) R.string.coldspot_expanded else R.string.coldspot_collapsed, item.title)
                }
                is OverviewItem.Commit -> (view as TextView).text = item.text
                is OverviewItem.BannerRow -> {
                    view.findViewById<View>(R.id.coldspot_banner).setBackgroundResource(item.banner.kind.background)
                    view.findViewById<ImageView>(R.id.coldspot_banner_icon).setImageResource(item.banner.kind.icon)
                    view.findViewById<TextView>(R.id.coldspot_banner_title).text = item.banner.title
                    view.findViewById<TextView>(R.id.coldspot_banner_details).text = item.banner.details.joinToString("\n") { "• $it" }
                }
                is OverviewItem.Total -> (view as TextView).text = item.text
                is OverviewItem.File -> bind(view, item.row)
                is OverviewItem.Left -> {
                    view.findViewById<TextView>(R.id.coldspot_left_path).text = item.item.path
                    view.findViewById<TextView>(R.id.coldspot_left_reason).text = item.item.reason
                }
                is OverviewItem.Message -> {
                    view.findViewById<ImageView>(R.id.coldspot_message_icon).setImageResource(if (item.failed) R.drawable.coldspot_ic_error else R.drawable.coldspot_ic_executed)
                    view.findViewById<TextView>(R.id.coldspot_message_title).text = item.title
                    view.findViewById<TextView>(R.id.coldspot_message_text).text = item.messages.joinToString("\n\n")
                }
                is OverviewItem.Actions -> bindActions(view)
            }
            return view
        }

        private fun kind(item: OverviewItem): Int = when (item) {
            is OverviewItem.Head -> 0
            is OverviewItem.Toggle -> 1
            is OverviewItem.Commit -> 2
            is OverviewItem.BannerRow -> 3
            is OverviewItem.Total -> 4
            is OverviewItem.File -> 5
            is OverviewItem.Left -> 6
            is OverviewItem.Message -> 7
            is OverviewItem.Actions -> 8
        }
    }

    private fun bind(view: View, header: Header) {
        view.findViewById<TextView>(R.id.coldspot_base).text = labelled(R.string.coldspot_base, header.base)
        view.findViewById<TextView>(R.id.coldspot_base_note).apply {
            text = header.baseNote
            visibility = if (header.baseNote == null) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.coldspot_head).text = labelled(R.string.coldspot_head, header.head)
        view.findViewById<View>(R.id.coldspot_uncommitted).visibility = if (header.uncommitted) View.VISIBLE else View.GONE
        view.findViewById<TextView>(R.id.coldspot_installed).text = labelled(R.string.coldspot_installed, header.installedAt)
        view.findViewById<TextView>(R.id.coldspot_since).text = labelled(R.string.coldspot_since, header.collectingSince ?: getString(R.string.coldspot_since_never))
    }

    private fun bind(view: View, row: FileRow) {
        view.findViewById<ImageView>(R.id.coldspot_file_marker).apply {
            setImageResource(row.marker.icon)
            contentDescription = row.description
        }
        view.findViewById<TextView>(R.id.coldspot_file_name).text = row.name
        view.findViewById<TextView>(R.id.coldspot_file_directory).apply {
            text = row.directory
            visibility = if (row.directory.isEmpty()) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.coldspot_file_module).apply {
            text = row.module
            visibility = if (row.module.isEmpty()) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.coldspot_file_count).apply {
            text = row.count
            setTextColor(color(row.marker.color))
            // the marker next to it says the same in words
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun bindActions(view: View) {
        val bubble = ColdSpot.bubble
        view.findViewById<Switch>(R.id.coldspot_bubble_switch).apply {
            setOnCheckedChangeListener(null)
            isEnabled = bubble != null
            isChecked = bubble?.shown ?: false
            setOnCheckedChangeListener { _, checked -> guarded("showing or hiding the bubble") { ColdSpot.setBubbleVisible(checked) } }
        }
        view.findViewById<Button>(R.id.coldspot_reset).apply {
            isEnabled = ColdSpot.isActive
            setOnClickListener { guarded("resetting") { confirmReset() } }
        }
        view.findViewById<TextView>(R.id.coldspot_version).text = report?.build?.let { getString(R.string.coldspot_version, it.coldspotVersion) }
    }

    /** `Base  origin/main @ eb2505f`, the label dimmed. */
    private fun labelled(label: Int, value: String): CharSequence = SpannableStringBuilder().apply {
        append(getString(label))
        setSpan(ForegroundColorSpan(color(R.color.coldspot_on_surface_dim)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        append("  ").append(value)
    }

    @Suppress("DEPRECATION") // getColor(int, Theme) is API 23
    private fun color(id: Int): Int = resources.getColor(id)

    private companion object {
        const val EXPANDED = "coldspot.expanded"

        /** By [Rows.kind]. */
        val LAYOUTS = intArrayOf(
            R.layout.coldspot_row_head,
            R.layout.coldspot_row_toggle,
            R.layout.coldspot_row_commit,
            R.layout.coldspot_row_banner,
            R.layout.coldspot_row_total,
            R.layout.coldspot_row_file,
            R.layout.coldspot_row_left,
            R.layout.coldspot_row_message,
            R.layout.coldspot_row_actions,
        )
    }
}
