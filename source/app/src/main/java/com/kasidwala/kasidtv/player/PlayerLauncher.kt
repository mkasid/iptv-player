package com.kasidwala.kasidtv.player

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Launches the built-in player (AutoSub playback engine: ExoPlayer +
 * automatic OpenSubtitles subtitles, resume by title).
 * The player reports position back into the history DB via [histKey].
 */
object PlayerLauncher {
    fun play(ctx: Context, url: String, title: String, histKey: String?) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setClass(ctx, PlayerActivity::class.java)
            putExtra("title", title)
            if (histKey != null) putExtra("hist_key", histKey)
        }
        ctx.startActivity(i)
    }
}
