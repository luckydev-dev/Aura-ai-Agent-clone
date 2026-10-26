package com.example.core.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.*
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

sealed class VoskModelStatus {
    object NotDownloaded : VoskModelStatus()
    data class Downloading(val progressPercent: Int, val bytesDownloaded: Long, val totalBytes: Long) : VoskModelStatus()
    data class Ready(val modelPath: String) : VoskModelStatus()
    data class Error(val message: String) : VoskModelStatus()
}

class VoskModelManager(private val context: Context) {

    companion object {
        private const val TAG = "Aura"
        const val MODEL_DIR_NAME = "vosk-model-small-en-in-0.4"
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"
        const val EXPECTED_SIZE_APPROX = 36 * 1024 * 1024L // ~36 MB
    }

    private val _status = MutableStateFlow<VoskModelStatus>(VoskModelStatus.NotDownloaded)
    val status: StateFlow<VoskModelStatus> = _status.asStateFlow()

    init {
        checkExistingModel()
    }

    fun getModelDirectory(): File {
        return File(context.filesDir, MODEL_DIR_NAME)
    }

    fun isModelReady(): Boolean {
        val dir = getModelDirectory()
        if (!dir.exists() || !dir.isDirectory) return false
        // Check for essential Vosk model components
        val amDir = File(dir, "am")
        val graphDir = File(dir, "graph")
        val confDir = File(dir, "conf")
        return (amDir.exists() || File(dir, "final.mdl").exists() || confDir.exists())
    }

    fun checkExistingModel(): Boolean {
        val ready = isModelReady()
        if (ready) {
            _status.value = VoskModelStatus.Ready(getModelDirectory().absolutePath)
        } else {
            if (_status.value !is VoskModelStatus.Downloading) {
                _status.value = VoskModelStatus.NotDownloaded
            }
        }
        return ready
    }

    suspend fun downloadAndExtractModel(): Result<String> = withContext(Dispatchers.IO) {
        if (isModelReady()) {
            val path = getModelDirectory().absolutePath
            _status.value = VoskModelStatus.Ready(path)
            return@withContext Result.success(path)
        }

        _status.value = VoskModelStatus.Downloading(0, 0L, EXPECTED_SIZE_APPROX)
        val tempZip = File(context.cacheDir, "vosk_model_temp.zip")

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        try {
            Log.i(TAG, "Starting Vosk model download from $MODEL_URL")
            val request = Request.Builder().url(MODEL_URL).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val err = "Download failed: HTTP ${response.code}"
                Log.e(TAG, err)
                _status.value = VoskModelStatus.Error(err)
                return@withContext Result.failure(IOException(err))
            }

            val body = response.body ?: throw IOException("Empty response body")
            val contentLength = if (body.contentLength() > 0) body.contentLength() else EXPECTED_SIZE_APPROX

            body.byteStream().use { input ->
                FileOutputStream(tempZip).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L
                    var lastProgress = 0

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        val progress = ((totalRead * 100) / contentLength).toInt().coerceIn(0, 100)
                        if (progress != lastProgress) {
                            lastProgress = progress
                            _status.value = VoskModelStatus.Downloading(progress, totalRead, contentLength)
                        }
                    }
                }
            }

            Log.i(TAG, "Vosk zip downloaded (${tempZip.length()} bytes). Extracting...")
            val targetDir = getModelDirectory()
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()

            unzip(tempZip, context.filesDir)
            tempZip.delete()

            if (isModelReady()) {
                val path = getModelDirectory().absolutePath
                Log.i(TAG, "Vosk model extracted successfully to $path")
                _status.value = VoskModelStatus.Ready(path)
                Result.success(path)
            } else {
                val err = "Extraction completed but model structure not verified."
                Log.e(TAG, err)
                _status.value = VoskModelStatus.Error(err)
                Result.failure(IllegalStateException(err))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading/extracting Vosk model: ${e.message}", e)
            tempZip.delete()
            val userMsg = if (e is IOException && (e.message?.contains("Unable to resolve host") == true || e.message?.contains("Failed to connect") == true)) {
                "Offline: Internet connection required to download 'Hey Aura' wake model (~36 MB)."
            } else {
                "Failed to download wake model: ${e.message}"
            }
            _status.value = VoskModelStatus.Error(userMsg)
            Result.failure(e)
        }
    }

    private fun unzip(zipFile: File, targetDirectory: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val file = File(targetDirectory, entry.name)
                val canonicalDest = targetDirectory.canonicalPath
                val canonicalEntry = file.canonicalPath
                if (!canonicalEntry.startsWith(canonicalDest)) {
                    throw SecurityException("Zip traversal attempt: ${entry.name}")
                }

                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { fos ->
                        val buffer = ByteArray(8192)
                        var count: Int
                        while (zis.read(buffer).also { count = it } != -1) {
                            fos.write(buffer, 0, count)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
