package com.kasidwala.kasidtv.data

import android.content.Context

/** App settings: Stalker portal credentials and the OMDb key. */
object Prefs {
    private const val NAME = "kasidtv"

    private fun p(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getPortalUrl(c: Context): String = p(c).getString("portal", "").orEmpty()
    fun setPortalUrl(c: Context, v: String) = p(c).edit().putString("portal", v.trim()).apply()

    fun getMac(c: Context): String = p(c).getString("mac", "").orEmpty()
    fun setMac(c: Context, v: String) =
        p(c).edit().putString("mac", v.trim().uppercase()).apply()

    fun getOmdbKey(c: Context): String = p(c).getString("omdb", "").orEmpty()
    fun setOmdbKey(c: Context, v: String) = p(c).edit().putString("omdb", v.trim()).apply()

    fun isSetupDone(c: Context): Boolean = p(c).getBoolean("setup_done", false)
    fun setSetupDone(c: Context, v: Boolean) = p(c).edit().putBoolean("setup_done", v).apply()

    /** Random locally-administered MAC in the Infomir OUI range. */
    fun randomMac(): String {
        val r = java.util.Random()
        return "00:1A:79:%02X:%02X:%02X".format(r.nextInt(256), r.nextInt(256), r.nextInt(256))
    }
}
