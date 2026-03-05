package ai.opencode.mobile.core.network

import ai.opencode.mobile.core.model.SseEvent
import ai.opencode.mobile.core.model.WireParsers
import java.io.IOException
import kotlin.math.min
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request

class SseStreamClient(
    private val config: ServerConfig,
    private val client: OkHttpClient = OkHttpClient()
) {
    fun connectGlobalEvents(): Flow<SseEvent> = flow {
        var retries = 0

        while (currentCoroutineContext().isActive) {
            try {
                val request = Request.Builder()
                    .url(config.baseUrl.trimEnd('/') + "/global/event")
                    .header("Accept", "text/event-stream")
                    .header("Cache-Control", "no-cache")
                    .apply {
                        basicAuthHeader(config.username, config.password)?.let { header("Authorization", it) }
                    }
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("SSE HTTP ${response.code}")
                    }

                    retries = 0
                    val source = response.body?.source() ?: throw IOException("SSE empty body")
                    val eventLines = mutableListOf<String>()

                    suspend fun flushEvent() {
                        if (eventLines.isEmpty()) return
                        val payload = eventLines.joinToString(separator = "\n").trim()
                        eventLines.clear()
                        if (payload.isEmpty() || payload == "[DONE]") return
                        runCatching { WireParsers.json.decodeFromString<SseEvent>(payload) }
                            .onSuccess { emit(it) }
                    }

                    while (!source.exhausted() && currentCoroutineContext().isActive) {
                        val rawLine = source.readUtf8Line() ?: break
                        val line = rawLine.trimEnd('\r')
                        when {
                            line.isEmpty() -> flushEvent()
                            line.startsWith(":") -> Unit
                            line.startsWith("data:") -> eventLines += line.removePrefix("data:").trimStart()
                        }
                    }
                    flushEvent()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                retries += 1
                delay(backoffMillis(retries))
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun backoffMillis(retries: Int): Long {
        val capped = min(retries, 5)
        return min(30_000L, 1_000L shl (capped - 1).coerceAtLeast(0))
    }

    private fun basicAuthHeader(username: String?, password: String?): String? {
        if (username.isNullOrBlank() || password.isNullOrBlank()) return null
        val token = java.util.Base64.getEncoder().encodeToString("$username:$password".toByteArray())
        return "Basic $token"
    }
}
