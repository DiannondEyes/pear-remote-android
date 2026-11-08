package pear.remote

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class RemoteSongData(
    val title: String? = null,
    val artist: String? = null,
    val coverUrl: String? = null,
    val isPlaying: Boolean? = null,
    val volume: Float? = null,
    val currentProgressSec: Long? = null,
    val totalDurationSec: Long? = null,
    val wsConnected: Boolean? = null
)

class PearRemoteManager(
    context: Context,
    private val onStateUpdate: (RemoteSongData) -> Unit,
    private val onConnectionStatusChanged: (isConnected: Boolean, statusText: String) -> Unit
) {
    companion object {
        private const val PREFS_NAME = "pear_remote_prefs"
        private const val KEY_IP = "saved_ip"
        private const val KEY_PORT = "saved_port"
        private const val KEY_TOKEN = "access_token"
        private const val KEY_CLIENT_NAME = "client_name"
        const val DEFAULT_PORT = 26538

        @Volatile
        var activeInstance: PearRemoteManager? = null
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Standard client for quick REST commands and WebSocket
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    // Dedicated client with long read timeout for /auth/{id} while waiting for user approval on PC
    private val authHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(65, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(70, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var currentIp: String = prefs.getString(KEY_IP, "") ?: ""

    @Volatile
    private var currentPort: Int = prefs.getInt(KEY_PORT, DEFAULT_PORT)

    @Volatile
    private var authToken: String = prefs.getString(KEY_TOKEN, "") ?: ""

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var isWsOpen: Boolean = false

    private var fallbackPollingJob: Job? = null
    private var wsReconnectTicks: Int = 0

    init {
        activeInstance = this
    }

    fun getSavedIp(): String = prefs.getString(KEY_IP, "") ?: ""

    fun getSavedPort(): Int = prefs.getInt(KEY_PORT, DEFAULT_PORT)

    fun getSavedToken(): String = prefs.getString(KEY_TOKEN, "") ?: ""

    fun getDefaultClientName(): String {
        val saved = prefs.getString(KEY_CLIENT_NAME, null)
        if (!saved.isNullOrBlank()) return saved
        val model = Build.MODEL?.replace(Regex("[^A-Za-z0-9_-]"), "-")?.takeIf { it.isNotBlank() }
            ?: "Android"
        val generated = "PearRemote-$model"
        prefs.edit().putString(KEY_CLIENT_NAME, generated).apply()
        return generated
    }

    /**
     * Step 3.1: Authorization and saving JWT token in SharedPreferences.
     * Supports both Pear Desktop's actual `POST /auth/{clientName}` route (from `routes/auth.ts`)
     * and `POST /api/v1/auth` fallback.
     */
    suspend fun connect(
        ip: String,
        port: Int,
        clientName: String = getDefaultClientName()
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cleanIp = ip.trim()
            if (cleanIp.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Введите IP-адрес ПК"))
            }

            currentIp = cleanIp
            currentPort = port
            prefs.edit()
                .putString(KEY_IP, cleanIp)
                .putInt(KEY_PORT, port)
                .apply()

            val encodedClient = URLEncoder.encode(clientName, "UTF-8")
            val payloadJson = JsonObject().apply {
                addProperty("clientName", clientName)
                addProperty("id", clientName)
            }.toString()

            // 1. Primary route in Pear Desktop (`src/plugins/api-server/backend/routes/auth.ts`): POST /auth/{id}
            val primaryAuthUrl = "http://$cleanIp:$port/auth/$encodedClient"
            val primaryRequest = Request.Builder()
                .url(primaryAuthUrl)
                .post(payloadJson.toRequestBody(jsonMediaType))
                .build()

            var token: String? = null
            var responseCode: Int

            authHttpClient.newCall(primaryRequest).execute().use { response ->
                responseCode = response.code
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string().orEmpty()
                    token = extractAccessToken(bodyStr)
                }
            }

            // 2. Fallback route: POST /api/v1/auth (if server uses /api/v1/auth)
            if (token == null && responseCode == 404) {
                val fallbackAuthUrl = "http://$cleanIp:$port/api/v1/auth"
                val fallbackRequest = Request.Builder()
                    .url(fallbackAuthUrl)
                    .post(payloadJson.toRequestBody(jsonMediaType))
                    .build()

                authHttpClient.newCall(fallbackRequest).execute().use { response ->
                    responseCode = response.code
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string().orEmpty()
                        token = extractAccessToken(bodyStr)
                    }
                }
            }

            val resolvedToken = token
            if (!resolvedToken.isNullOrBlank()) {
                authToken = resolvedToken
                prefs.edit().putString(KEY_TOKEN, resolvedToken).apply()

                // Start WebSocket & fallback polling
                connectWebSocket(cleanIp, port, resolvedToken)
                startFallbackPolling(cleanIp, port, resolvedToken)

                // Immediately fetch initial song & volume via REST as well
                fetchSongInfo()
                fetchVolumeState()

                onConnectionStatusChanged(true, "Подключено")
                Result.success(resolvedToken)
            } else if (responseCode == 403) {
                onConnectionStatusChanged(false, "Ошибка: доступ отклонён на ПК")
                Result.failure(IllegalStateException("Доступ отклонён пользователем на ПК (403)"))
            } else {
                onConnectionStatusChanged(false, "Ошибка подключения (код $responseCode)")
                Result.failure(IllegalStateException("Не удалось получить токен (HTTP $responseCode)"))
            }
        } catch (e: Exception) {
            onConnectionStatusChanged(false, "Ошибка подключения: сервер недоступен")
            Result.failure(e)
        }
    }

    private fun extractAccessToken(bodyStr: String): String? {
        if (bodyStr.isBlank()) return null
        return try {
            val jsonObj = JsonParser.parseString(bodyStr).asJsonObject
            jsonObj.get("accessToken")?.takeIf { !it.isJsonNull }?.asString
                ?: jsonObj.get("token")?.takeIf { !it.isJsonNull }?.asString
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Step 3.2: REST control commands with `Authorization: Bearer <token>`
     */
    fun togglePlay() {
        scope.launch {
            postControlCommand("/api/v1/toggle-play")
        }
    }

    fun play() {
        scope.launch {
            postControlCommand("/api/v1/play")
        }
    }

    fun pause() {
        scope.launch {
            postControlCommand("/api/v1/pause")
        }
    }

    fun next() {
        scope.launch {
            postControlCommand("/api/v1/next")
            delay(350)
            fetchSongInfo()
        }
    }

    fun previous() {
        scope.launch {
            postControlCommand("/api/v1/previous")
            delay(350)
            fetchSongInfo()
        }
    }

    fun setVolume(volume: Int) {
        val clamped = volume.coerceIn(0, 100)
        scope.launch {
            val bodyJson = JsonObject().apply {
                addProperty("volume", clamped)
            }.toString()
            postControlCommand("/api/v1/volume", bodyJson)
        }
    }

    fun seekTo(seconds: Long) {
        val clamped = seconds.coerceAtLeast(0L)
        scope.launch {
            val bodyJson = JsonObject().apply {
                addProperty("seconds", clamped)
            }.toString()
            postControlCommand("/api/v1/seek-to", bodyJson)
        }
    }

    private suspend fun postControlCommand(path: String, jsonBody: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            if (currentIp.isBlank()) return@withContext false
            try {
                val url = "http://$currentIp:$currentPort$path"
                val requestBody = (jsonBody ?: "").toRequestBody(
                    if (jsonBody != null) jsonMediaType else null
                )
                val builder = Request.Builder()
                    .url(url)
                    .post(requestBody)

                if (authToken.isNotBlank()) {
                    builder.header("Authorization", "Bearer $authToken")
                }

                httpClient.newCall(builder.build()).execute().use { response ->
                    response.isSuccessful
                }
            } catch (e: Exception) {
                false
            }
        }

    /**
     * Polls `GET /api/v1/song` and updates the UI state.
     */
    suspend fun fetchSongInfo(): Boolean = withContext(Dispatchers.IO) {
        if (currentIp.isBlank()) return@withContext false
        try {
            val url = "http://$currentIp:$currentPort/api/v1/song"
            val builder = Request.Builder().url(url).get()
            if (authToken.isNotBlank()) {
                builder.header("Authorization", "Bearer $authToken")
            }

            httpClient.newCall(builder.build()).execute().use { response ->
                if (response.code == 204) {
                    // Server is reachable, but no song is currently active
                    return@use true
                }
                if (!response.isSuccessful) {
                    return@use false
                }
                val bodyStr = response.body?.string().orEmpty()
                if (bodyStr.isNotBlank()) {
                    parseAndDispatchSongJson(bodyStr)
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Polls `GET /api/v1/volume` to synchronize current desktop player volume.
     */
    suspend fun fetchVolumeState(): Boolean = withContext(Dispatchers.IO) {
        if (currentIp.isBlank()) return@withContext false
        try {
            val url = "http://$currentIp:$currentPort/api/v1/volume"
            val builder = Request.Builder().url(url).get()
            if (authToken.isNotBlank()) {
                builder.header("Authorization", "Bearer $authToken")
            }

            httpClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val bodyStr = response.body?.string().orEmpty()
                if (bodyStr.isNotBlank()) {
                    val obj = JsonParser.parseString(bodyStr).asJsonObject
                    val vol = obj.get("state")?.takeIf { !it.isJsonNull }?.asFloat
                        ?: obj.get("volume")?.takeIf { !it.isJsonNull }?.asFloat
                    if (vol != null) {
                        onStateUpdate(RemoteSongData(volume = vol.coerceIn(0f, 100f)))
                    }
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Step 3.3: Real-time synchronization via WebSocket (`ws://<ip>:<port>/api/v1/ws?token=<token>`)
     * with automatic fallback polling every 2 seconds if WebSocket drops or closes.
     */
    fun connectWebSocket(ip: String, port: Int, token: String) {
        try {
            webSocket?.cancel()
            webSocket = null
            isWsOpen = false

            val encodedToken = URLEncoder.encode(token, "UTF-8")
            val wsUrl = "ws://$ip:$port/api/v1/ws?token=$encodedToken"
            val request = Request.Builder()
                .url(wsUrl)
                .apply {
                    if (token.isNotBlank()) {
                        header("Authorization", "Bearer $token")
                    }
                }
                .build()

            webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    isWsOpen = true
                    onStateUpdate(RemoteSongData(wsConnected = true))
                    onConnectionStatusChanged(true, "Подключено (WebSocket)")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    parseAndDispatchWebSocketMessage(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    isWsOpen = false
                    try {
                        webSocket.close(1000, null)
                    } catch (_: Exception) {
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    isWsOpen = false
                    onStateUpdate(RemoteSongData(wsConnected = false))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    isWsOpen = false
                    onStateUpdate(RemoteSongData(wsConnected = false))
                }
            })
        } catch (e: Exception) {
            isWsOpen = false
        }
    }

    private fun startFallbackPolling(ip: String, port: Int, token: String) {
        fallbackPollingJob?.cancel()
        fallbackPollingJob = scope.launch {
            while (isActive) {
                delay(2000L)
                if (!isWsOpen) {
                    val ok = fetchSongInfo()
                    if (ok) {
                        fetchVolumeState()
                        onConnectionStatusChanged(true, "Подключено (REST-опрос)")
                    }
                    wsReconnectTicks++
                    if (wsReconnectTicks >= 3) {
                        wsReconnectTicks = 0
                        connectWebSocket(ip, port, token)
                    }
                }
            }
        }
    }

    private fun parseAndDispatchWebSocketMessage(rawJson: String) {
        try {
            val root = JsonParser.parseString(rawJson).asJsonObject
            val type = root.get("type")?.takeIf { !it.isJsonNull }?.asString

            when (type) {
                "PLAYER_INFO", "VIDEO_CHANGED" -> {
                    val songObj = root.getAsJsonObject("song")
                    val position = root.get("position")?.takeIf { !it.isJsonNull }?.asDouble?.toLong()
                    val volume = root.get("volume")?.takeIf { !it.isJsonNull }?.asFloat
                    val isPlayingRoot = root.get("isPlaying")?.takeIf { !it.isJsonNull }?.asBoolean

                    if (songObj != null) {
                        val parsed = extractSongObject(songObj)
                        onStateUpdate(
                            parsed.copy(
                                isPlaying = isPlayingRoot ?: parsed.isPlaying,
                                currentProgressSec = position ?: parsed.currentProgressSec ?: 0L,
                                volume = volume?.coerceIn(0f, 100f) ?: parsed.volume
                            )
                        )
                    } else {
                        onStateUpdate(
                            RemoteSongData(
                                isPlaying = isPlayingRoot,
                                currentProgressSec = position,
                                volume = volume?.coerceIn(0f, 100f)
                            )
                        )
                    }
                }

                "PLAYER_STATE_CHANGED" -> {
                    val isPlaying = root.get("isPlaying")?.takeIf { !it.isJsonNull }?.asBoolean
                    val position = root.get("position")?.takeIf { !it.isJsonNull }?.asDouble?.toLong()
                    onStateUpdate(
                        RemoteSongData(
                            isPlaying = isPlaying,
                            currentProgressSec = position
                        )
                    )
                }

                "POSITION_CHANGED" -> {
                    val position = root.get("position")?.takeIf { !it.isJsonNull }?.asDouble?.toLong()
                    if (position != null) {
                        onStateUpdate(RemoteSongData(currentProgressSec = position))
                    }
                }

                "VOLUME_CHANGED" -> {
                    val volume = root.get("volume")?.takeIf { !it.isJsonNull }?.asFloat
                    if (volume != null) {
                        onStateUpdate(RemoteSongData(volume = volume.coerceIn(0f, 100f)))
                    }
                }

                else -> {
                    // Fallback if a custom WS message sends song fields directly
                    parseAndDispatchSongJson(rawJson)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun parseAndDispatchSongJson(rawJson: String) {
        try {
            val obj = JsonParser.parseString(rawJson).asJsonObject
            val data = extractSongObject(obj)
            onStateUpdate(data)
        } catch (_: Exception) {
        }
    }

    private fun extractSongObject(obj: JsonObject): RemoteSongData {
        val title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString
        val artist = obj.get("artist")?.takeIf { !it.isJsonNull }?.asString
        val imageSrc = obj.get("imageSrc")?.takeIf { !it.isJsonNull }?.asString
            ?: obj.get("coverUrl")?.takeIf { !it.isJsonNull }?.asString

        // Support both `songDuration` (Pear Desktop schema) and `duration`
        val duration = obj.get("songDuration")?.takeIf { !it.isJsonNull }?.asDouble?.toLong()
            ?: obj.get("duration")?.takeIf { !it.isJsonNull }?.asDouble?.toLong()

        // Support both `elapsedSeconds` (Pear Desktop schema) and `progress` / `position`
        val progress = obj.get("elapsedSeconds")?.takeIf { !it.isJsonNull }?.asDouble?.toLong()
            ?: obj.get("progress")?.takeIf { !it.isJsonNull }?.asDouble?.toLong()
            ?: obj.get("position")?.takeIf { !it.isJsonNull }?.asDouble?.toLong()

        val isPaused = obj.get("isPaused")?.takeIf { !it.isJsonNull }?.asBoolean
        val isPlayingDirect = obj.get("isPlaying")?.takeIf { !it.isJsonNull }?.asBoolean
        val isPlaying = isPlayingDirect ?: isPaused?.let { !it }

        val volume = obj.get("volume")?.takeIf { !it.isJsonNull }?.asFloat

        return RemoteSongData(
            title = title,
            artist = artist,
            coverUrl = imageSrc,
            isPlaying = isPlaying,
            volume = volume?.coerceIn(0f, 100f),
            currentProgressSec = progress,
            totalDurationSec = duration
        )
    }

    fun disconnect() {
        fallbackPollingJob?.cancel()
        try {
            webSocket?.close(1000, "User disconnected")
        } catch (_: Exception) {
        }
        webSocket = null
        isWsOpen = false
    }
}
