package io.github.plexfilas

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.work.ExistingPeriodicWorkPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }
}

class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: Prefs

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        prefs = Prefs(requireContext())

        findPreference<EditTextPreference>(Prefs.K_URL)?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            it.hint = "http://192.168.1.10:32400"
        }
        findPreference<EditTextPreference>(Prefs.K_TOKEN)?.apply {
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                if (p.text.isNullOrBlank()) getString(R.string.token_empty) else getString(R.string.token_set)
            }
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            }
        }

        click("link") { startActivity(Intent(requireContext(), LinkActivity::class.java)) }
        click("test") { testConnection(it) }
        click("sync_now") { syncNow(it) }
        click("make_visible") { requestVisible() }
    }

    private fun click(key: String, action: (Preference) -> Unit) {
        findPreference<Preference>(key)?.setOnPreferenceClickListener { action(it); true }
    }

    override fun onResume() {
        super.onResume()
        // La pantalla de vinculación puede haber rellenado URL y token mientras tanto
        findPreference<EditTextPreference>(Prefs.K_URL)?.text = prefs.sp.getString(Prefs.K_URL, "")
        findPreference<EditTextPreference>(Prefs.K_TOKEN)?.text = prefs.sp.getString(Prefs.K_TOKEN, "")
        prefs.sp.registerOnSharedPreferenceChangeListener(this)
        refreshStatus()
    }

    override fun onPause() {
        prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        when (key) {
            in Prefs.ROW_KEYS -> if (prefs.isConfigured()) Scheduler.syncSoon(requireContext())
            Prefs.K_INTERVAL -> Scheduler.schedule(requireContext(), ExistingPeriodicWorkPolicy.UPDATE)
            Prefs.K_LAST_SYNC -> refreshStatus()
        }
    }

    private fun refreshStatus() {
        findPreference<Preference>("sync_now")?.summary =
            prefs.lastSync.ifBlank { getString(R.string.never_synced) }
    }

    private fun testConnection(pref: Preference) {
        if (!prefs.isConfigured()) {
            pref.summary = getString(R.string.missing_config)
            return
        }
        pref.summary = getString(R.string.testing)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val c = PlexClient(prefs.serverUrl, prefs.token, prefs.clientId, timeoutMs = 6000)
                    c.identity()
                    val s = c.sections()
                    "OK · ${s.count { it.type == "movie" }} bibliotecas de películas, ${s.count { it.type == "show" }} de series"
                }
            }
            pref.summary = result.getOrElse { "Error: ${it.message}" }
        }
    }

    private fun syncNow(pref: Preference) {
        if (!prefs.isConfigured()) {
            pref.summary = getString(R.string.missing_config)
            return
        }
        pref.summary = getString(R.string.syncing)
        viewLifecycleOwner.lifecycleScope.launch {
            val msg = runCatching { SyncEngine.run(requireContext()) }
                .fold({ getString(R.string.sync_ok, it) }, { "Error: ${it.message}" })
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            refreshStatus()
        }
    }

    /** Pide al sistema que marque las filas como visibles (algunos launchers solo muestran esas). */
    private fun requestVisible() {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val ids = withContext(Dispatchers.IO) {
                runCatching {
                    ChannelPublisher(ctx, PlexClient(prefs.serverUrl, prefs.token, prefs.clientId)).nonBrowsableChannelIds()
                }.getOrDefault(emptyList())
            }
            if (ids.isEmpty()) {
                Toast.makeText(ctx, R.string.all_visible, Toast.LENGTH_LONG).show()
                return@launch
            }
            ids.forEach { id ->
                runCatching { TvContractCompat.requestChannelBrowsable(ctx, id) }
                try {
                    startActivity(
                        Intent(TvContractCompat.ACTION_REQUEST_CHANNEL_BROWSABLE)
                            .putExtra(TvContractCompat.EXTRA_CHANNEL_ID, id)
                    )
                } catch (_: ActivityNotFoundException) {
                } catch (_: SecurityException) {
                }
            }
            Toast.makeText(ctx, getString(R.string.visible_requested, ids.size), Toast.LENGTH_LONG).show()
        }
    }
}
