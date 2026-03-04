package ai.opencode.mobile.android.speech

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidAudioRecorder {
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null

    suspend fun start(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            stop()
            val output = File(context.cacheDir, "opencode-recording-${System.currentTimeMillis()}.m4a")
            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(64_000)
                setOutputFile(output.absolutePath)
                prepare()
                start()
            }
            recorder = mediaRecorder
            currentFile = output
        }
    }

    suspend fun stop(): File? = withContext(Dispatchers.IO) {
        val file = currentFile
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        currentFile = null
        file
    }
}

