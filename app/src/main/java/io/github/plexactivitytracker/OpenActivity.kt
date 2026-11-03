package io.github.plexactivitytracker

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * Las tarjetas apuntan aquí (plexactivitytracker://open?key=…) y esta actividad, sin interfaz,
 * abre el contenido en la app de Plex según el modo elegido en ajustes.
 * Así se puede cambiar el comportamiento sin volver a publicar las filas.
 */
class OpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        PlexLauncher.open(
            this,
            key = data?.getQueryParameter("key"),
            type = data?.getQueryParameter("type"),
            continueRow = data?.getQueryParameter("row") == "continue"
        )
        finish()
    }
}

object PlexLauncher {
    const val PLEX_PKG = "com.plexapp.android"

    fun open(context: Context, key: String?, type: String?, continueRow: Boolean) {
        val prefs = Prefs(context)
        val mode = if (continueRow) prefs.openContinue else prefs.openOther
        val server = prefs.machineId

        if (!key.isNullOrBlank() && server.isNotBlank() && mode != "app") {
            // "play" solo tiene sentido en películas y episodios; series y temporadas van a su ficha
            val action = if (mode == "play" && (type == "movie" || type == "episode")) "play" else "preplay"
            val typeCode = when (type) {
                "movie" -> 1; "show" -> 2; "season" -> 3; "episode" -> 4; else -> 1
            }
            val uri = Uri.parse(
                "plex://$action/?metadataKey=${Uri.encode("/library/metadata/$key")}" +
                    "&metadataType=$typeCode&server=$server"
            )
            // 1) Plex para TV declara el filtro plex:// en tv.plex.app.MainActivity, pero esa actividad
            //    está deshabilitada: solo está activo un alias (.MainActivityDefault u otro, según el
            //    icono elegido en Plex). Un intent explícito al alias llega a MainActivity con la URL.
            launcherComponent(context)?.let { alias ->
                if (tryStart(context, Intent(Intent.ACTION_VIEW, uri).setComponent(alias))) return
            }
            // 2) Resolución implícita (por si otra versión de Plex sí la admite)
            if (tryStart(context, Intent(Intent.ACTION_VIEW, uri).setPackage(PLEX_PKG))) return
        }

        val pm = context.packageManager
        val launch = pm.getLeanbackLaunchIntentForPackage(PLEX_PKG) ?: pm.getLaunchIntentForPackage(PLEX_PKG)
        if (launch == null || !tryStart(context, launch)) {
            Toast.makeText(context, R.string.plex_not_found, Toast.LENGTH_LONG).show()
        }
    }

    /** Alias activo que abre Plex desde el launcher (cambia según el icono elegido en Plex). */
    private fun launcherComponent(context: Context): ComponentName? {
        val pm: PackageManager = context.packageManager
        return (pm.getLeanbackLaunchIntentForPackage(PLEX_PKG) ?: pm.getLaunchIntentForPackage(PLEX_PKG))?.component
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
