package com.kasidwala.kasidtv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.kasidwala.kasidtv.R
import com.kasidwala.kasidtv.data.ImageLoader

/** Horizontal poster cards with D-pad focus zoom. */
class CardAdapter(
    private val onClick: (CardItem) -> Unit,
    private val onLoadMore: (() -> Unit)? = null,
    private val onLongClick: ((CardItem) -> Unit)? = null,
) : RecyclerView.Adapter<CardAdapter.VH>() {

    val items = mutableListOf<CardItem>()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val poster: ImageView = v.findViewById(R.id.ivPoster)
        val title: TextView = v.findViewById(R.id.tvCardTitle)
        val meta: TextView = v.findViewById(R.id.tvCardMeta)
        val pb: ProgressBar = v.findViewById(R.id.pbCard)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false)
        v.setOnFocusChangeListener { vv, has ->
            vv.animate().cancel()
            val s = if (has) 1.07f else 1f
            vv.animate().scaleX(s).scaleY(s).setDuration(120).start()
        }
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        h.title.text = item.title
        if (item.meta.isNullOrEmpty()) h.meta.visibility = View.GONE
        else {
            h.meta.visibility = View.VISIBLE
            h.meta.text = item.meta
        }
        ImageLoader.load(h.poster, item.posterUrl)
        if (item.progress in 0.02f..0.98f) {
            h.pb.visibility = View.VISIBLE
            h.pb.progress = (item.progress * 100).toInt()
        } else {
            h.pb.visibility = View.GONE
        }
        h.itemView.setOnClickListener { onClick(item) }
        h.itemView.setOnLongClickListener {
            if (item.kind == CardKind.HISTORY) {
                onLongClick?.invoke(item)
                true
            } else false
        }
        if (pos >= items.size - 6 && items.size >= 10) onLoadMore?.invoke()
    }

    override fun getItemCount() = items.size

    fun addAll(list: List<CardItem>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }
}
