package com.kasidwala.kasidtv.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kasidwala.kasidtv.R
import com.kasidwala.kasidtv.data.ImageLoader
import com.kasidwala.kasidtv.data.Prefs
import com.kasidwala.kasidtv.stalker.StalkerClient
import kotlin.concurrent.thread

/** Global search: Movies and Series in their own groupings. */
class SearchActivity : Activity() {

    private lateinit var adapter: SearchAdapter
    private lateinit var tvStatus: TextView
    private var searchGen = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        tvStatus = findViewById(R.id.tvStatus)
        val rv = findViewById<RecyclerView>(R.id.rvResults)
        val glm = GridLayoutManager(this, 5)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(pos: Int) =
                if (adapter.getItemViewType(pos) == SearchAdapter.T_HEADER) 5 else 1
        }
        rv.layoutManager = glm
        adapter = SearchAdapter(::openDetails)
        rv.adapter = adapter

        findViewById<EditText>(R.id.etQuery).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s.toString().trim()
                val gen = ++searchGen
                thread {
                    Thread.sleep(700)
                    if (gen != searchGen) return@thread
                    doSearch(q)
                }
            }
        })
    }

    private fun doSearch(q: String) {
        if (q.length < 3) {
            runOnUiThread {
                adapter.items.clear()
                adapter.notifyDataSetChanged()
                tvStatus.visibility = View.GONE
            }
            return
        }
        status("Searching…")
        val client = StalkerClient(Prefs.getPortalUrl(this), Prefs.getMac(this))
        if (!client.ensureAuth()) {
            status("Could not connect to portal")
            return
        }
        val seen = mutableSetOf<String>()
        val movies = ArrayList<CardItem>()
        val series = ArrayList<CardItem>()
        for (it in client.getVodList("", 1, q).items) {
            if (it.id.isEmpty()) continue
            val c = vodCard(it, it.isSeries)
            if (seen.add(c.key)) (if (it.isSeries) series else movies).add(c)
        }
        for (it in client.getSeriesList("", 1, q).items) {
            if (it.id.isEmpty()) continue
            val c = vodCard(it, true)
            if (seen.add(c.key)) series.add(c)
        }
        val items = ArrayList<Any>()
        if (movies.isNotEmpty()) {
            items.add(getString(R.string.movies))
            items.addAll(movies)
        }
        if (series.isNotEmpty()) {
            items.add(getString(R.string.series))
            items.addAll(series)
        }
        runOnUiThread {
            adapter.items.clear()
            adapter.items.addAll(items)
            adapter.notifyDataSetChanged()
            if (items.isEmpty()) status("No results for \"$q\"") else status(null)
        }
    }

    private fun status(msg: String?) {
        runOnUiThread {
            if (msg == null) tvStatus.visibility = View.GONE
            else {
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = msg
            }
        }
    }

    private fun openDetails(c: CardItem) {
        val i = Intent(this, DetailsActivity::class.java).apply {
            putExtra("kind", if (c.kind == CardKind.SERIES) "series" else "movie")
            putExtra("id", c.stalkerId)
            putExtra("title", c.title)
            putExtra("poster", c.posterUrl)
            putExtra("cmd", c.cmd)
        }
        startActivity(i)
    }

    // ------------------------------------------------------------ adapter

    private class SearchAdapter(val onClick: (CardItem) -> Unit) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            const val T_HEADER = 0
            const val T_CARD = 1
        }

        val items = mutableListOf<Any>() // String header or CardItem

        class HH(v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v.findViewById(R.id.tvSectionTitle)
        }

        class CH(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.ivPoster)
            val title: TextView = v.findViewById(R.id.tvCardTitle)
            val meta: TextView = v.findViewById(R.id.tvCardMeta)
        }

        override fun getItemViewType(pos: Int) =
            if (items[pos] is String) T_HEADER else T_CARD

        override fun onCreateViewHolder(p: ViewGroup, vt: Int): RecyclerView.ViewHolder {
            return if (vt == T_HEADER) {
                HH(LayoutInflater.from(p.context).inflate(R.layout.item_section_header, p, false))
            } else {
                val v = LayoutInflater.from(p.context).inflate(R.layout.item_card, p, false)
                v.setOnFocusChangeListener { vv, has ->
                    vv.animate().cancel()
                    val s = if (has) 1.07f else 1f
                    vv.animate().scaleX(s).scaleY(s).setDuration(120).start()
                }
                CH(v)
            }
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val o = items[pos]
            if (h is HH) {
                h.tv.text = o as String
            } else if (h is CH) {
                val c = o as CardItem
                h.title.text = c.title
                if (c.meta.isNullOrEmpty()) h.meta.visibility = View.GONE
                else {
                    h.meta.visibility = View.VISIBLE
                    h.meta.text = c.meta
                }
                ImageLoader.load(h.poster, c.posterUrl)
                h.itemView.setOnClickListener { onClick(c) }
            }
        }

        override fun getItemCount() = items.size
    }
}
