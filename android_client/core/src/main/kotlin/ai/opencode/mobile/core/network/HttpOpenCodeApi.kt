package ai.opencode.mobile.core.network

import ai.opencode.mobile.core.model.AgentInfo
import ai.opencode.mobile.core.model.FileContent
import ai.opencode.mobile.core.model.FileDiff
import ai.opencode.mobile.core.model.FileNode
import ai.opencode.mobile.core.model.FileStatusEntry
import ai.opencode.mobile.core.model.HealthResponse
import ai.opencode.mobile.core.model.Message
import ai.opencode.mobile.core.model.MessageWithParts
import ai.opencode.mobile.core.model.Project
import ai.opencode.mobile.core.model.ProvidersResponse
import ai.opencode.mobile.core.model.Session
import ai.opencode.mobile.core.model.SessionStatus
import ai.opencode.mobile.core.model.SseEvent
import ai.opencode.mobile.core.model.TodoItem
import ai.opencode.mobile.core.model.WireParsers
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl

class HttpOpenCodeApi(
    private val config: ServerConfig,
    private val client: OkHttpClient = OkHttpClient(),
    private val sseClient: SseStreamClient = SseStreamClient(config, client)
) : OpenCodeApi {

    override suspend fun health(): HealthResponse = get("/global/health")

    override suspend fun projects(): List<Project> = get("/project")

    override suspend fun projectCurrent(): Project? {
        return runCatching { get<Project>("/project/current") }.getOrNull()
    }

    override suspend fun sessions(directory: String?, limit: Int): List<Session> {
        val url = config.baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("session")
            .apply {
                if (!directory.isNullOrBlank()) addQueryParameter("directory", directory)
                addQueryParameter("limit", limit.toString())
            }
            .build()
        return getByUrl(url.toString())
    }

    override suspend fun createSession(title: String?): Session {
        val bodyJson: JsonObject = buildJsonObject {
            if (!title.isNullOrBlank()) put("title", JsonPrimitive(title))
        }
        return requestJson("/session", "POST", WireParsers.json.encodeToString(JsonObject.serializer(), bodyJson))
    }

    override suspend fun updateSession(sessionID: String, title: String): Session {
        val body = """{"title":"${escapeJson(title)}"}"""
        return requestJson("/session/$sessionID", "PATCH", body)
    }

    override suspend fun deleteSession(sessionID: String) {
        request("/session/$sessionID", "DELETE").close()
    }

    override suspend fun sessionStatus(): Map<String, SessionStatus> = get("/session/status")

    override suspend fun messages(sessionID: String, limit: Int?): List<MessageWithParts> {
        val urlBuilder = config.baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("session")
            .addPathSegment(sessionID)
            .addPathSegment("message")
        if (limit != null && limit > 0) {
            urlBuilder.addQueryParameter("limit", limit.toString())
        }
        val text = requestByUrl(urlBuilder.build().toString()).use { bodyString(it) }
        return WireParsers.parseMessages(text)
    }

    override suspend fun promptAsync(sessionID: String, text: String, agent: String, model: Message.ModelInfo?) {
        val payload = WireParsers.buildPromptBody(text = text, agent = agent, model = model)
        val response = request("/session/$sessionID/prompt_async", "POST", payload)
        response.use {
            if (it.code != 204) {
                throw IOException("prompt_async failed: ${it.code}")
            }
        }
    }

    override suspend fun abort(sessionID: String) {
        val response = request("/session/$sessionID/abort", "POST")
        response.use {
            if (it.code !in 200..299) {
                throw IOException("abort failed: ${it.code}")
            }
        }
    }

    override suspend fun summarize(sessionID: String) {
        val response = request("/session/$sessionID/summarize", "POST")
        response.use {
            if (it.code !in 200..299) {
                throw IOException("summarize failed: ${it.code}")
            }
        }
    }

    override suspend fun providers(): ProvidersResponse {
        val raw = request("/config/providers", "GET").use { bodyString(it) }
        return WireParsers.parseProviders(raw)
    }

    override suspend fun agents(): List<AgentInfo> = get("/agent")

    override suspend fun sessionTodos(sessionID: String): List<TodoItem> = get("/session/$sessionID/todo")

    override suspend fun sessionDiff(sessionID: String): List<FileDiff> = get("/session/$sessionID/diff")

    override suspend fun fileList(path: String): List<FileNode> {
        val url = config.baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("file")
            .addQueryParameter("path", path)
            .build()
        return getByUrl(url.toString())
    }

    override suspend fun fileContent(path: String): FileContent {
        val url = config.baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("file")
            .addPathSegment("content")
            .addQueryParameter("path", path)
            .build()
        return getByUrl(url.toString())
    }

    override suspend fun fileStatus(): List<FileStatusEntry> = get("/file/status")

    override suspend fun findFile(query: String, limit: Int): List<String> {
        val url = config.baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("find")
            .addPathSegment("file")
            .addQueryParameter("query", query)
            .addQueryParameter("limit", limit.toString())
            .build()
        return getByUrl(url.toString())
    }

    override suspend fun respondPermission(
        sessionID: String,
        permissionID: String,
        response: OpenCodeApi.PermissionResponse
    ) {
        val body = """{"response":"${response.wireValue}"}"""
        val http = request("/session/$sessionID/permissions/$permissionID", "POST", body)
        http.use {
            if (it.code !in 200..299) {
                throw IOException("respondPermission failed: ${it.code}")
            }
        }
    }

    override fun globalEvents(): Flow<SseEvent> = sseClient.connectGlobalEvents()

    private suspend inline fun <reified T> get(path: String): T = request(path, "GET").use {
        decodeBody(it)
    }

    private suspend inline fun <reified T> getByUrl(url: String): T = requestByUrl(url).use {
        decodeBody(it)
    }

    private suspend inline fun <reified T> requestJson(path: String, method: String, body: String): T {
        return request(path, method, body).use { decodeBody(it) }
    }

    private suspend fun request(path: String, method: String, body: String? = null): Response {
        val url = config.baseUrl.trimEnd('/') + path
        return requestByUrl(url, method, body)
    }

    private suspend fun requestByUrl(url: String, method: String = "GET", body: String? = null): Response {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")

        basicAuthHeader(config.username, config.password)?.let { requestBuilder.header("Authorization", it) }

        if (method == "GET") {
            requestBuilder.get()
        } else {
            val requestBody = (body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType())
            requestBuilder.method(method, requestBody)
        }

        val response = client.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful) {
            val payload = response.body?.string().orEmpty()
            response.close()
            throw IOException("HTTP ${response.code}: $payload")
        }
        return response
    }

    private inline fun <reified T> decodeBody(response: Response): T {
        val raw = bodyString(response)
        return WireParsers.json.decodeFromString(raw)
    }

    private fun bodyString(response: Response): String {
        return response.body?.string() ?: throw IOException("Empty response body")
    }

    private fun basicAuthHeader(username: String?, password: String?): String? {
        if (username.isNullOrBlank() || password.isNullOrBlank()) return null
        val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray())
        return "Basic $token"
    }

    private fun escapeJson(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
