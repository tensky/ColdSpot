package id.tensky.coldspot.runtime

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import java.util.Locale

/**
 * One changed file: where it is, what its changed lines did together, which of them are still to look at, and the
 * changed lines themselves in their hunks ([fileDetail]), at code density, each with a marker in the gutter.
 * Tapping anywhere on a changed line's row says below the list what the marker means for that line; TalkBack
 * reads and activates the rows one line at a time, and reads the explanation out when it changes. The rows are a
 * list's, recycled, so that a file of ten thousand lines scrolls as one of ten.
 *
 * Opened by [ColdSpotActivity] with the file's [PATH], and shows it from the report that screen made last; without
 * one, after the process was restarted under it, it analyses again.
 */
public class ColdSpotFileActivity : Activity() {
    private lateinit var list: ListView
    private lateinit var explanation: TextView
    private val rows = Rows()
    private var selected = NONE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        guarded("opening a file", { closeWith(it) }) {
            setContentView(R.layout.coldspot_file)
            list = findViewById(R.id.coldspot_lines)
            explanation = findViewById(R.id.coldspot_explanation)
            fitWindow(findViewById(R.id.coldspot_root), top = findViewById(R.id.coldspot_bar), bottom = explanation)
            findViewById<View>(R.id.coldspot_back).setOnClickListener { finish() }
            selected = savedInstanceState?.getInt(SELECTED, NONE) ?: NONE

            val path = intent.getStringExtra(PATH).orEmpty()
            findViewById<TextView>(R.id.coldspot_title).text = fileName(path)
            val report = ColdSpot.lastReport
            if (report != null) show(report, path) else ColdSpot.analyze { if (!isDestroyed) show(it, path) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(SELECTED, selected)
    }

    /** A file that cannot be shown says so, where the explanation goes. */
    private fun show(report: Report, path: String) {
        logged("showing a file", { e -> explanation.text = getString(R.string.coldspot_file_failed, describe(e)); explanation.visibility = View.VISIBLE }) { showNow(report, path) }
    }

    private fun showNow(report: Report, path: String) {
        val file = report.files.firstOrNull { it.path == path }
        if (file == null) {
            explanation.text = getString(R.string.coldspot_file_gone, path)
            return
        }
        val detail = fileDetail(file)
        list.addHeaderView(head(detail), null, false)
        rows.items = detail.rows
        rows.digits = detail.rows.filterIsInstance<DetailRow.Line>().maxOfOrNull { it.number }?.toString()?.length ?: 1
        list.adapter = rows
        list.setOnItemClickListener { _, _, position, _ ->
            guarded("explaining a line") {
                // the header is the list's first position
                val index = position - list.headerViewsCount
                if (index >= 0 && (rows.items[index] as? DetailRow.Line)?.marker != null) {
                    selected = index
                    rows.notifyDataSetChanged()
                    explain()
                }
            }
        }
        explanation.visibility = if (detail.note == null) View.VISIBLE else View.GONE
        explain()
    }

    private fun explain() {
        val line = rows.items.getOrNull(selected) as? DetailRow.Line
        explanation.text = if (line?.marker == null) getString(R.string.coldspot_tap_a_line) else getString(R.string.coldspot_line_explained, line.number, line.explanation)
    }

    /** Above the lines: where the file is, what became of it, and what the markers mean. */
    private fun head(detail: FileDetail): View {
        val view = LayoutInflater.from(this).inflate(R.layout.coldspot_file_head, list, false)
        view.findViewById<TextView>(R.id.coldspot_file_path).text = detail.path
        view.findViewById<TextView>(R.id.coldspot_file_module).apply {
            text = detail.module
            visibility = if (detail.module.isEmpty()) View.GONE else View.VISIBLE
        }
        view.findViewById<ImageView>(R.id.coldspot_file_marker).apply {
            if (detail.marker != null) setImageResource(detail.marker.icon)
            visibility = if (detail.marker == null) View.GONE else View.VISIBLE
            // the status next to it says it in words
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        view.findViewById<TextView>(R.id.coldspot_file_status).text = detail.status
        view.findViewById<TextView>(R.id.coldspot_file_summary).apply {
            text = detail.summary.joinToString("\n")
            contentDescription = detail.summaryDescription
            visibility = if (detail.summary.isEmpty()) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.coldspot_file_note).apply {
            text = detail.note
            visibility = if (detail.note == null) View.GONE else View.VISIBLE
        }
        val legend = view.findViewById<GridLayout>(R.id.coldspot_legend)
        legend.visibility = if (detail.note == null) View.VISIBLE else View.GONE
        for (marker in Marker.values()) {
            val item = LayoutInflater.from(this).inflate(R.layout.coldspot_legend_item, legend, false)
            item.findViewById<ImageView>(R.id.coldspot_legend_icon).setImageResource(marker.icon)
            item.findViewById<TextView>(R.id.coldspot_legend_label).text = marker.label
            // one column's worth each, whatever the label's length
            legend.addView(item, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 })
        }
        return view
    }

    /** A line of the file or a gap between hunks, recycled. */
    private inner class Rows : BaseAdapter() {
        var items: List<DetailRow> = emptyList()

        /** How many digits the largest line number has: every number gets that much room, so that the text lines up. */
        var digits: Int = 1

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): DetailRow = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount(): Int = 2
        override fun getItemViewType(position: Int): Int = if (items[position] is DetailRow.Line) LINE else GAP
        override fun areAllItemsEnabled(): Boolean = false

        /** A changed line takes a tap; context and gaps have nothing to say. */
        override fun isEnabled(position: Int): Boolean = (items[position] as? DetailRow.Line)?.marker != null

        /** A row that cannot be shown is left empty, and logged. */
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            logged("showing a line of a file", { View(this@ColdSpotFileActivity) }) { row(position, convertView, parent) }

        private fun row(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = items[position]
            val view = convertView ?: LayoutInflater.from(this@ColdSpotFileActivity).inflate(if (item is DetailRow.Line) R.layout.coldspot_row_line else R.layout.coldspot_row_gap, parent, false)
            when (item) {
                is DetailRow.Gap -> (view as TextView).text = item.text
                is DetailRow.Line -> bind(view, item, position == selected)
            }
            return view
        }

        private fun bind(view: View, line: DetailRow.Line, selected: Boolean) {
            val marker = line.marker
            view.findViewById<ImageView>(R.id.coldspot_line_marker).apply {
                if (marker != null) setImageResource(marker.icon)
                visibility = if (marker == null) View.INVISIBLE else View.VISIBLE
                contentDescription = marker?.label
            }
            view.findViewById<TextView>(R.id.coldspot_line_number).apply {
                text = String.format(Locale.ROOT, "%d", line.number)
                minimumWidth = Math.round(paint.measureText("0") * digits) + paddingLeft + paddingRight
            }
            view.findViewById<TextView>(R.id.coldspot_line_text).text = line.text
            // Code density for every line, changed or not (DECISIONS.md "Dev page structure"): the whole row is the target.
            if (marker == null) view.setBackgroundResource(0) else view.setBackgroundResource(marker.background)
            view.findViewById<View>(R.id.coldspot_line_selected).visibility = if (selected) View.VISIBLE else View.INVISIBLE
        }
    }

    internal companion object {
        /** The extra that names the file: its path as the manifest has it. */
        const val PATH = "id.tensky.coldspot.runtime.PATH"
        private const val SELECTED = "coldspot.selected"
        private const val NONE = -1
        private const val LINE = 0
        private const val GAP = 1
    }
}
