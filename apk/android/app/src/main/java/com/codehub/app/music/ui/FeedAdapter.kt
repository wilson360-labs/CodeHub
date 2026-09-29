package com.codehub.app.music.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.codehub.app.music.ArtworkLoader
import com.codehub.app.R
import dev.brahmkshatriya.echo.common.models.Shelf.Category
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track

/** Filas que se muestran en el feed del reproductor. */
sealed class Row {

    data class Tabs(val tabs: List<Tab>, val selected: Int) : Row()

    data class Header(
        val title: String,
        val subtitle: String?,
        val more: (() -> Unit)? = null,
    ) : Row()

    data class Card(val item: UiItem) : Row()

    data class CardCat(val category: Category) : Row()

    data class Cards(val items: List<UiItem>) : Row()

    data class TrackRow(val number: Int, val track: Track) : Row()

    data object Loading : Row()

    data class Info(val text: String) : Row()
}

/** Ítem de card: el medio Echo + subtítulo/imagen ya resueltos. */
data class UiItem(
    val media: EchoMediaItem,
    val subtitle: String?,
    val image: ImageHolder?,
)

/** Acceso a colores del tema actual (activity no-AppCompact). */
object ThemeColors {
    fun of(context: Context, attr: Int, fallback: Int): Int {
        val out = TypedValue()
        if (context.theme.resolveAttribute(attr, out, true)) return out.data
        return fallback
    }
}

/**
 * Adaptador del feed del reproductor: tabs, cabeceras de shelf, cards
 * (linear), pares de cards a modo grid, filas numeradas de track y estados.
 * Todo el UI es programático (mismo patrón que DownloadsActivity).
 */
class FeedAdapter(
    private val context: Context,
    private val artwork: ArtworkLoader,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var onCard: ((EchoMediaItem) -> Unit)? = null
    var onCategory: ((Category) -> Unit)? = null
    var onTrackClick: ((Int, Track) -> Unit)? = null
    var onTabSelected: ((Int) -> Unit)? = null

    private val rows = mutableListOf<Row>()
    private val density = context.resources.displayMetrics.density

    fun submit(rows: List<Row>) {
        this.rows.clear()
        this.rows.addAll(rows)
        notifyDataSetChanged()
    }

    private fun dp(v: Int) = (v * density).toInt()
    private fun textView(
        parent: ViewGroup,
        sizeSp: Float = 14f,
        bold: Boolean = false,
        secondary: Boolean = false,
    ): TextView {
        val t = TextView(parent.context)
        t.textSize = sizeSp
        t.setTypeface(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        t.setTextColor(if (secondary) MusicTheme.MUTED else MusicTheme.TEXT)
        t.maxLines = 1
        return t
    }

    private fun imageView(context: Context, sizeDp: Int): ImageView {
        val iv = ImageView(context)
        iv.layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        MusicTheme.roundImage(context, iv, 12)
        return iv
    }

    private fun placeholder(iv: ImageView) {
        iv.setImageDrawable(null)
    }

    private fun loadImage(iv: ImageView, holder: ImageHolder?) {
        placeholder(iv)
        artwork.load(holder) { bmp ->
            iv.setImageBitmap(bmp)
        }
    }

    fun clock(ms: Long): String {
        val s = ms / 1000
        val m = s / 60
        val h = m / 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m % 60, s % 60)
        else "%02d:%02d".format(m % 60, s % 60)
    }

    // ------------------------------------------------------------------
    // Adapter base
    // ------------------------------------------------------------------

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Tabs -> 0
        is Row.Header -> 1
        is Row.Card -> 2
        is Row.CardCat -> 3
        is Row.Cards -> 4
        is Row.TrackRow -> 5
        is Row.Loading -> 6
        is Row.Info -> 7
    }

    open class VH(view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            0 -> TabsVH(tabsView(parent))
            1 -> HeaderVH(headerView(parent))
            2 -> VH(cardView(parent, false))
            3 -> VH(cardCatView(parent))
            4 -> CardsVH(cardsView(parent))
            5 -> TrackVH(trackView(parent))
            6 -> VH(loadingView(parent))
            else -> InfoVH(infoView(parent))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Tabs -> {
                (holder as TabsVH).bind(row.tabs, row.selected) { onTabSelected?.invoke(it) }
            }

            is Row.Header -> {
                val h = holder as HeaderVH
                h.title.text = row.title
                h.subtitle.text = row.subtitle ?: ""
                h.subtitle.visibility = if (row.subtitle == null) View.GONE else View.VISIBLE
                h.more.text = "Ver todo ›"
                h.more.visibility = if (row.more == null) View.GONE else View.VISIBLE
                h.more.setOnClickListener { row.more?.invoke() }
            }

            is Row.Card -> {
                val item = row.item
                holder.itemView.tag = item.media
                val title = holder.itemView.findViewById<TextView>(ID_TITLE)
                val sub = holder.itemView.findViewById<TextView>(ID_SUB)
                val iv = holder.itemView.findViewById<ImageView>(ID_IMAGE)
                title.text = item.media.title
                sub.text = item.subtitle ?: ""
                sub.visibility = if (item.subtitle == null) View.GONE else View.VISIBLE
                loadImage(iv, item.image)
                holder.itemView.setOnClickListener { onCard?.invoke(item.media) }
            }

            is Row.CardCat -> {
                holder.itemView.tag = row.category
                val iv = holder.itemView.findViewById<ImageView>(ID_IMAGE)
                val title = holder.itemView.findViewById<TextView>(ID_TITLE)
                val sub = holder.itemView.findViewById<TextView>(ID_SUB)
                title.text = row.category.title
                sub.text = row.category.subtitle ?: ""
                sub.visibility = if (row.category.subtitle == null) View.GONE else View.VISIBLE
                loadImage(iv, row.category.image)
                holder.itemView.setOnClickListener { onCategory?.invoke(row.category) }
            }

            is Row.Cards -> {
                val h = holder as CardsVH
                h.cells.left.removeAllViews()
                h.cells.left.addView(gridCell(h.cells.left, row.items.getOrNull(0)))
                h.cells.right.removeAllViews()
                h.cells.right.addView(gridCell(h.cells.right, row.items.getOrNull(1)))
            }

            is Row.TrackRow -> {
                val h = holder as TrackVH
                h.number.text = (row.number + 1).toString().padStart(2, '0')
                h.title.text = row.track.title
                h.artists.text = row.track.artists.joinToString(", ") { it.name }
                h.duration.text = row.track.duration?.let { clock(it) } ?: ""
                holder.itemView.setOnClickListener { onTrackClick?.invoke(row.number, row.track) }
            }

            is Row.Loading, is Row.Info -> Unit
        }
        if (holder is InfoVH && rows[position] is Row.Info) {
            holder.text.text = (rows[position] as Row.Info).text
        }
    }

    // ------------------------------------------------------------------
    // Builders de vistas
    // ------------------------------------------------------------------

    private fun tabsView(parent: ViewGroup): View {
        val scroll = HorizontalScrollView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
            )
            isHorizontalScrollBarEnabled = false
        }
        val inner = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        scroll.addView(inner)
        return scroll
    }

    private fun headerView(parent: ViewGroup): View {
        val cell = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(4))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val row = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = textView(parent, 16f, bold = true)
        title.id = ID_TITLE
        title.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val more = textView(parent, 13f, secondary = true).apply { setTextColor(MusicTheme.ACCENT) }
        more.id = ID_MORE
        row.addView(title)
        row.addView(more)
        val sub = textView(parent, 12f, secondary = true)
        sub.id = ID_SUB
        cell.addView(row)
        cell.addView(sub)
        return cell
    }

    private fun cardView(parent: ViewGroup, big: Boolean): View {
        val cell = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = MusicTheme.ripple(parent.context, MusicTheme.rounded(parent.context, MusicTheme.SURFACE, 14))
            val lp = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(dp(12), dp(4), dp(12), dp(4))
            layoutParams = lp
            isClickable = true
        }
        val iv = imageView(parent.context, if (big) 96 else 52)
        iv.id = ID_IMAGE
        val col = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
            setPadding(dp(12), 0, 0, 0)
        }
        val title = textView(parent, 15f, bold = true)
        title.id = ID_TITLE
        val sub = textView(parent, 13f, secondary = true)
        sub.id = ID_SUB
        col.addView(title)
        col.addView(sub)
        cell.addView(iv)
        cell.addView(col)
        return cell
    }

    private fun cardCatView(parent: ViewGroup): View = cardView(parent, false)

    private fun gridCell(parent: ViewGroup, item: UiItem?): View {
        val cell = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(4), dp(4), dp(4), dp(8))
            isClickable = item != null
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        if (item == null) return cell
        val iv = imageView(parent.context, 128)
        iv.id = ID_IMAGE
        loadImage(iv, item.image)
        val title = textView(parent, 14f, bold = true)
        title.id = ID_TITLE
        val sub = textView(parent, 12f, secondary = true)
        sub.id = ID_SUB
        sub.text = item.subtitle ?: ""
        sub.visibility = if (item.subtitle == null) View.GONE else View.VISIBLE
        title.text = item.media.title
        cell.addView(iv)
        cell.addView(title)
        cell.addView(sub)
        cell.setOnClickListener { onCard?.invoke(item.media) }
        return cell
    }

    private fun cardsView(parent: ViewGroup): View {
        val cell = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val left = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val right = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        cell.addView(left)
        cell.addView(right)
        return cell
    }

    private fun trackView(parent: ViewGroup): View {
        val cell = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = MusicTheme.ripple(parent.context, MusicTheme.rounded(parent.context, MusicTheme.SURFACE, 12))
            val lp = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(dp(10), dp(2), dp(10), dp(2))
            layoutParams = lp
        }
        val number = textView(parent, 13f, secondary = true)
        number.id = ID_NUMBER
        number.layoutParams = LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT)
        number.gravity = Gravity.CENTER_VERTICAL
        val col = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val title = textView(parent, 14f)
        title.id = ID_TITLE
        val artists = textView(parent, 12f, secondary = true)
        artists.id = ID_ARTISTS
        val duration = textView(parent, 12f, secondary = true)
        duration.id = ID_DURATION
        col.addView(title)
        col.addView(artists)
        cell.addView(number)
        cell.addView(col)
        cell.addView(duration)
        return cell
    }

    private fun loadingView(parent: ViewGroup): View {
        return FrameLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(72)
            )
            addView(ProgressBar(parent.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    dp(40), dp(40), Gravity.CENTER
                )
            })
        }
    }

    private fun infoView(parent: ViewGroup): View {
        return TextView(parent.context).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setTextColor(MusicTheme.MUTED)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    // ------------------------------------------------------------------
    // ViewHolders
    // ------------------------------------------------------------------

    private class TabsVH(view: View) : VH(view) {
        fun bind(tabs: List<Tab>, selected: Int, onSelected: (Int) -> Unit) {
            val scroll = itemView as HorizontalScrollView
            val inner = scroll.getChildAt(0) as LinearLayout
            inner.removeAllViews()
            tabs.forEachIndexed { index, tab ->
                val ctx = inner.context
                val chip = TextView(ctx)
                chip.text = tab.title
                chip.textSize = 13f
                val horizontal = (14 * ctx.resources.displayMetrics.density).toInt()
                val vertical = (8 * ctx.resources.displayMetrics.density).toInt()
                chip.setPadding(horizontal, vertical, horizontal, vertical)
                chip.gravity = Gravity.CENTER
                val selectedChip = index == selected
                chip.setTypeface(Typeface.DEFAULT, if (selectedChip) Typeface.BOLD else Typeface.NORMAL)
                chip.setTextColor(if (selectedChip) MusicTheme.BG else MusicTheme.MUTED)
                chip.background = MusicTheme.ripple(
                    ctx,
                    MusicTheme.rounded(
                        ctx,
                        if (selectedChip) MusicTheme.ACCENT else MusicTheme.SURFACE_2,
                        18,
                    )
                )
                chip.setOnClickListener { onSelected(index) }
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(12, 8, 0, 8)
                chip.layoutParams = lp
                inner.addView(chip)
            }
        }
    }

    private class HeaderVH(view: View) : VH(view) {
        val title: TextView = view.findViewById(ID_TITLE)
        val subtitle: TextView = view.findViewById(ID_SUB)
        val more: TextView = view.findViewById(ID_MORE)
    }

    private class CardsVH(view: View) : VH(view) {
        class Cells {
            lateinit var container: LinearLayout
            lateinit var left: LinearLayout
            lateinit var right: LinearLayout
        }

        val cells = Cells().apply {
            container = itemView as LinearLayout
            left = container.getChildAt(0) as LinearLayout
            right = container.getChildAt(1) as LinearLayout
        }
    }

    private class TrackVH(view: View) : VH(view) {
        val number: TextView = view.findViewById(ID_NUMBER)
        val title: TextView = view.findViewById(ID_TITLE)
        val artists: TextView = view.findViewById(ID_ARTISTS)
        val duration: TextView = view.findViewById(ID_DURATION)
    }

    private class InfoVH(view: View) : VH(view) {
        val text: TextView = view as TextView
    }

    private companion object {
        const val ID_TITLE = 0x01
        const val ID_SUB = 0x02
        const val ID_IMAGE = 0x03
        const val ID_MORE = 0x04
        const val ID_NUMBER = 0x05
        const val ID_ARTISTS = 0x06
        const val ID_DURATION = 0x07
    }
}