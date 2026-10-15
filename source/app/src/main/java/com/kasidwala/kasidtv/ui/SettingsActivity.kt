package com.kasidwala.kasidtv.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.kasidwala.kasidtv.R
import com.kasidwala.kasidtv.data.DbHelper
import com.kasidwala.kasidtv.data.ImageLoader
import com.kasidwala.kasidtv.data.Prefs
import com.kasidwala.kasidtv.stalker.StalkerClient
import kotlin.concurrent.thread

/** Portal credentials, OMDb key, connection test, cache/history clearing. */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val etPortal = findViewById<EditText>(R.id.etPortal)
        val etMac = findViewById<EditText>(R.id.etMac)
        val etOmdb = findViewById<EditText>(R.id.etOmdb)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        etPortal.setText(Prefs.getPortalUrl(this))
        etMac.setText(Prefs.getMac(this))
        etOmdb.setText(Prefs.getOmdbKey(this))

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            Prefs.setPortalUrl(this, etPortal.text.toString())
            Prefs.setMac(this, etMac.text.toString())
            Prefs.setOmdbKey(this, etOmdb.text.toString())
            Prefs.setSetupDone(this, true)
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<Button>(R.id.btnTest).setOnClickListener {
            val portal = etPortal.text.toString().trim().trimEnd('/')
            val mac = etMac.text.toString().trim().uppercase()
            tvStatus.text = "Testing…"
            thread {
                val client = StalkerClient(portal, mac)
                val ok = client.ensureAuth()
                var detail = ""
                if (ok) {
                    val vod = client.getVodCategories().size
                    val series = client.getSeriesCategories().size
                    detail = "Connection OK — $vod movie categories, $series series categories."
                }
                runOnUiThread {
                    tvStatus.text = if (ok) detail else "Connection failed — check URL and MAC."
                }
            }
        }

        findViewById<Button>(R.id.btnClearHistory).setOnClickListener {
            thread {
                DbHelper.get(this).clearHistory()
                runOnUiThread {
                    Toast.makeText(this, "Watch history cleared", Toast.LENGTH_SHORT).show()
                }
            }
        }

        findViewById<Button>(R.id.btnClearCache).setOnClickListener {
            thread {
                DbHelper.get(this).clearOmdbCache()
                runOnUiThread {
                    Toast.makeText(this, "Metadata cache cleared", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
