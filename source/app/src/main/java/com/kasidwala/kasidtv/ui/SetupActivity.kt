package com.kasidwala.kasidtv.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.kasidwala.kasidtv.R
import com.kasidwala.kasidtv.data.Prefs
import com.kasidwala.kasidtv.stalker.StalkerClient
import kotlin.concurrent.thread

/** First-run wizard: portal URL + MAC, connection tested before entering. */
class SetupActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.isSetupDone(this)) {
            goMain()
            return
        }
        setContentView(R.layout.activity_setup)

        val etPortal = findViewById<EditText>(R.id.etPortal)
        val etMac = findViewById<EditText>(R.id.etMac)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        etMac.setText(Prefs.randomMac())

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
                val ok = StalkerClient(portal, mac).handshake()
                runOnUiThread {
                    btnSave.isEnabled = true
                    if (ok) {
                        Prefs.setPortalUrl(this, portal)
                        Prefs.setMac(this, mac)
                        Prefs.setSetupDone(this, true)
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
}
