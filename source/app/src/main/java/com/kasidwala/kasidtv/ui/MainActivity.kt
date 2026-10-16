package com.kasidwala.kasidtv.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kasidwala.kasidtv.KasidTVApp
import com.kasidwala.kasidtv.R
import com.kasidwala.kasidtv.data.DbHelper
import com.kasidwala.kasidtv.data.HistoryEntry
import com.kasidwala.kasidtv.data.OmdbClient
import com.kasidwala.kasidtv.data.Prefs
import com.kasidwala.kasidtv.player.CrashGuard
import com.kasidwala.kasidtv.stalker.StalkerClient
import com.kasidwala.kasidtv.stalker.VodPage
import kotlin.concurrent.thread

/** Home: Movies / Series / Continue Watching tabs, each with its own rows. */
class MainActivity : Activity() {

    private class RowState(
        val header: String,
        val adapter: CardAdapter,
        val categoryId: String,
        val seriesMode: Boolean,
        val useSeriesType: Boolean,
        var nextP: Int = -1, // -1 = pagination base not yet detected
        var exhausted: Boolean = false,
        var loading: Boolean = false,
    )

    private lateinit var rows: RowAdapter
    private lateinit var tvEmpty: TextView
    private lateinit var btnTabMovies: Button
    private lateinit var btnTabSeries: Button
    private lateinit var btnTabHistory: Button

    private val movieStates = mutableListOf<RowState>()
    private val seriesStates = mutableListOf<RowState>()
    private val historyStates = mutableListOf<RowState>()
    private val enrichedKeys = mutableSetOf<String>()
    private var settingsSig = ""
    private var curTab = 0 // 0 = movies, 1 = series, 2 = history

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Note: no CrashGuard here — see SetupActivity.
        KasidTVApp.takeCrash(this)?.let { report ->
            AlertDialog.Builder(this)
                .setTitle("The app crashed last time")
                .setMessage(report)
                .setPositiveButton("Close", null)
                .show()
        }
        if (!Prefs.isSetupDone(this)) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btnSearch).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnTabMovies = findViewById(R.id.btnTabMovies)
        btnTabSeries = findViewById(R.id.btnTabSeries)
        btnTabHistory = findViewById(R.id.btnTabHistory)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnTabMovies.setOnClickListener { showTab(0) }
        btnTabSeries.setOnClickListener { showTab(1) }
        btnTabHistory.setOnClickListener { showTab(2) }

        val rv = findViewById<RecyclerView>(R.id.rvRows)
        rv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        rows = RowAdapter()
        rv.adapter = rows
        settingsSig = settingsSig()
        loadAll()
    }

    override fun onResume() {
        super.onResume()
        if (!Prefs.isSetupDone(this)) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        if (settingsSig() != settingsSig) {
            settingsSig = settingsSig()
            loadAll()
        } else if (curTab == 2) {
            refreshHistoryRow()
        }
    }

    private fun settingsSig() = Prefs.getPortalUrl(this) + "|" + Prefs.getMac(this)

    private fun toast(msg: String) =
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    private fun newClient() = StalkerClient(Prefs.getPortalUrl(this), Prefs.getMac(this))

    // ------------------------------------------------------------ tabs

    private fun showTab(tab: Int) {
        curTab = tab
        val states = when (tab) {
            0 -> movieStates
            1 -> seriesStates
            else -> historyStates
        }
        rows.rows.clear()
        rows.rows.addAll(states.map { RowAdapter.RowData(it.header, it.adapter) })
        rows.notifyDataSetChanged()
        tvEmpty.visibility = if (states.isEmpty()) View.VISIBLE else View.GONE
        tvEmpty.text = when (tab) {
            2 -> "Nothing watched yet — your history will appear here"
            else -> "No content found"
        }
        styleTabs()
        if (tab == 2) refreshHistoryRow()
    }

    private fun styleTabs() {
        styleTab(btnTabMovies, curTab == 0)
        styleTab(btnTabSeries, curTab == 1)
        styleTab(btnTabHistory, curTab == 2)
    }

    private fun styleTab(btn: Button, active: Boolean) {
        btn.setBackgroundResource(if (active) R.drawable.btn_bg else R.drawable.btn_ghost)
        btn.setTextColor(
            ContextCompat.getColor(
                this, if (active) R.color.on_accent else R.color.text_primary
            )
        )
    }

    // ------------------------------------------------------------ loading

    private fun loadAll() {
        movieStates.clear()
        seriesStates.clear()
        historyStates.clear()
        rows.rows.clear()
        rows.notifyDataSetChanged()
        tvEmpty.visibility = View.VISIBLE
        tvEmpty.text = getString(R.string.loading)
        thread {
            try {
                loadAllInner()
            } catch (t: Throwable) {
                toast("Load failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /**
     * Publish a finished row to the UI immediately, so rows appear
     * progressively instead of all at the end (no long blank screen on
     * slow portals). All state mutation happens on the UI thread.
     */
    private fun publishRow(st: RowState, tab: Int) {
        runOnUiThread {
            val states = if (tab == 0) movieStates else seriesStates
            states.add(st)
            if (curTab == tab) {
                rows.rows.add(RowAdapter.RowData(st.header, st.adapter))
                rows.notifyItemInserted(rows.rows.size - 1)
                tvEmpty.visibility = View.GONE
            }
        }
    }

    private fun loadAllInner() {
            val client = newClient()
            if (!client.ensureAuth()) {
                toast("Could not connect to portal — check Settings")
                return
            }

            // Movies by category (series entries filtered out).
            val vodCats = client.getVodCategories()
            for (cat in vodCats) {
                val st = newRow(cat.title, cat.id, seriesMode = false, useSeriesType = false)
                fillRow(client, st, minItems = 12)
                if (st.adapter.items.isNotEmpty()) publishRow(st, 0)
            }

            // Series: dedicated categories when the portal has them,
            // otherwise the series entries inside the VOD categories.
            val seriesCats = client.getSeriesCategories()
            if (seriesCats.isNotEmpty()) {
                for (cat in seriesCats) {
                    val st = newRow(cat.title, cat.id, seriesMode = false, useSeriesType = true)
                    fillRow(client, st, minItems = 12)
                    if (st.adapter.items.isNotEmpty()) publishRow(st, 1)
                }
            } else {
                for (cat in vodCats) {
                    val st = newRow(cat.title, cat.id, seriesMode = true, useSeriesType = false)
                    fillRow(client, st, minItems = 12)
                    if (st.adapter.items.isNotEmpty()) publishRow(st, 1)
                }
            }

            // Continue Watching (local history).
            val hist = DbHelper.get(this).recentHistory(30)
            if (hist.isNotEmpty()) {
                val st = newRow(
                    getString(R.string.continue_watching), "", false, false
                )
                st.adapter.items.addAll(hist.map { historyCard(it) })
                st.exhausted = true
                runOnUiThread {
                    historyStates.add(st)
                    btnTabHistory.visibility = View.VISIBLE
                }
            }

            runOnUiThread {
                if (curTab == 2 && historyStates.isEmpty()) curTab = 0
                // Rebuild the current tab from everything published so far
                // (covers rows that arrived while the user was on another tab).
                showTab(curTab)
                if (movieStates.isEmpty() && seriesStates.isEmpty()) {
                    toast("No content found — check portal settings")
                }
            }
    }

    /** Create a row whose adapter loads more pages on scroll. */
    private fun newRow(
        header: String, categoryId: String, seriesMode: Boolean, useSeriesType: Boolean,
    ): RowState {
        val holder = arrayOfNulls<RowState>(1)
        val adapter = CardAdapter(
            onClick = ::onCardClick,
            onLoadMore = { holder[0]?.let { loadMore(it) } },
            onLongClick = ::onCardLongClick,
        )
        return RowState(header, adapter, categoryId, seriesMode, useSeriesType)
            .also { holder[0] = it }
    }

    /** Long-press a Continue Watching card to remove it from history. */
    private fun onCardLongClick(c: CardItem) {
        val key = c.histKey ?: return
        AlertDialog.Builder(this)
            .setTitle(c.title)
            .setMessage(getString(R.string.remove_from_history_q))
            .setPositiveButton(getString(R.string.remove)) { _, _ ->
                thread {
                    DbHelper.get(this).deleteHistory(key)
                    runOnUiThread { refreshHistoryRow() }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * Fill [st] with at least [minItems] cards (first load) or one more page
     * (scroll load-more). Must run on a background thread.
     *
     * The page loop is bounded: if a category's items are all filtered out
     * (e.g. only series in a movies row) or the portal ignores paging, we
     * stop after a few pages instead of requesting forever (blank-screen hang).
     */
    private fun fillRow(client: StalkerClient, st: RowState, minItems: Int = 0) {
        if (st.loading || st.exhausted) return
        st.loading = true
        try {
            val all = mutableListOf<CardItem>()
            var pages = 0
            val maxPages = if (minItems == 0) 1 else 6
            while (!st.exhausted && (minItems == 0 || all.size < minItems) && pages < maxPages) {
                pages++
                var p = if (st.nextP < 0) 1 else st.nextP
                var pg = fetchPage(client, st, p)
                if (st.nextP < 0) {
                    // Detect 0-based vs 1-based pagination.
                    if (pg.items.isEmpty() && pg.totalItems > 0) {
                        val z = fetchPage(client, st, 0)
                        if (z.items.isNotEmpty()) {
                            pg = z
                            p = 0
                        }
                    }
                    st.nextP = p + 1
                } else {
                    st.nextP = p + 1
                }
                if (pg.items.isEmpty()) {
                    st.exhausted = true
                    break
                }
                all += pg.items
                    .filter { it.id.isNotEmpty() && it.isSeries == st.seriesMode }
                    .map { vodCard(it, st.seriesMode) }
                if (minItems == 0) break // load-more fetches a single page
            }
            if (all.isNotEmpty()) {
                runOnUiThread {
                    st.adapter.addAll(all)
                    enrichCards(st.adapter)
                }
            }
        } finally {
            st.loading = false
        }
    }

    private fun fetchPage(client: StalkerClient, st: RowState, p: Int): VodPage =
        if (st.useSeriesType) client.getSeriesList(st.categoryId, p)
        else client.getVodList(st.categoryId, p)

    private fun loadMore(st: RowState) {
        thread {
            try {
                val client = newClient()
                if (!client.ensureAuth()) return@thread
                fillRow(client, st)
            } catch (t: Throwable) {
                // scroll load-more must never crash the app
            }
        }
    }

    // ------------------------------------------------------------ history

    private fun historyCard(h: HistoryEntry): CardItem {
        val prog = if (h.durationMs > 0) h.positionMs.toFloat() / h.durationMs else -1f
        return if (h.kind == "episode") {
            CardItem(
                key = "h:${h.key}", title = "${h.seriesTitle} · ${h.title}",
                posterUrl = h.poster, meta = h.seasonName ?: "Episode",
                stalkerId = h.seriesId.orEmpty(), kind = CardKind.HISTORY, cmd = null,
                progress = prog, histKind = "episode", histKey = h.key,
                seriesTitle = h.seriesTitle, seasonId = h.seasonId, seasonName = h.seasonName,
                episodeId = h.episodeId, episodeNum = h.episodeNum, episodeCmd = h.cmd,
            )
        } else {
            CardItem(
                key = "h:${h.key}", title = h.title, posterUrl = h.poster, meta = "Movie",
                stalkerId = h.seriesId.orEmpty(), kind = CardKind.HISTORY, cmd = h.cmd,
                progress = prog, histKind = "movie", histKey = h.key,
            )
        }
    }

    private fun refreshHistoryRow() {
        thread {
            val hist = DbHelper.get(this).recentHistory(30)
            runOnUiThread {
                historyStates.clear()
                if (hist.isNotEmpty()) {
                    val st = newRow(getString(R.string.continue_watching), "", false, false)
                    st.adapter.items.addAll(hist.map { historyCard(it) })
                    st.exhausted = true
                    historyStates.add(st)
                }
                btnTabHistory.visibility =
                    if (historyStates.isNotEmpty()) View.VISIBLE else View.GONE
                if (curTab == 2) {
                    if (historyStates.isEmpty()) showTab(0) else showTab(2)
                }
            }
        }
    }

    // ------------------------------------------------------------ metadata

    /** Lazy OMDb enrichment for cards lacking a rating line. */
    private fun enrichCards(adapter: CardAdapter) {
        thread {
            for (c in adapter.items.toList()) {
                val already = synchronized(enrichedKeys) { !enrichedKeys.add(c.key) }
                if (already) continue
                if (!c.meta.isNullOrEmpty() && c.meta!!.contains("★")) continue
                val meta = OmdbClient.lookup(this, c.title, background = true) ?: continue
                val line = OmdbClient.metaLine(meta)
                var changed = false
                if (line != null) {
                    c.meta = line
                    changed = true
                }
                if (c.posterUrl.isNullOrBlank() && !meta.poster.isNullOrBlank()
                    && meta.poster.startsWith("http")
                ) {
                    c.posterUrl = meta.poster
                    changed = true
                }
                if (!changed) continue
                runOnUiThread {
                    val i = adapter.items.indexOfFirst { it.key == c.key }
                    if (i >= 0) adapter.notifyItemChanged(i)
                }
            }
        }
    }

    // ------------------------------------------------------------ navigation

    private fun onCardClick(c: CardItem) {
        val i = Intent(this, DetailsActivity::class.java)
        when (c.kind) {
            CardKind.MOVIE -> {
                i.putExtra("kind", "movie")
                i.putExtra("id", c.stalkerId)
                i.putExtra("title", c.title)
                i.putExtra("poster", c.posterUrl)
                i.putExtra("cmd", c.cmd)
            }
            CardKind.SERIES -> {
                i.putExtra("kind", "series")
                i.putExtra("id", c.stalkerId)
                i.putExtra("title", c.title)
                i.putExtra("poster", c.posterUrl)
            }
            CardKind.HISTORY -> {
                if (c.histKind == "episode") {
                    i.putExtra("kind", "series")
                    i.putExtra("id", c.stalkerId)
                    i.putExtra("title", c.seriesTitle)
                    i.putExtra("poster", c.posterUrl)
                    i.putExtra("season_id", c.seasonId)
                    i.putExtra("episode_id", c.episodeId)
                } else {
                    i.putExtra("kind", "movie")
                    i.putExtra("id", c.stalkerId)
                    i.putExtra("title", c.title)
                    i.putExtra("poster", c.posterUrl)
                    i.putExtra("cmd", c.cmd)
                }
            }
        }
        startActivity(i)
    }
}
