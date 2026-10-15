package com.kasidwala.kasidtv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kasidwala.kasidtv.R

/** Vertical list of rows; each row is a header plus a horizontal card rail. */
class RowAdapter : RecyclerView.Adapter<RowAdapter.RVH>() {

    data class RowData(val header: String, val adapter: CardAdapter)

    val rows = mutableListOf<RowData>()

    class RVH(v: View) : RecyclerView.ViewHolder(v) {
        val tvHeader: TextView = v.findViewById(R.id.tvRowTitle)
        val rvCards: RecyclerView = v.findViewById(R.id.rvCards)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false)
        return RVH(v)
    }

    override fun onBindViewHolder(h: RVH, pos: Int) {
        val r = rows[pos]
        h.tvHeader.text = r.header
        if (h.rvCards.adapter !== r.adapter) {
            h.rvCards.layoutManager =
                LinearLayoutManager(h.itemView.context, LinearLayoutManager.HORIZONTAL, false)
            h.rvCards.adapter = r.adapter
            h.rvCards.setHasFixedSize(true)
        }
    }

    override fun getItemCount() = rows.size
}
