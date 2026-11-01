package io.github.plexactivitytracker

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.json.JSONObject
import java.util.UUID

/** Acceso tipado a los ajustes (son los mismos que edita la pantalla de ajustes). */
class Prefs(context: Context) {

    val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    var serverUrl: String
        get() = normalizeUrl(sp.getString(K_URL, "") ?: "")
        set(v) = sp.edit { putString(K_URL, v) }

    var token: String
        get() = (sp.getString(K_TOKEN, "") ?: "").trim()
        set(v) = sp.edit { putString(K_TOKEN, v) }

    /** Identificador estable de este "cliente" Plex. */
    val clientId: String
        get() = sp.getString(K_CLIENT_ID, null) ?: UUID.randomUUID().toString().also {
            sp.edit { putString(K_CLIENT_ID, it) }
        }

    /** machineIdentifier del servidor (se rellena en cada sincronización). */
    var machineId: String
        get() = sp.getString(K_MACHINE_ID, "") ?: ""
        set(v) = sp.edit { putString(K_MACHINE_ID, v) }

    var lastSync: String
        get() = sp.getString(K_LAST_SYNC, "") ?: ""
        set(v) = sp.edit { putString(K_LAST_SYNC, v) }

    val rowContinue get() = sp.getBoolean(K_ROW_CONTINUE, true)
    val rowMovies get() = sp.getBoolean(K_ROW_MOVIES, true)
    val rowShows get() = sp.getBoolean(K_ROW_SHOWS, true)
    val rowReco get() = sp.getBoolean(K_ROW_RECO, true)
    val watchNext get() = sp.getBoolean(K_WATCH_NEXT, true)
    val itemsPerRow get() = (sp.getString(K_ITEMS, "20") ?: "20").toIntOrNull() ?: 20
    val cardImage get() = sp.getString(K_CARD_IMAGE, "poster") ?: "poster"
    val openContinue get() = sp.getString(K_OPEN_CONTINUE, "play") ?: "play"
    val openOther get() = sp.getString(K_OPEN_OTHER, "preplay") ?: "preplay"
    val intervalMinutes get() = (sp.getString(K_INTERVAL, "15") ?: "15").toLongOrNull() ?: 15L
    val wallpaperOnFocus get() = sp.getBoolean(K_WP_FOCUS, true)
    val wallpaperSource get() = sp.getString(K_WP_SOURCE, "all") ?: "all"

    fun isConfigured() = serverUrl.isNotBlank() && token.isNotBlank()

    /** Copia de ajustes para Projectivy (sin el token). */
    fun exportJson(): String {
        val o = JSONObject()
        EXPORTABLE.forEach { k -> sp.all[k]?.let { o.put(k, it) } }
        return o.toString()
    }

    fun importJson(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        sp.edit {
            EXPORTABLE.filter { o.has(it) }.forEach { k ->
                when (val v = o.get(k)) {
                    is Boolean -> putBoolean(k, v)
                    else -> putString(k, v.toString())
                }
            }
        }
    }

    companion object {
        const val K_URL = "server_url"
        const val K_TOKEN = "token"
        const val K_CLIENT_ID = "client_id"
        const val K_MACHINE_ID = "machine_id"
        const val K_LAST_SYNC = "last_sync"
        const val K_ROW_CONTINUE = "row_continue"
        const val K_ROW_MOVIES = "row_movies"
        const val K_ROW_SHOWS = "row_shows"
        const val K_ROW_RECO = "row_reco"
        const val K_WATCH_NEXT = "watch_next"
        const val K_ITEMS = "items_per_row"
        const val K_CARD_IMAGE = "card_image"
        const val K_OPEN_CONTINUE = "open_continue"
        const val K_OPEN_OTHER = "open_other"
        const val K_INTERVAL = "interval"
        const val K_WP_FOCUS = "wp_focus"
        const val K_WP_SOURCE = "wp_source"

        /** Ajustes que, al cambiar, piden repintar las filas. */
        val ROW_KEYS = setOf(K_ROW_CONTINUE, K_ROW_MOVIES, K_ROW_SHOWS, K_ROW_RECO, K_WATCH_NEXT, K_ITEMS, K_CARD_IMAGE, K_URL, K_TOKEN)

        private val EXPORTABLE = listOf(
            K_URL, K_ROW_CONTINUE, K_ROW_MOVIES, K_ROW_SHOWS, K_ROW_RECO, K_WATCH_NEXT, K_ITEMS, K_CARD_IMAGE,
            K_OPEN_CONTINUE, K_OPEN_OTHER, K_INTERVAL, K_WP_FOCUS, K_WP_SOURCE
        )

        fun normalizeUrl(raw: String): String {
            var u = raw.trim().trimEnd('/')
            if (u.isEmpty()) return u
            if (!u.contains("://")) u = "http://$u"
            // Si no lleva puerto y es una IP/host local sin ruta, se asume el 32400 de Plex
            val afterScheme = u.substringAfter("://")
            if (!afterScheme.contains(':') && !afterScheme.contains('/') && !afterScheme.endsWith(".plex.direct")) {
                u = "$u:32400"
            }
            return u
        }
    }
}
