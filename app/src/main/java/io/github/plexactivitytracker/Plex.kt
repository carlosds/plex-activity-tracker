package io.github.plexactivitytracker

import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class PlexException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Un elemento de la biblioteca de Plex, ya reducido a lo que necesitan las tarjetas. */
data class PlexItem(
    val ratingKey: String,
    val type: String,            // movie | show | season | episode
    val title: String,
    val showTitle: String?,      // serie a la que pertenece (episodio/temporada)
    val season: Int?,
    val episode: Int?,
    val summary: String?,
    val year: Int?,
    val posterPath: String?,     // imagen vertical 2:3
    val stillPath: String?,      // imagen horizontal 16:9
    val artPath: String?,        // fanart para el fondo
    val durationMs: Long?,
    val viewOffsetMs: Long?,
    val lastViewedAt: Long?,     // segundos epoch
    val addedAt: Long?           // segundos epoch
) {
    val cardTitle: String
        get() = when (type) {
            "episode", "season" -> showTitle ?: title
            else -> title
        }

    val cardSubtitle: String?
        get() = when (type) {
            "episode" -> buildString {
                if (season != null && episode != null) append("T$season·E$episode  ")
                append(title)
            }
            "season" -> title
            else -> year?.toString()
        }
}

data class Section(val key: String, val type: String, val title: String)

/** Peticiones HTTP con las cabeceras que espera Plex. Sin dependencias externas. */
object PlexHttp {
    fun request(
        url: String,
        clientId: String,
        token: String? = null,
        method: String = "GET",
        timeoutMs: Int = 10_000
    ): String {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw PlexException("URL no válida: $url", e)
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("X-Plex-Product", "Plex Activity Tracker")
            conn.setRequestProperty("X-Plex-Version", BuildConfig.VERSION_NAME)
            conn.setRequestProperty("X-Plex-Client-Identifier", clientId)
            conn.setRequestProperty("X-Plex-Platform", "Android")
            conn.setRequestProperty("X-Plex-Device", "Android TV")
            conn.setRequestProperty("X-Plex-Device-Name", "Plex Activity Tracker (${Build.MODEL})")
            if (!token.isNullOrBlank()) conn.setRequestProperty("X-Plex-Token", token)
            if (method == "POST") {
                conn.doOutput = true
                conn.outputStream.use { }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 401) throw PlexException("Token no válido (HTTP 401)")
            if (code !in 200..299) throw PlexException("HTTP $code en ${URL(url).path}")
            return body
        } catch (e: PlexException) {
            throw e
        } catch (e: Exception) {
            throw PlexException("${e.javaClass.simpleName}: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }
}

/** Cliente mínimo para el Plex Media Server. */
class PlexClient(
    baseUrl: String,
    private val token: String,
    private val clientId: String,
    private val timeoutMs: Int = 10_000
) {
    val base = baseUrl.trimEnd('/')

    private fun url(path: String, params: Map<String, Any> = emptyMap()): String {
        val q = params.entries.joinToString("&") { "${Uri.encode(it.key)}=${Uri.encode(it.value.toString())}" }
        return if (q.isEmpty()) "$base$path" else "$base$path?$q"
    }

    private fun container(path: String, params: Map<String, Any> = emptyMap()): JSONObject {
        val body = PlexHttp.request(url(path, params), clientId, token, timeoutMs = timeoutMs)
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw PlexException("Respuesta inesperada de $path", e)
        }
        return json.optJSONObject("MediaContainer") ?: JSONObject()
    }

    private fun page(limit: Int) = mapOf("X-Plex-Container-Start" to 0, "X-Plex-Container-Size" to limit)

    fun identity(): String =
        container("/identity").optString("machineIdentifier").ifBlank {
            throw PlexException("El servidor no devolvió machineIdentifier")
        }

    fun sections(): List<Section> {
        val arr = container("/library/sections").optJSONArray("Directory") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Section(it.optString("key"), it.optString("type"), it.optString("title"))
        }
    }

    /** "Continuar viendo" (hub moderno); si el servidor no lo tiene, On Deck clásico. */
    fun continueWatching(limit: Int): List<PlexItem> {
        val hub = runCatching { parseItems(container("/hubs/continueWatching/items", page(limit))) }.getOrNull()
        if (!hub.isNullOrEmpty()) return hub.take(limit)
        return parseItems(container("/library/onDeck", page(limit))).take(limit)
    }

    fun recentlyAdded(section: Section, limit: Int): List<PlexItem> =
        parseItems(container("/library/sections/${section.key}/recentlyAdded", page(limit))).take(limit)

    /**
     * Recomendaciones que Plex calcula por biblioteca (Redescubre, Mejor valoradas, Porque viste X…).
     * Se mezclan alternando entre hubs para que la fila tenga variedad.
     */
    fun recommendations(sections: List<Section>, limit: Int, exclude: Set<String>): List<PlexItem> {
        val hubs = mutableListOf<List<PlexItem>>()
        for (s in sections) {
            val mc = runCatching { container("/hubs/sections/${s.key}", mapOf("count" to 12)) }.getOrNull() ?: continue
            val arr = mc.optJSONArray("Hub") ?: continue
            for (i in 0 until arr.length()) {
                val hub = arr.getJSONObject(i)
                val id = hub.optString("hubIdentifier").lowercase()
                if (EXCLUDED_HUBS.any { id.contains(it) }) continue
                val items = parseItems(hub)
                if (items.isNotEmpty()) hubs += items
            }
        }
        val out = LinkedHashMap<String, PlexItem>()
        var idx = 0
        while (out.size < limit && hubs.any { idx < it.size }) {
            for (h in hubs) {
                if (idx < h.size && h[idx].ratingKey !in exclude) out.putIfAbsent(h[idx].ratingKey, h[idx])
                if (out.size >= limit) break
            }
            idx++
        }
        return out.values.toList()
    }

    /** URL de imagen redimensionada por el propio servidor. */
    fun imageUrl(path: String?, width: Int, height: Int): String? {
        if (path.isNullOrBlank()) return null
        return url(
            "/photo/:/transcode",
            linkedMapOf(
                "width" to width, "height" to height, "minSize" to 1, "upscale" to 1,
                "url" to path, "X-Plex-Token" to token
            )
        )
    }

    companion object {
        private val EXCLUDED_HUBS = listOf("ondeck", "continue", "inprogress", "recentlyadded", "recentlyviewed", "playlist")
        private val SUPPORTED = setOf("movie", "show", "season", "episode")

        fun parseItems(container: JSONObject): List<PlexItem> {
            val arr: JSONArray = container.optJSONArray("Metadata") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { parseItem(arr.getJSONObject(it)) }
        }

        private fun parseItem(o: JSONObject): PlexItem? {
            val type = o.str("type") ?: return null
            if (type !in SUPPORTED) return null
            val key = o.str("ratingKey") ?: return null
            val poster = when (type) {
                "episode" -> o.str("grandparentThumb") ?: o.str("parentThumb") ?: o.str("thumb")
                "season" -> o.str("thumb") ?: o.str("parentThumb")
                else -> o.str("thumb")
            }
            val art = o.str("art") ?: o.str("grandparentArt") ?: o.str("parentArt")
            val still = if (type == "episode") o.str("thumb") ?: art else art ?: poster
            return PlexItem(
                ratingKey = key,
                type = type,
                title = o.str("title") ?: "",
                showTitle = when (type) {
                    "episode" -> o.str("grandparentTitle")
                    "season" -> o.str("parentTitle")
                    else -> null
                },
                season = if (type == "episode") o.int("parentIndex") else o.int("index").takeIf { type == "season" },
                episode = if (type == "episode") o.int("index") else null,
                summary = o.str("summary"),
                year = o.int("year"),
                posterPath = poster,
                stillPath = still,
                artPath = art,
                durationMs = o.long("duration"),
                viewOffsetMs = o.long("viewOffset"),
                lastViewedAt = o.long("lastViewedAt"),
                addedAt = o.long("addedAt")
            )
        }

        private fun JSONObject.str(k: String): String? =
            if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotBlank() } else null

        private fun JSONObject.int(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null
        private fun JSONObject.long(k: String): Long? = if (has(k) && !isNull(k)) optLong(k) else null
    }
}

/** Vinculación con la cuenta (código de plex.tv/link) y descubrimiento del servidor. */
object PlexAccount {
    data class Pin(val id: Long, val code: String)
    data class Connection(val uri: String, val address: String, val port: Int, val local: Boolean, val relay: Boolean)
    data class Server(val name: String, val accessToken: String, val owned: Boolean, val connections: List<Connection>)

    private const val API = "https://plex.tv/api/v2"

    fun createPin(clientId: String): Pin {
        val j = JSONObject(PlexHttp.request("$API/pins?strong=false", clientId, method = "POST"))
        return Pin(j.getLong("id"), j.getString("code"))
    }

    /** Devuelve el token cuando el usuario ya ha introducido el código, o null si aún no. */
    fun checkPin(clientId: String, pinId: Long): String? {
        val j = JSONObject(PlexHttp.request("$API/pins/$pinId", clientId))
        return if (j.isNull("authToken")) null else j.optString("authToken").takeIf { it.isNotBlank() }
    }

    fun servers(clientId: String, token: String): List<Server> {
        val arr = JSONArray(PlexHttp.request("$API/resources?includeHttps=1&includeRelay=0", clientId, token))
        val out = mutableListOf<Server>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (!r.optString("provides").split(',').contains("server")) continue
            val conns = r.optJSONArray("connections") ?: JSONArray()
            out += Server(
                name = r.optString("name"),
                accessToken = r.optString("accessToken").ifBlank { token },
                owned = r.optBoolean("owned"),
                connections = (0 until conns.length()).map { conns.getJSONObject(it) }.map {
                    Connection(
                        uri = it.optString("uri"),
                        address = it.optString("address"),
                        port = it.optInt("port", 32400),
                        local = it.optBoolean("local"),
                        relay = it.optBoolean("relay")
                    )
                }
            )
        }
        return out.sortedByDescending { it.owned }
    }

    /** Prueba primero las conexiones de la LAN (http directo por IP y luego plex.direct). */
    fun findWorkingUrl(server: Server, clientId: String): String? {
        val candidates = mutableListOf<String>()
        server.connections.filter { it.local && !it.relay }.forEach { c ->
            if (c.address.isNotBlank() && !c.address.contains(':')) candidates += "http://${c.address}:${c.port}"
            if (c.uri.isNotBlank()) candidates += c.uri
        }
        server.connections.filter { !it.local && !it.relay && it.uri.isNotBlank() }.forEach { candidates += it.uri }
        for (url in candidates.distinct()) {
            val ok = runCatching { PlexClient(url, server.accessToken, clientId, timeoutMs = 4000).identity() }.isSuccess
            if (ok) return url
        }
        return null
    }
}
