package ai.opencode.mobile.android.speech

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        baseUrl: String,
        token: String,
        audioFile: File,
        language: String? = null,
        prompt: String? = null,
        terms: String? = null
    ): TranscriptionResponse = withContext(Dispatchers.IO) {
        val normalized = normalizeBaseUrl(baseUrl)
        require(token.isNotBlank()) { "AI Builder token is empty" }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .apply {
                if (!language.isNullOrBlank()) addFormDataPart("language", language)
                if (!prompt.isNullOrBlank()) addFormDataPart("prompt", prompt)
                if (!terms.isNullOrBlank()) addFormDataPart("terms", terms)
                addFormDataPart(
                    name = "audio_file",
                    filename = audioFile.name.ifBlank { "audio.m4a" },
                    body = audioFile.asRequestBody("application/octet-stream".toMediaType())
                )
            }
            .build()

        val request = Request.Builder()
            .url("$normalized/v1/audio/transcriptions")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        val response = http.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) {
                val payload = it.body?.string().orEmpty()
                throw IOException("AI Builder transcription HTTP ${it.code}: $payload")
            }
            val raw = it.body?.string().orEmpty()
            val parsed = json.parseToJsonElement(raw).jsonObject
            val text = parsed["text"]?.jsonPrimitive?.content
                ?: throw IOException("AI Builder transcription response missing text")
            val requestID = parsed["request_id"]?.jsonPrimitive?.content
            TranscriptionResponse(requestID = requestID, text = text)
        }
    }

    suspend fun testConnection(baseUrl: String, token: String) = withContext(Dispatchers.IO) {
        val normalized = normalizeBaseUrl(baseUrl)
        require(token.isNotBlank()) { "AI Builder token is empty" }
        val request = Request.Builder()
            .url("$normalized/v1/embeddings")
            .header("Authorization", "Bearer $token")
            .post("""{"input":"ok"}""".toRequestBody("application/json".toMediaType()))
            .build()
        val response = http.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) {
                val payload = it.body?.string().orEmpty()
                throw IOException("AI Builder connection test HTTP ${it.code}: $payload")
            }
        }
    }

    private fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "AI Builder base URL is empty" }
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }
}

