package com.kasidwala.kasidtv.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.kasidwala.kasidtv.KasidTVApp
import com.kasidwala.kasidtv.R
import com.kasidwala.kasidtv.data.Prefs
import com.kasidwala.kasidtv.player.CrashGuard
import com.kasidwala.kasidtv.stalker.StalkerClient
import kotlin.concurrent.thread

/** First-run wizard: portal URL + MAC, connection tested before entering. */
class SetupActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashGuard.install(this)
        KasidTVApp.takeCrash(this)?.let { showCrash(it) }
        if (Prefs.isSetupDone(this)) {
            goMain()
            return
        }
        setContentView(R.layout.activity_setup)

        val etPortal = findViewById<EditText>(R.id.etPortal)
        val etMac = findViewById<EditText>(R.id.etMac)
        val etOmdb = findViewById<EditText>(R.id.etOmdbSetup)
        val etOsKey = findViewById<EditText>(R.id.etOsKeySetup)
        val etOsUser = findViewById<EditText>(R.id.etOsUserSetup)
        val etOsPass = findViewById<EditText>(R.id.etOsPassSetup)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        etMac.setText(Prefs.randomMac())
        etOmdb.setText(Prefs.getOmdbKey(this))
        val osPrefs = getSharedPreferences("autosub", Context.MODE_PRIVATE)
        etOsKey.setText(osPrefs.getString("api_key", ""))
        etOsUser.setText(osPrefs.getString("os_user", ""))
        etOsPass.setText(osPrefs.getString("os_pass", ""))

        btnSave.setOnClickListener {
            val portal = etPortal.text.toString().trim().trimEnd('/')
            val mac = etMac.text.toString().trim().uppercase()
            if (portal.isEmpty() || mac.isEmpty()) {
                tvStatus.text = "Enter the portal URL and MAC address."
                return@setOnClickListener
            }
            tvStatus.text = "Testing connection…"
            btnSave.isEnabled = false
            thread {
                val ok = try {
                    StalkerClient(portal, mac).handshake()
                } catch (t: Throwable) {
                    false
                }
                runOnUiThread {
                    btnSave.isEnabled = true
                    if (ok) {
                        Prefs.setPortalUrl(this, portal)
                        Prefs.setMac(this, mac)
                        Prefs.setOmdbKey(this, etOmdb.text.toString())
                        Prefs.setSetupDone(this, true)
                        osPrefs.edit()
                            .putString("api_key", etOsKey.text.toString().trim())
                            .putString("os_user", etOsUser.text.toString().trim())
                            .putString("os_pass", etOsPass.text.toString())
                            .apply()
                        goMain()
                    } else {
                        tvStatus.text = "Could not connect — check the portal URL and MAC."
                    }
                }
            }
        }
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    /** Shows the previous run's crash (saved by KasidTVApp) so it can be reported. */
    private fun showCrash(report: String) {
        AlertDialog.Builder(this)
            .setTitle("The app crashed last time")
            .setMessage(report)
            .setPositiveButton("Close", null)
            .show()
    }
}
