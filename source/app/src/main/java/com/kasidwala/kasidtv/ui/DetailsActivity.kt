package com.kasidwala.kasidtv.ui

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kasidwala.kasidtv.R
import com.kasidwala.kasidtv.data.DbHelper
import com.kasidwala.kasidtv.data.HistoryEntry
import com.kasidwala.kasidtv.data.ImageLoader
import com.kasidwala.kasidtv.data.OmdbClient
import com.kasidwala.kasidtv.data.OmdbMeta
import com.kasidwala.kasidtv.data.Prefs
import com.kasidwala.kasidtv.player.PlayerLauncher
import com.kasidwala.kasidtv.stalker.Episode
import com.kasidwala.kasidtv.stalker.Season
import com.kasidwala.kasidtv.stalker.StalkerClient
import kotlin.concurrent.thread

/** Movie / series details: metadata, Play/Resume, seasons + episodes. */
class DetailsActivity : Activity() {

    private lateinit var client: StalkerClient
    private var kind = "movie"
    private var id = ""
    private var title = ""
    private var poster: String? = null
    private var cmd: String? = null

    private var seasons: List<Season> = emptyList()
    private var episodes: List<Episode> = emptyList()
    private var curSeason: Season? = null
    private lateinit var seasonAdapter: SeasonAdapter
    private lateinit var episodeAdapter: EpisodeAdapter

    // Deep link into an episode (from Continue Watching).
    private var targetSeasonId: String? = null
    private var targetEpisodeId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)

        kind = intent.getStringExtra("kind") ?: "movie"
        id = intent.getStringExtra("id").orEmpty()
        title = intent.getStringExtra("title").orEmpty()
        poster = intent.getStringExtra("poster")
        cmd = intent.getStringExtra("cmd")
        targetSeasonId = intent.getStringExtra("season_id")
        targetEpisodeId = intent.getStringExtra("episode_id")
        client = StalkerClient(Prefs.getPortalUrl(this), Prefs.getMac(this))

        findViewById<TextView>(R.id.tvTitle).text = title
        ImageLoader.load(findViewById<ImageView>(R.id.ivPoster), poster)
        updateResumeLabel()

        findViewById<Button>(R.id.btnPlay).setOnClickListener { onPlayPressed() }

        val rvSeasons = findViewById<RecyclerView>(R.id.rvSeasons)
        rvSeasons.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        seasonAdapter = SeasonAdapter { s -> selectSeason(s) }
        rvSeasons.adapter = seasonAdapter

        val rvEpisodes = findViewById<RecyclerView>(R.id.rvEpisodes)
        rvEpisodes.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        episodeAdapter = EpisodeAdapter { ep -> playEpisode(ep) }
        rvEpisodes.adapter = episodeAdapter

        thread {
            if (!client.ensureAuth()) {
                toast("Could not connect to portal")
                return@thread
            }
            val meta = OmdbClient.lookup(this, title)
            runOnUiThread { applyMeta(meta) }
            if (kind == "series") {
                val ss = client.getSeasons(id)
                runOnUiThread {
                    seasons = ss
                    if (ss.isNotEmpty()) {
                        findViewById<TextView>(R.id.tvSeasonsLabel).visibility = View.VISIBLE
                        findViewById<TextView>(R.id.tvEpisodesLabel).visibility = View.VISIBLE
                        rvSeasons.visibility = View.VISIBLE
                        rvEpisodes.visibility = View.VISIBLE
                        seasonAdapter.items = ss
                        seasonAdapter.notifyDataSetChanged()
                        val target = ss.find { it.id == targetSeasonId } ?: ss[0]
                        selectSeason(target)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateResumeLabel()
        if (::episodeAdapter.isInitialized) episodeAdapter.notifyDataSetChanged()
    }

    private fun toast(msg: String) =
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    // ------------------------------------------------------------ metadata

    private fun applyMeta(meta: OmdbMeta?) {
        if (meta == null) return
        OmdbClient.metaLine(meta)?.let {
            findViewById<TextView>(R.id.tvMeta).text = it
        }
        meta.plot?.let { findViewById<TextView>(R.id.tvPlot).text = it }
        if (poster.isNullOrBlank() && !meta.poster.isNullOrBlank()
            && meta.poster.startsWith("http")
        ) {
            poster = meta.poster
            ImageLoader.load(findViewById<ImageView>(R.id.ivPoster), poster)
        }
    }

    private fun updateResumeLabel() {
        val btn = findViewById<Button>(R.id.btnPlay)
        if (kind == "movie") {
            val h = DbHelper.get(this).getHistory("m:$id")
            btn.text = if (h != null && h.durationMs > 0 && h.positionMs > 10_000) {
                getString(R.string.resume) + " " + fmtTime(h.positionMs)
            } else {
                getString(R.string.play)
            }
        } else {
            btn.text = getString(R.string.play)
        }
    }

    private fun fmtTime(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    // ------------------------------------------------------------ series

    private fun selectSeason(s: Season) {
        curSeason = s
        seasonAdapter.selectedId = s.id
        seasonAdapter.notifyDataSetChanged()
        thread {
            val eps = client.getEpisodes(id, s.id)
            runOnUiThread {
                episodes = eps
                episodeAdapter.items = eps
                episodeAdapter.notifyDataSetChanged()
            }
        }
    }

    private inner class SeasonAdapter(val onPick: (Season) -> Unit) :
        RecyclerView.Adapter<SeasonAdapter.SH>() {
        var items: List<Season> = emptyList()
        var selectedId: String? = null

        inner class SH(v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v as TextView
        }

        override fun onCreateViewHolder(p: ViewGroup, vt: Int): SH {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_season, p, false)
            return SH(v)
        }

        override fun onBindViewHolder(h: SH, pos: Int) {
            val s = items[pos]
            h.tv.text = s.name
            h.tv.setBackgroundResource(
                if (s.id == selectedId) R.drawable.btn_bg else R.drawable.btn_ghost
            )
            h.tv.setOnClickListener { onPick(s) }
        }

        override fun getItemCount() = items.size
    }

    private inner class EpisodeAdapter(val onPick: (Episode) -> Unit) :
        RecyclerView.Adapter<EpisodeAdapter.EH>() {
        var items: List<Episode> = emptyList()

        inner class EH(v: View) : RecyclerView.ViewHolder(v) {
            val num: TextView = v.findViewById(R.id.tvEpNum)
            val title: TextView = v.findViewById(R.id.tvEpTitle)
            val meta: TextView = v.findViewById(R.id.tvEpMeta)
            val pb: ProgressBar = v.findViewById(R.id.pbEp)
        }

        override fun onCreateViewHolder(p: ViewGroup, vt: Int): EH {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_episode, p, false)
            return EH(v)
        }

        override fun onBindViewHolder(h: EH, pos: Int) {
            val ep = items[pos]
            h.num.text = ep.number
            h.title.text = ep.name
            val key = "e:$id:${curSeason?.id}:${ep.id}"
            val he = DbHelper.get(this@DetailsActivity).getHistory(key)
            if (he != null && he.durationMs > 0) {
                val f = he.positionMs.toFloat() / he.durationMs
                if (f in 0.02f..0.98f) {
                    h.pb.visibility = View.VISIBLE
                    h.pb.progress = (f * 100).toInt()
                } else h.pb.visibility = View.GONE
                h.meta.text = fmtTime(he.positionMs)
            } else {
                h.pb.visibility = View.GONE
                h.meta.text = ""
            }
            h.itemView.setOnClickListener { onPick(ep) }
        }

        override fun getItemCount() = items.size
    }

    // ------------------------------------------------------------ playback

    private fun onPlayPressed() {
        if (kind == "movie") {
            resolveAndPlay(
                epCmd = cmd, seriesNum = "", playTitle = title,
                hist = HistoryEntry(
                    key = "m:$id", title = title, poster = poster, kind = "movie",
                    seriesTitle = null, seriesId = id, seasonId = null, seasonName = null,
                    episodeId = null, episodeNum = null, cmd = cmd,
                    positionMs = 0, durationMs = 0, updatedAt = System.currentTimeMillis(),
                ),
            )
        } else {
            episodes.firstOrNull()?.let { playEpisode(it) }
                ?: toast("No episodes loaded yet")
        }
    }

    private fun playEpisode(ep: Episode) {
        val season = curSeason ?: return
        val playTitle = "$title S${season.name.filter { it.isDigit() }} E${ep.number}"
        resolveAndPlay(
            epCmd = ep.cmd, seriesNum = ep.number, playTitle = playTitle,
            hist = HistoryEntry(
                key = "e:$id:${season.id}:${ep.id}", title = ep.name, poster = poster,
                kind = "episode", seriesTitle = title, seriesId = id,
                seasonId = season.id, seasonName = season.name,
                episodeId = ep.id, episodeNum = ep.number, cmd = ep.cmd,
                positionMs = 0, durationMs = 0, updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Record history, resolve the short-lived stream URL, hand to AutoSub.
     * AutoSub resumes by title, so the title must be stable per episode.
     */
    private fun resolveAndPlay(epCmd: String?, seriesNum: String, playTitle: String, hist: HistoryEntry) {
        if (epCmd.isNullOrEmpty()) {
            toast("No stream available for this item")
            return
        }
        val pb = findViewById<ProgressBar>(R.id.pbLoading)
        pb.visibility = View.VISIBLE
        DbHelper.get(this).upsertHistory(hist)
        thread {
            val url = client.createLink(epCmd, seriesNum)
            runOnUiThread {
                pb.visibility = View.GONE
                if (url.isNullOrEmpty()) toast("Could not get a stream URL")
                else PlayerLauncher.play(this, url, playTitle)
            }
        }
    }
}
