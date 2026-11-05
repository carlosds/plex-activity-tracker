package io.github.plexactivitytracker

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Vinculación sin teclear el token: muestra un código de 4 caracteres para introducir
 * en plex.tv/link desde el móvil o el ordenador, y localiza tu servidor en la LAN.
 */
class LinkActivity : AppCompatActivity() {

    private lateinit var code: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(48)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        root.addView(label(getString(R.string.link_instructions), 22f))
        code = label("····", 72f).apply { setTextColor(getColor(R.color.accent)); letterSpacing = 0.3f }
        root.addView(code)
        status = label(getString(R.string.link_requesting), 18f)
        root.addView(status)
        setContentView(root)

        lifecycleScope.launch { runFlow() }
    }

    private suspend fun runFlow() {
        val prefs = Prefs(this)
        val cid = prefs.clientId
        try {
            val pin = withContext(Dispatchers.IO) { PlexAccount.createPin(cid) }
            code.text = pin.code
            status.text = getString(R.string.link_waiting)

            var token: String? = null
            val deadline = System.currentTimeMillis() + 10 * 60_000L
            while (token == null && System.currentTimeMillis() < deadline) {
                delay(2000)
                token = withContext(Dispatchers.IO) { runCatching { PlexAccount.checkPin(cid, pin.id) }.getOrNull() }
            }
            if (token == null) {
                status.text = getString(R.string.link_expired)
                return
            }

            status.text = getString(R.string.link_searching)
            val servers = withContext(Dispatchers.IO) { PlexAccount.servers(cid, token) }
            if (servers.isEmpty()) {
                status.text = getString(R.string.link_no_servers)
                return
            }
            var chosen: PlexAccount.Server? = null
            var url: String? = null
            for (s in servers) {
                url = withContext(Dispatchers.IO) { PlexAccount.findWorkingUrl(s, cid) }
                if (url != null) { chosen = s; break }
            }
            if (chosen == null || url == null) {
                // Guardamos el token del primero para que solo falte escribir la URL a mano
                prefs.token = servers.first().accessToken
                status.text = getString(R.string.link_no_connection, servers.joinToString { it.name })
                return
            }

            prefs.serverUrl = url
            prefs.token = chosen.accessToken
            status.text = getString(R.string.link_connected, chosen.name, url)
            val summary = runCatching { SyncEngine.run(this) }
            status.text = summary.fold(
                { getString(R.string.link_done, it) },
                { "Error: ${it.message}" }
            )
            if (summary.isSuccess) {
                delay(3000)
                finish()
            }
        } catch (e: Exception) {
            status.text = "Error: ${e.message}"
        }
    }

    private fun label(s: String, sp: Float) = TextView(this).apply {
        text = s
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setPadding(0, dp(12), 0, dp(12))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
