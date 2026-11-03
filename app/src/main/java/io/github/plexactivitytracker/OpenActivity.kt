package io.github.plexactivitytracker

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
            continueRow = data?.getQueryParameter("row") == "continue",
            offsetSec = data?.getQueryParameter("pos")?.toLongOrNull() ?: 0L
        )
        finish()
    }
}

/**
 * Apertura de contenido en Plex para Android TV (la app nueva, tv.plex.app).
 *
 * Lo comprobado en una Bravia con Google TV:
 *  - El filtro plex:// está en tv.plex.app.MainActivity, que está deshabilitada; solo hay activo un
 *    alias (.MainActivityDefault u otro, según el icono elegido en Plex). Hay que mandar el intent
 *    explícito a ese alias.
 *  - Funciona: plex://watch/video?uri=server://<machineId>/com.plexapp.plugins.library/library/metadata/<id>
 *    Acepta &offset=<segundos> para empezar en ese punto (así se reanuda; viewOffset y t no
 *    funcionan, y offset en milisegundos se interpreta como segundos y salta al final).
 *  - No funcionan: plex://play, plex://preplay ni plex://movie/<guid> (ficha).
 *  - Con Plex cerrado el enlace falla ("No se pudo conectar con el servidor"): Plex aún no ha
 *    encontrado el servidor. Por eso primero se abre Plex y el enlace se envía tras una espera.
 */
object PlexLauncher {
    const val PLEX_PKG = "com.plexapp.android"
    private const val TAG = "PlexTracker"

    fun open(context: Context, key: String?, type: String?, continueRow: Boolean, offsetSec: Long = 0L) {
        val prefs = Prefs(context)
        val mode = if (continueRow) prefs.openContinue else prefs.openOther
        val server = prefs.machineId
        val app = context.applicationContext

        val launch = launcherIntent(app)
        if (launch == null) {
            Toast.makeText(context, R.string.plex_not_found, Toast.LENGTH_LONG).show()
            return
        }

        // Solo películas y episodios se pueden reproducir; series y temporadas abren Plex.
        val playable = type == "movie" || type == "episode"
        if (mode != "play" || !playable || key.isNullOrBlank() || server.isBlank()) {
            tryStart(context, launch)
            return
        }

        val uri = Uri.parse("plex://watch/video").buildUpon()
            .appendQueryParameter("uri", "server://$server/com.plexapp.plugins.library/library/metadata/$key")
            .apply { if (offsetSec > 0) appendQueryParameter("offset", offsetSec.toString()) }
            .build()
        val deepLink = Intent(Intent.ACTION_VIEW, uri).setComponent(launch.component)

        val delayMs = prefs.plexDelaySeconds * 1000L
        if (delayMs <= 0) {
            if (!tryStart(context, deepLink)) tryStart(context, launch)
            return
        }
        // Primero Plex (para que arranque y encuentre el servidor) y luego el enlace.
        // Android permite lanzar actividades unos segundos después de haber lanzado otra,
        // así que la espera debe quedarse por debajo de ~10 s.
        tryStart(context, launch)
        Handler(Looper.getMainLooper()).postDelayed({
            if (!tryStart(app, deepLink)) Log.w(TAG, "no pude enviar el enlace a Plex: $uri")
        }, delayMs)
    }

    /** Alias activo que abre Plex desde el launcher (cambia según el icono elegido en Plex). */
    private fun launcherIntent(context: Context): Intent? {
        val pm = context.packageManager
        return pm.getLeanbackLaunchIntentForPackage(PLEX_PKG) ?: pm.getLaunchIntentForPackage(PLEX_PKG)
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "no se pudo abrir $intent", e); false
    } catch (e: SecurityException) {
        Log.w(TAG, "no se pudo abrir $intent", e); false
    }
}
