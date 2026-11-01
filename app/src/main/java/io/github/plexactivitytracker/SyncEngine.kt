package io.github.plexactivitytracker

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperProviderContract
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Descarga los datos de Plex y repinta las filas. Una sola ejecución a la vez. */
object SyncEngine {

    private val mutex = Mutex()
    const val PROJECTIVY_PKG = "com.spocky.projengmenu"

    suspend fun run(context: Context): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val ctx = context.applicationContext
            val prefs = Prefs(ctx)
            try {
                val summary = doRun(ctx, prefs)
                prefs.lastSync = "${now()} · $summary"
                summary
            } catch (e: Exception) {
                prefs.lastSync = "${now()} · Error: ${e.message}"
                throw e
            }
        }
    }

    private fun doRun(ctx: Context, prefs: Prefs): String {
        if (!prefs.isConfigured()) throw PlexException("Falta configurar el servidor y el token")
        val client = PlexClient(prefs.serverUrl, prefs.token, prefs.clientId)
        prefs.machineId = client.identity()

        val n = prefs.itemsPerRow.coerceIn(5, 50)
        val sections = client.sections()
        val movieSections = sections.filter { it.type == "movie" }
        val showSections = sections.filter { it.type == "show" }

        val cont = if (prefs.rowContinue || prefs.watchNext) client.continueWatching(n) else emptyList()
        val movies = if (prefs.rowMovies) recent(client, movieSections, n) else emptyList()
        val shows = if (prefs.rowShows) recent(client, showSections, n) else emptyList()
        val reco = if (prefs.rowReco) {
            client.recommendations(movieSections + showSections, n, cont.map { it.ratingKey }.toSet())
        } else emptyList()

        val rows = listOf(
            Row(Row.ROW_CONTINUE, ctx.getString(R.string.row_continue), prefs.rowContinue, cont),
            Row(Row.ROW_MOVIES, ctx.getString(R.string.row_movies), prefs.rowMovies, movies),
            Row(Row.ROW_SHOWS, ctx.getString(R.string.row_shows), prefs.rowShows, shows),
            Row(Row.ROW_RECO, ctx.getString(R.string.row_reco), prefs.rowReco, reco)
        )

        val publisher = ChannelPublisher(ctx, client)
        rows.forEach { if (it.enabled) publisher.publishRow(it) else publisher.removeRow(it.id) }
        if (prefs.watchNext) publisher.publishWatchNext(cont) else publisher.clearWatchNext()

        if (ItemCache.save(ctx, rows.filter { it.enabled }, client)) notifyProjectivy(ctx)

        return listOfNotNull(
            "En curso ${cont.size}".takeIf { prefs.rowContinue || prefs.watchNext },
            "Películas ${movies.size}".takeIf { prefs.rowMovies },
            "Series ${shows.size}".takeIf { prefs.rowShows },
            "Recomendado ${reco.size}".takeIf { prefs.rowReco }
        ).joinToString(" · ")
    }

    private fun recent(client: PlexClient, sections: List<Section>, n: Int): List<PlexItem> =
        sections.flatMap { runCatching { client.recentlyAdded(it, n) }.getOrDefault(emptyList()) }
            .sortedByDescending { it.addedAt ?: 0 }
            .distinctBy { it.ratingKey }
            .take(n)

    /** Avisa a Projectivy de que hay fondos nuevos (solo tiene efecto con Premium). */
    private fun notifyProjectivy(ctx: Context) {
        runCatching {
            ctx.sendBroadcast(
                Intent(WallpaperProviderContract.ACTION_WALLPAPER_PROVIDER_UPDATED)
                    .setPackage(PROJECTIVY_PKG)
                    .putExtra(WallpaperProviderContract.EXTRA_PROVIDER_ID, ctx.getString(R.string.plugin_uuid))
                    .putExtra(
                        WallpaperProviderContract.EXTRA_UPDATE_REASON,
                        WallpaperProviderContract.UpdateReason.DATA_CHANGED
                    )
            )
        }
    }

    private fun now(): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())
}

/** Última lista publicada, con URLs ya resueltas, para el plugin de fondos. */
object ItemCache {

    data class Entry(
        val row: String, val title: String, val poster: String?, val thumb: String?,
        val art: String, val open: String
    )

    private fun file(ctx: Context) = File(ctx.filesDir, "items.json")

    /** Guarda y devuelve true si el contenido ha cambiado. */
    fun save(ctx: Context, rows: List<Row>, client: PlexClient): Boolean {
        val arr = JSONArray()
        for (row in rows) for (item in row.items) {
            val art = client.imageUrl(item.artPath, 1920, 1080) ?: continue
            arr.put(
                JSONObject()
                    .put("row", row.id)
                    .put("title", item.cardTitle)
                    .put("poster", client.imageUrl(item.posterPath, 400, 600))
                    .put("thumb", client.imageUrl(item.stillPath, 640, 360))
                    .put("art", art)
                    .put("open", ChannelPublisher.openUri(item, row.isContinue).toString())
            )
        }
        val text = arr.toString()
        val f = file(ctx)
        val old = runCatching { f.readText() }.getOrNull()
        if (old == text) return false
        f.writeText(text)
        return true
    }

    fun load(ctx: Context): List<Entry> {
        val text = runCatching { file(ctx).readText() }.getOrNull() ?: return emptyList()
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Entry(
                row = it.optString("row"),
                title = it.optString("title"),
                poster = it.optString("poster").takeIf { s -> s.isNotBlank() && s != "null" },
                thumb = it.optString("thumb").takeIf { s -> s.isNotBlank() && s != "null" },
                art = it.optString("art"),
                open = it.optString("open")
            )
        }
    }
}
