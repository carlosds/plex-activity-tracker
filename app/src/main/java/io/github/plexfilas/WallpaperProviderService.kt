package io.github.plexfilas

import android.app.Service
import android.content.Intent
import android.os.IBinder
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperDisplayMode
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperType

/**
 * Plugin de fondos para Projectivy (necesita Projectivy Premium para poder elegirlo).
 * - Cada X minutos: rota el fanart de lo que hay en tus filas.
 * - Al enfocar una tarjeta de nuestras filas: muestra el fanart de ese título.
 * Trabaja con la caché de la última sincronización, así que responde al instante.
 */
class WallpaperProviderService : Service() {

    override fun onBind(intent: Intent): IBinder = binder

    private val binder = object : IWallpaperProviderService.Stub() {

        override fun getWallpapers(event: Event?): List<Wallpaper> {
            val prefs = Prefs(this@WallpaperProviderService)
            val entries = ItemCache.load(this@WallpaperProviderService)
            if (entries.isEmpty()) return emptyList()

            return when (event) {
                is Event.TimeElapsed -> {
                    val source = prefs.wallpaperSource
                    entries.filter {
                        when (source) {
                            "continue" -> it.row == Row.ROW_CONTINUE
                            "recent" -> it.row == Row.ROW_MOVIES || it.row == Row.ROW_SHOWS
                            else -> true
                        }
                    }.distinctBy { it.art }.map { it.toWallpaper() }
                }

                is Event.ProgramCardFocused -> {
                    if (!prefs.wallpaperOnFocus) return emptyList()
                    val icon = event.iconUri
                    val match = entries.firstOrNull { icon != null && (icon == it.poster || icon == it.thumb) }
                        ?: entries.firstOrNull { event.title != null && event.title == it.title }
                    // Lista vacía = no cambiar el fondo actual (tarjetas que no son nuestras)
                    match?.let { listOf(it.toWallpaper()) } ?: emptyList()
                }

                else -> emptyList()
            }
        }

        override fun getPreferences(): String = Prefs(this@WallpaperProviderService).exportJson()

        override fun setPreferences(params: String) {
            Prefs(this@WallpaperProviderService).importJson(params)
        }
    }

    private fun ItemCache.Entry.toWallpaper() = Wallpaper(
        uri = art,
        type = WallpaperType.IMAGE,
        displayMode = WallpaperDisplayMode.CROP,
        title = title,
        source = "Plex",
        actionUri = open
    )
}
