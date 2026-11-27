package app.unpaused.data.radio

import app.unpaused.data.db.AppDatabase
import app.unpaused.data.db.RadioStationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress

/** Station as returned by the Radio Browser API (only the fields this app uses). */
@Serializable
data class RadioStationDto(
    val stationuuid: String,
    val name: String = "",
    val url: String = "",
    @SerialName("url_resolved") val urlResolved: String = "",
    val homepage: String = "",
    val favicon: String = "",
    val tags: String = "",
    val countrycode: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val hls: Int = 0,
    val lastcheckok: Int = 1,
) {
    val streamUrl: String get() = urlResolved.ifBlank { url }.trim()

    fun toEntity(existing: RadioStationEntity?) = RadioStationEntity(
        stationUuid = stationuuid,
        name = name.trim().ifEmpty { "Unnamed station" },
        streamUrl = streamUrl,
        homepage = homepage.ifBlank { null },
        faviconUrl = favicon.ifBlank { null },
        tags = tags.ifBlank { null },
        countryCode = countrycode.ifBlank { null },
        codec = codec.ifBlank { null },
        bitrateKbps = bitrate.takeIf { it > 0 },
        favoritedAt = existing?.favoritedAt ?: 0,
        lastPlayedAt = existing?.lastPlayedAt ?: 0,
    )
}

/**
 * Client for the free, community-run Radio Browser directory (https://api.radio-browser.info).
 * No key; the project asks for a descriptive User-Agent and a "click" call when a station is played.
 * Mirrors are discovered by DNS, as the documentation recommends, instead of hard-coding one host.
 */
class RadioBrowserClient(private val http: OkHttpClient, private val userAgent: String) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    @Volatile private var hosts: List<String>? = null

    suspend fun top(limit: Int = 60): List<RadioStationDto> = stations("json/stations/topclick/$limit") { it.addQueryParameter("hidebroken", "true") }

    suspend fun search(name: String?, tag: String? = null, countryCode: String? = null, limit: Int = 60): List<RadioStationDto> =
        stations("json/stations/search") { b ->
            name?.trim()?.takeIf { it.isNotEmpty() }?.let { b.addQueryParameter("name", it) }
            tag?.trim()?.takeIf { it.isNotEmpty() }?.let { b.addQueryParameter("tag", it) }
            countryCode?.trim()?.takeIf { it.length == 2 }?.let { b.addQueryParameter("countrycode", it.uppercase()) }
            b.addQueryParameter("hidebroken", "true")
            b.addQueryParameter("order", "clickcount")
            b.addQueryParameter("reverse", "true")
            b.addQueryParameter("limit", limit.toString())
        }

    suspend fun byUuid(uuid: String): RadioStationDto? = stations("json/stations/byuuid") { it.addQueryParameter("uuids", uuid) }.firstOrNull()

    /** Tells the directory a station was played (counted once per IP and station per day by the server). */
    suspend fun click(uuid: String) {
        runCatching { get("json/url/$uuid") {} }
    }

    private suspend fun stations(path: String, query: (HttpUrl.Builder) -> Unit): List<RadioStationDto> {
        val body = get(path, query)
        return json.decodeFromString<List<RadioStationDto>>(body)
            .filter { it.streamUrl.startsWith("http://") || it.streamUrl.startsWith("https://") }
            .distinctBy { it.stationuuid }
    }

    private suspend fun get(path: String, query: (HttpUrl.Builder) -> Unit): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (host in hosts()) {
            val url = "https://$host/".toHttpUrl().newBuilder().addPathSegments(path).also(query).build()
            val request = Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "application/json").build()
            try {
                http.newCall(request).execute().use { r ->
                    if (r.isSuccessful) return@withContext r.body.string()
                    last = IOException("HTTP ${r.code} from $host")
                }
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("Radio Browser is unreachable")
    }

    private fun hosts(): List<String> {
        hosts?.let { return it }
        val found = runCatching {
            InetAddress.getAllByName("all.api.radio-browser.info")
                .mapNotNull { runCatching { it.canonicalHostName }.getOrNull() }
                .filter { it.endsWith(".api.radio-browser.info") }
                .distinct()
                .shuffled()
        }.getOrDefault(emptyList())
        // The round-robin name itself also answers, so it's the fallback when reverse DNS gives nothing.
        return (found + "all.api.radio-browser.info").also { if (found.isNotEmpty()) hosts = it }
    }
}

class RadioRepository(private val db: AppDatabase, private val client: RadioBrowserClient) {
    private val dao = db.radio()

    fun favorites(): Flow<List<RadioStationEntity>> = dao.observeFavorites()
    fun recent(limit: Int = 20): Flow<List<RadioStationEntity>> = dao.observeRecent(limit)

    suspend fun top(): List<RadioStationEntity> = merge(client.top())
    suspend fun search(name: String?, tag: String?, countryCode: String?): List<RadioStationEntity> = merge(client.search(name, tag, countryCode))

    /** Local copy first (so favourites play offline-from-directory), then the directory. */
    suspend fun station(uuid: String): RadioStationEntity? = dao.get(uuid) ?: runCatching { client.byUuid(uuid) }.getOrNull()?.let { dto ->
        dto.toEntity(null).also { dao.upsert(it) }
    }

    suspend fun toggleFavorite(station: RadioStationEntity): Boolean {
        val current = dao.get(station.stationUuid) ?: station
        val favorite = current.favoritedAt == 0L
        dao.upsert(current.copy(favoritedAt = if (favorite) System.currentTimeMillis() else 0))
        return favorite
    }

    suspend fun remember(station: RadioStationEntity) {
        val current = dao.get(station.stationUuid)
        dao.upsert(station.copy(favoritedAt = current?.favoritedAt ?: station.favoritedAt, lastPlayedAt = current?.lastPlayedAt ?: station.lastPlayedAt))
    }

    suspend fun markPlayed(uuid: String) {
        dao.get(uuid)?.let { dao.upsert(it.copy(lastPlayedAt = System.currentTimeMillis())) }
        client.click(uuid)
    }

    private suspend fun merge(remote: List<RadioStationDto>): List<RadioStationEntity> = remote.map { it.toEntity(dao.get(it.stationuuid)) }
}
