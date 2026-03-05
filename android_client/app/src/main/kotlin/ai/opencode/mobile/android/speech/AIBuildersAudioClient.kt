package ai.opencode.mobile.android.speech

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
 
    private fun extractText(payload: JsonObject): String? {
        payload["text"]?.jsonPrimitive?.contentOrNull?.let { return it }
        payload["result"]?.jsonPrimitive?.contentOrNull?.let { return it }
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

