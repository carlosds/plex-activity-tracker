package io.github.plexfilas

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.BaseColumns
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
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

    private fun channels(): List<PreviewChannel> = runCatching { helper.allChannels }.getOrDefault(emptyList())

    fun publishRow(row: Row) {
        val existing = channels()
        val current = existing.firstOrNull { it.internalProviderId == row.id }
        val channelId = current?.id ?: createChannel(row, makeDefault = existing.isEmpty())
        if (current != null && current.displayName?.toString() != row.title) {
            runCatching { helper.updatePreviewChannel(channelId, buildChannel(row)) }
        }
        deletePrograms(TvContractCompat.buildPreviewProgramsUriForChannel(channelId)) { id ->
            helper.deletePreviewProgram(id)
        }
        val total = row.items.size
        row.items.forEachIndexed { index, item ->
            val b = PreviewProgram.Builder()
            b.setChannelId(channelId)
            fill(b, item, row.isContinue, weight = total - index)
            runCatching { helper.publishPreviewProgram(b.build()) }
        }
    }

    fun removeRow(rowId: String) {
        channels().filter { it.internalProviderId == rowId }.forEach {
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
            .setAppLinkIntentUri(Uri.parse("plexfilas://settings"))
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
        ids.forEach { runCatching { delete(it) } }
    }

    /** Valores comunes de una tarjeta (los builders de canal y de Watch Next no comparten tipo público). */
    private class Card(
        val type: Int, val title: String, val description: String?, val episodeTitle: String?,
        val season: Int?, val episode: Int?, val releaseDate: String?,
        val poster: Uri?, val thumb: Uri?, val durationMs: Int?, val positionMs: Int?,
        val intent: Uri, val providerId: String
    )

    private fun card(item: PlexItem, continueRow: Boolean): Card {
        val desc = listOfNotNull(item.cardSubtitle, item.summary).joinToString("\n").ifBlank { null }
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
            releaseDate = item.year?.toString(),
            poster = client.imageUrl(item.posterPath, 400, 600)?.let(Uri::parse),
            thumb = client.imageUrl(item.stillPath, 640, 360)?.let(Uri::parse),
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
        fun openUri(item: PlexItem, continueRow: Boolean): Uri =
            Uri.parse("plexfilas://open").buildUpon()
                .appendQueryParameter("key", item.ratingKey)
                .appendQueryParameter("type", item.type)
                .appendQueryParameter("row", if (continueRow) "continue" else "other")
                .build()
    }
}
