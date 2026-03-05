package ai.opencode.mobile.android.speech

import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

data class TranscriptionResponse(
    val requestID: String?,
    val text: String
)

class AIBuildersAudioClient(
    private val http: OkHttpClient = OkHttpClient()
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun transcribe(
        provider: SpeechProvider,
        baseUrl: String,
        token: String,
        audioFile: File,
        doubaoResourceID: String? = null,
        language: String? = null,
        prompt: String? = null,
        terms: String? = null
    ): TranscriptionResponse = withContext(Dispatchers.IO) {
        val normalized = normalizeBaseUrl(baseUrl)
        require(token.isNotBlank()) { "${provider.displayName} token is empty" }
        if (provider == SpeechProvider.DOUBAO && isOpenSpeechBaseUrl(normalized)) {
            return@withContext transcribeDoubaoOpenSpeechFlash(
                normalizedBaseUrl = normalized,
                token = token,
                audioFile = audioFile,
                doubaoResourceID = doubaoResourceID
            )
        }
        val candidateUrls = buildTranscribeUrls(normalized, provider, doubaoResourceID)

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .apply {
                if (!language.isNullOrBlank()) addFormDataPart("language", language)
                if (!prompt.isNullOrBlank()) addFormDataPart("prompt", prompt)
                if (!terms.isNullOrBlank()) addFormDataPart("terms", terms)
                if (provider == SpeechProvider.DOUBAO && !doubaoResourceID.isNullOrBlank()) {
                    addFormDataPart("resource_id", doubaoResourceID)
                }
                addFormDataPart(
                    name = "audio_file",
                    filename = audioFile.name.ifBlank { "audio.m4a" },
                    body = audioFile.asRequestBody("application/octet-stream".toMediaType())
                )
            }
            .build()

        var lastError: IOException? = null
        for ((index, url) in candidateUrls.withIndex()) {
            val requestBuilder = Request.Builder()
                .url(url)
                .post(body)
            when (provider) {
                SpeechProvider.AIBUILDERS -> {
                    requestBuilder.header("Authorization", "Bearer $token")
                }
                SpeechProvider.DOUBAO -> {
                    requestBuilder.header("X-Api-Key", token)
                    if (!doubaoResourceID.isNullOrBlank()) {
                        requestBuilder.header("X-Resource-Id", doubaoResourceID)
                    }
                }
            }
            val request = requestBuilder.build()

            val response = http.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) {
                    val payload = it.body?.string().orEmpty()
                    val fallbackAllowed = provider == SpeechProvider.DOUBAO && it.code == 404 && index < candidateUrls.lastIndex
                    if (fallbackAllowed) {
                        lastError = IOException("${provider.displayName} transcription HTTP ${it.code}: $payload")
                        return@use
                    }
                    throw IOException("${provider.displayName} transcription HTTP ${it.code}: $payload")
                }
                val raw = it.body?.string().orEmpty()
                val parsed = json.parseToJsonElement(raw).jsonObject
                val text = extractText(parsed)
                    ?: throw IOException("${provider.displayName} transcription response missing text")
                val requestID = parsed["request_id"]?.jsonPrimitive?.content
                return@withContext TranscriptionResponse(requestID = requestID, text = text)
            }
        }

        throw lastError ?: IOException("${provider.displayName} transcription failed")
    }

    suspend fun testConnection(
        provider: SpeechProvider,
        baseUrl: String,
        token: String,
        doubaoResourceID: String? = null
    ) = withContext(Dispatchers.IO) {
        val normalized = normalizeBaseUrl(baseUrl)
        require(token.isNotBlank()) { "${provider.displayName} token is empty" }
        if (provider == SpeechProvider.DOUBAO && isOpenSpeechBaseUrl(normalized)) {
            testDoubaoOpenSpeechFlash(
                normalizedBaseUrl = normalized,
                token = token,
                doubaoResourceID = doubaoResourceID
            )
            return@withContext
        }
        val candidateUrls = buildTranscribeUrls(normalized, provider, doubaoResourceID)

        if (provider == SpeechProvider.AIBUILDERS) {
            val request = Request.Builder()
                .url("$normalized/v1/embeddings")
                .header("Authorization", "Bearer $token")
                .post("""{"input":"ok"}""".toRequestBody("application/json".toMediaType()))
                .build()
            val response = http.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) {
                    val payload = it.body?.string().orEmpty()
                    throw IOException("${provider.displayName} connection test HTTP ${it.code}: $payload")
                }
            }
            return@withContext
        }

        var lastError: IOException? = null
        for ((index, url) in candidateUrls.withIndex()) {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .apply {
                    if (!doubaoResourceID.isNullOrBlank()) {
                        addFormDataPart("resource_id", doubaoResourceID)
                    }
                }
                .build()
            val request = Request.Builder()
                .url(url)
                .header("X-Api-Key", token)
                .apply {
                    if (!doubaoResourceID.isNullOrBlank()) {
                        header("X-Resource-Id", doubaoResourceID)
                    }
                }
                .post(body)
                .build()

            val response = http.newCall(request).execute()
            response.use {
                if (it.code == 401 || it.code == 403) {
                    val payload = it.body?.string().orEmpty()
                    throw IOException("Doubao connection test unauthorized HTTP ${it.code}: $payload")
                }
                val fallbackAllowed = it.code == 404 && index < candidateUrls.lastIndex
                if (fallbackAllowed) {
                    val payload = it.body?.string().orEmpty()
                    lastError = IOException("Doubao connection test HTTP ${it.code}: $payload")
                    return@use
                }
                if (it.code in 200..499) {
                    return@withContext
                }
                val payload = it.body?.string().orEmpty()
                throw IOException("${provider.displayName} connection test HTTP ${it.code}: $payload")
            }
        }
        throw lastError ?: IOException("${provider.displayName} connection test failed")
    }

    private fun buildTranscribeUrls(
        normalizedBaseUrl: String,
        provider: SpeechProvider,
        doubaoResourceID: String?
    ): List<String> {
        val urls = linkedSetOf(buildTranscribeUrl(normalizedBaseUrl, provider, doubaoResourceID))
        if (provider == SpeechProvider.DOUBAO) {
            val trimmed = normalizedBaseUrl.trimEnd('/')
            if (trimmed.endsWith("/backend")) {
                val fallbackBase = trimmed.removeSuffix("/backend")
                urls += buildTranscribeUrl(fallbackBase, provider, doubaoResourceID)
            }
        }
        return urls.toList()
    }

    private fun buildTranscribeUrl(
        normalizedBaseUrl: String,
        provider: SpeechProvider,
        doubaoResourceID: String?
    ): String {
        val builder = normalizedBaseUrl.toHttpUrl().newBuilder()
        builder.addPathSegments("v1/audio/transcriptions")
        if (provider == SpeechProvider.DOUBAO && !doubaoResourceID.isNullOrBlank()) {
            builder.addQueryParameter("resource_id", doubaoResourceID)
        }
        return builder.build().toString()
    }

    private fun isOpenSpeechBaseUrl(normalizedBaseUrl: String): Boolean {
        return runCatching { normalizedBaseUrl.toHttpUrl().host.lowercase() }
            .getOrDefault("")
            .contains("openspeech.bytedance.com")
    }

    private fun transcribeDoubaoOpenSpeechFlash(
        normalizedBaseUrl: String,
        token: String,
        audioFile: File,
        doubaoResourceID: String?
    ): TranscriptionResponse {
        val (appKey, accessKey) = splitDoubaoCredentials(token)
        val requestID = UUID.randomUUID().toString()
        val resourceID = doubaoResourceID?.takeIf { it.isNotBlank() } ?: "volc.seedasr.auc"
        val audioBase64 = Base64.getEncoder().encodeToString(audioFile.readBytes())
        val body = buildJsonObject {
            put("user", buildJsonObject { put("uid", JsonPrimitive("opencode-android")) })
            put(
                "audio",
                buildJsonObject {
                    put("format", JsonPrimitive("m4a"))
                    put("data", JsonPrimitive(audioBase64))
                }
            )
            put(
                "request",
                buildJsonObject {
                    put("model_name", JsonPrimitive("bigmodel"))
                    put("enable_itn", JsonPrimitive(true))
                }
            )
        }

        val request = Request.Builder()
            .url("${normalizedBaseUrl.trimEnd('/')}/api/v3/auc/bigmodel/recognize/flash")
            .header("Content-Type", "application/json")
            .header("X-Api-App-Key", appKey)
            .header("X-Api-Access-Key", accessKey)
            .header("X-Api-Resource-Id", resourceID)
            .header("X-Api-Request-Id", requestID)
            .header("X-Api-Sequence", "-1")
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody("application/json".toMediaType()))
            .build()

        val response = http.newCall(request).execute()
        response.use {
            val raw = it.body?.string().orEmpty()
            val parsed = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            val header = parsed?.get("header") as? JsonObject
            val code = header?.get("code")?.jsonPrimitive?.contentOrNull
            val message = header?.get("message")?.jsonPrimitive?.contentOrNull
            if (!it.isSuccessful || (code != null && code != "1000")) {
                val hint = if (message?.contains("resourceId", ignoreCase = true) == true) {
                    " (check Doubao Resource ID for this key)"
                } else {
                    ""
                }
                throw IOException("Doubao transcription HTTP ${it.code}: ${message ?: raw}$hint")
            }
            val text = extractText(parsed ?: JsonObject(emptyMap()))
                ?: throw IOException("Doubao transcription response missing text")
            return TranscriptionResponse(requestID = requestID, text = text)
        }
    }

    private fun testDoubaoOpenSpeechFlash(
        normalizedBaseUrl: String,
        token: String,
        doubaoResourceID: String?
    ) {
        val (appKey, accessKey) = splitDoubaoCredentials(token)
        val requestID = UUID.randomUUID().toString()
        val resourceID = doubaoResourceID?.takeIf { it.isNotBlank() } ?: "volc.seedasr.auc"
        val body = buildJsonObject {
            put("user", buildJsonObject { put("uid", JsonPrimitive("opencode-android-test")) })
            put("audio", buildJsonObject { put("format", JsonPrimitive("m4a")); put("data", JsonPrimitive("")) })
            put("request", buildJsonObject { put("model_name", JsonPrimitive("bigmodel")) })
        }
        val request = Request.Builder()
            .url("${normalizedBaseUrl.trimEnd('/')}/api/v3/auc/bigmodel/recognize/flash")
            .header("Content-Type", "application/json")
            .header("X-Api-App-Key", appKey)
            .header("X-Api-Access-Key", accessKey)
            .header("X-Api-Resource-Id", resourceID)
            .header("X-Api-Request-Id", requestID)
            .header("X-Api-Sequence", "-1")
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody("application/json".toMediaType()))
            .build()

        val response = http.newCall(request).execute()
        response.use {
            val raw = it.body?.string().orEmpty()
            val parsed = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            val header = parsed?.get("header") as? JsonObject
            val code = header?.get("code")?.jsonPrimitive?.contentOrNull
            val message = header?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty()
            val authOrGrantError =
                it.code == 401 || it.code == 403 ||
                    message.contains("grant", ignoreCase = true) ||
                    message.contains("resource", ignoreCase = true) ||
                    message.contains("app key", ignoreCase = true) ||
                    message.contains("access key", ignoreCase = true)
            if (authOrGrantError) {
                throw IOException("Doubao connection test HTTP ${it.code}: ${message.ifBlank { raw }}")
            }
        }
    }

    private fun splitDoubaoCredentials(token: String): Pair<String, String> {
        val trimmed = token.trim()
        if (trimmed.contains(":")) {
            val parts = trimmed.split(":", limit = 2)
            if (parts[0].isNotBlank() && parts[1].isNotBlank()) {
                return parts[0].trim() to parts[1].trim()
            }
        }
        return trimmed to trimmed
    }

    private fun extractText(payload: JsonObject): String? {
        payload["text"]?.jsonPrimitive?.contentOrNull?.let { return it }
        payload["result"]?.jsonPrimitive?.contentOrNull?.let { return it }
        val resultObj = payload["result"] as? JsonObject
        resultObj?.get("text")?.jsonPrimitive?.contentOrNull?.let { return it }
        resultObj?.get("transcript")?.jsonPrimitive?.contentOrNull?.let { return it }
        findStringField(payload, key = "text")?.let { return it }
        val data = payload["data"] as? JsonObject
        data?.get("text")?.jsonPrimitive?.contentOrNull?.let { return it }
        data?.get("result")?.jsonPrimitive?.contentOrNull?.let { return it }
        data?.get("transcript")?.jsonPrimitive?.contentOrNull?.let { return it }
        payload["transcript"]?.jsonPrimitive?.contentOrNull?.let { return it }
        val nested = payload["data"]
        if (nested != null) {
            findStringField(nested, key = "text")?.let { return it }
            findStringField(nested, key = "result")?.let { return it }
            findStringField(nested, key = "transcript")?.let { return it }
        }
        return null
    }

    private fun findStringField(node: JsonElement, key: String): String? {
        val obj = node as? JsonObject ?: return null
        obj[key]?.jsonPrimitive?.contentOrNull?.let { return it }
        for ((_, value) in obj) {
            findStringField(value, key)?.let { return it }
        }
        return null
    }

    private fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "Speech base URL is empty" }
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }
}

