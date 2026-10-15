package com.kasidwala.kasidtv.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Hands playback to AutoSub Player (external player with auto subtitles).
 * AutoSub keys resume by title, so the title must be stable.
 */
object PlayerLauncher {
    private const val PKG = "com.kasidwala.autosubplayer"
    private const val PLAYER = "com.kasidwala.autosubplayer.PlayerActivity"

    /** Returns false when AutoSub Player is not installed. */
    fun play(ctx: Context, url: String, title: String): Boolean {
        return try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                putExtra("title", title)
                setClassName(PKG, PLAYER)
            }
            ctx.startActivity(i)
            true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                ctx, "Install AutoSub Player to play videos", Toast.LENGTH_LONG
            ).show()
            false
        }
    }
}
