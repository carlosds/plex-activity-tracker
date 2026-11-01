package io.github.plexactivitytracker

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.net.Uri
import android.provider.BaseColumns
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram

/** Una fila a publicar: id interno estable, nombre visible y contenido. */
data class Row(val id: String, val title: String, val enabled: Boolean, val items: List<PlexItem>) {
    val isContinue get() = id == ROW_CONTINUE

    companion object {
        const val ROW_CONTINUE = "continue"
        const val ROW_MOVIES = "movies"
        const val ROW_SHOWS = "shows"
        const val ROW_RECO = "reco"
    }
}

/**
 * Publica las filas como "canales" de la pantalla de inicio de Android TV (TvProvider).
 * Projectivy (y cualquier launcher compatible) las puede mostrar como categorías.
 */
class ChannelPublisher(private val context: Context, private val client: PlexClient) {

    private val helper = PreviewChannelHelper(context)
    private val prefs = Prefs(context)

    private fun channels(): List<PreviewChannel> = runCatching { helper.allChannels }.getOrDefault(emptyList())

    /** Canales de esta app (id + id interno), leídos directamente del TvProvider. */
    private class Own(val id: Long, val providerId: String?)

    private fun ownChannels(): List<Own> {
        val out = mutableListOf<Own>()
        try {
            context.contentResolver.query(
                TvContractCompat.Channels.CONTENT_URI,
                arrayOf(BaseColumns._ID, TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) out += Own(c.getLong(0), c.getString(1))
            }
        } catch (e: Exception) {
            Log.w(TAG, "no pude listar los canales", e)
        }
        return out
    }

    fun publishRow(row: Row) {
        val all = ownChannels()
        // Si por algún motivo hay varios canales de la misma fila (versiones anteriores creaban uno
        // nuevo en cada sincronización), se conserva el más antiguo —el que ya tendrás elegido en
        // Projectivy— y se borran los demás.
        val mine = all.filter { it.providerId == row.id }.sortedBy { it.id }
        mine.drop(1).forEach { dup ->
            runCatching { helper.deletePreviewChannel(dup.id) }
                .onFailure { Log.w(TAG, "borrado de canal duplicado ${dup.id} falló", it) }
        }
        val current = mine.firstOrNull()
        val channelId = current?.id ?: createChannel(row, makeDefault = all.isEmpty())
        if (current != null) {
            // Siempre: nombre, enlace y logo (el logo solo se guarda al crear el canal, así que
            // sin esto un canal ya existente se quedaría con el icono antiguo).
            runCatching { helper.updatePreviewChannel(channelId, buildChannel(row)) }
                .onFailure { Log.w(TAG, "updatePreviewChannel(${row.id}) falló", it) }
            runCatching { ChannelLogoUtils.storeChannelLogo(context, channelId, logo()) }
                .onFailure { Log.w(TAG, "storeChannelLogo(${row.id}) falló", it) }
        }
        var deleted = 0
        deletePrograms(TvContractCompat.buildPreviewProgramsUriForChannel(channelId)) { id ->
            helper.deletePreviewProgram(id)
            deleted++
        }
        var published = 0
        val total = row.items.size
        row.items.forEachIndexed { index, item ->
            val b = PreviewProgram.Builder()
            b.setChannelId(channelId)
            fill(b, item, row.isContinue, weight = total - index)
            runCatching { helper.publishPreviewProgram(b.build()) }
                .onSuccess { published++ }
                .onFailure { Log.w(TAG, "publishPreviewProgram(${row.id}, ${item.ratingKey}) falló", it) }
        }
        Log.i(
            TAG,
            "fila ${row.id}: canal=$channelId ${if (current == null) "NUEVO" else "existente"} " +
                "duplicados=${(mine.size - 1).coerceAtLeast(0)} borrados=$deleted publicados=$published/$total"
        )
    }

    fun removeRow(rowId: String) {
        ownChannels().filter { it.providerId == rowId }.forEach {
            runCatching { helper.deletePreviewChannel(it.id) }
        }
    }

    /** Fila de sistema "Continuar viendo" (Watch Next), con barra de progreso. */
    fun publishWatchNext(items: List<PlexItem>) {
        clearWatchNext()
        val now = System.currentTimeMillis()
        items.forEachIndexed { index, item ->
            val b = WatchNextProgram.Builder()
            b.setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            val engaged = item.lastViewedAt?.let { it * 1000 } ?: (now - index * 60_000L)
            b.setLastEngagementTimeUtcMillis(engaged)
            fill(b, item)
            runCatching { helper.publishWatchNextProgram(b.build()) }
        }
    }

    fun clearWatchNext() {
        deletePrograms(TvContractCompat.WatchNextPrograms.CONTENT_URI) { id ->
            context.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null)
        }
    }

    /** Peticiones de visibilidad para launchers que solo muestran canales "browsable". */
    fun nonBrowsableChannelIds(): List<Long> = channels().filter { !it.isBrowsable }.map { it.id }

    private fun createChannel(row: Row, makeDefault: Boolean): Long {
        val ch = buildChannel(row)
        return if (makeDefault) helper.publishDefaultChannel(ch) else helper.publishChannel(ch)
    }

    private fun buildChannel(row: Row): PreviewChannel =
        PreviewChannel.Builder()
            .setDisplayName(row.title)
            .setAppLinkIntentUri(Uri.parse("plexactivitytracker://settings"))
            .setInternalProviderId(row.id)
            .setLogo(logo())
            .build()

    private fun logo(): Bitmap =
        ContextCompat.getDrawable(context, R.drawable.ic_logo)!!.toBitmap(256, 256)

    private fun deletePrograms(uri: Uri, delete: (Long) -> Unit) {
        val ids = mutableListOf<Long>()
        runCatching {
            context.contentResolver.query(uri, arrayOf(BaseColumns._ID), null, null, null)?.use { c ->
                while (c.moveToNext()) ids += c.getLong(0)
            }
        }
        ids.forEach { runCatching { delete(it) }.onFailure { e -> Log.w(TAG, "borrado de programa $it falló", e) } }
    }

    /** Valores comunes de una tarjeta (los builders de canal y de Watch Next no comparten tipo público). */
    private class Card(
        val type: Int, val title: String, val description: String?, val episodeTitle: String?,
        val season: Int?, val episode: Int?, val releaseDate: String?,
        val poster: Uri?, val thumb: Uri?, val durationMs: Int?, val positionMs: Int?,
        val intent: Uri, val providerId: String
    )

    private fun card(item: PlexItem, continueRow: Boolean): Card {
        // "Continuar viendo": solo título y duración (sin año ni descripción).
        // Resto de filas: el año va solo en releaseDate (el launcher ya lo muestra) y no en la descripción.
        val desc = if (continueRow) null
        else listOfNotNull(item.cardSubtitle, item.summary).joinToString("\n").ifBlank { null }
        // En filas normales se puede elegir carátula (vertical) o fanart (horizontal).
        val posterOnly = !continueRow && prefs.cardImage == "poster"
        return Card(
            type = when (item.type) {
                "movie" -> TvContractCompat.PreviewProgramColumns.TYPE_MOVIE
                "show" -> TvContractCompat.PreviewProgramColumns.TYPE_TV_SERIES
                "season" -> TvContractCompat.PreviewProgramColumns.TYPE_TV_SEASON
                else -> TvContractCompat.PreviewProgramColumns.TYPE_TV_EPISODE
            },
            title = item.cardTitle,
            description = desc,
            episodeTitle = item.title.takeIf { item.type == "episode" },
            season = item.season.takeIf { item.type == "episode" },
            episode = item.episode.takeIf { item.type == "episode" },
            releaseDate = if (continueRow) null else item.year?.toString(),
            poster = client.imageUrl(item.posterPath, 400, 600)?.let(Uri::parse),
            thumb = if (posterOnly) null else client.imageUrl(item.stillPath, 640, 360)?.let(Uri::parse),
            durationMs = item.durationMs?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
            positionMs = if (continueRow) item.viewOffsetMs?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() else null,
            intent = openUri(item, continueRow),
            providerId = item.ratingKey
        )
    }

    private fun fill(b: PreviewProgram.Builder, item: PlexItem, continueRow: Boolean, weight: Int) {
        val c = card(item, continueRow)
        b.setType(c.type)
        b.setTitle(c.title)
        c.description?.let { b.setDescription(it) }
        c.episodeTitle?.let { b.setEpisodeTitle(it) }
        c.season?.let { b.setSeasonNumber(it) }
        c.episode?.let { b.setEpisodeNumber(it) }
        c.releaseDate?.let { b.setReleaseDate(it) }
        c.poster?.let {
            b.setPosterArtUri(it)
            b.setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_2_3)
        }
        c.thumb?.let {
            b.setThumbnailUri(it)
            b.setThumbnailAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9)
        }
        c.durationMs?.let { b.setDurationMillis(it) }
        c.positionMs?.let { b.setLastPlaybackPositionMillis(it) }
        b.setIntentUri(c.intent)
        b.setInternalProviderId(c.providerId)
        b.setWeight(weight)
    }

    private fun fill(b: WatchNextProgram.Builder, item: PlexItem) {
        val c = card(item, continueRow = true)
        b.setType(c.type)
        b.setTitle(c.title)
        c.description?.let { b.setDescription(it) }
        c.episodeTitle?.let { b.setEpisodeTitle(it) }
        c.season?.let { b.setSeasonNumber(it) }
        c.episode?.let { b.setEpisodeNumber(it) }
        c.releaseDate?.let { b.setReleaseDate(it) }
        c.poster?.let {
            b.setPosterArtUri(it)
            b.setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_2_3)
        }
        c.thumb?.let {
            b.setThumbnailUri(it)
            b.setThumbnailAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9)
        }
        c.durationMs?.let { b.setDurationMillis(it) }
        c.positionMs?.let { b.setLastPlaybackPositionMillis(it) }
        b.setIntentUri(c.intent)
        b.setInternalProviderId(c.providerId)
    }

    companion object {
        private const val TAG = "PlexTracker"

        fun openUri(item: PlexItem, continueRow: Boolean): Uri =
            Uri.parse("plexactivitytracker://open").buildUpon()
                .appendQueryParameter("key", item.ratingKey)
                .appendQueryParameter("type", item.type)
                .appendQueryParameter("row", if (continueRow) "continue" else "other")
                .build()
    }
}
