package com.example.core.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

data class OwnerVoiceProfile(
    val f0Mean: Float,
    val f0Std: Float,
    val spectralCentroid: Float,
    val zcrMean: Float,
    val enrolledAtMs: Long = System.currentTimeMillis()
)

data class OwnerVoiceMatchResult(
    val isMatch: Boolean,
    val scorePercent: Int,
    val message: String
)

class OwnerVoiceVerifier(private val context: Context) {
    companion object {
        private const val TAG = "Aura"
        private const val PROFILE_FILE_NAME = "owner_voice_profile.json"
        private const val SAMPLE_RATE = 16000
        private const val ENROLLMENT_DURATION_SEC = 3
    }

    private val profileFile: File get() = File(context.filesDir, PROFILE_FILE_NAME)

    fun hasProfile(): Boolean {
        return profileFile.exists() && profileFile.length() > 0
    }

    fun getProfile(): OwnerVoiceProfile? {
        if (!hasProfile()) return null
        return try {
            val json = JSONObject(profileFile.readText())
            OwnerVoiceProfile(
                f0Mean = json.optDouble("f0Mean", 0.0).toFloat(),
                f0Std = json.optDouble("f0Std", 0.0).toFloat(),
                spectralCentroid = json.optDouble("spectralCentroid", 0.0).toFloat(),
                zcrMean = json.optDouble("zcrMean", 0.0).toFloat(),
                enrolledAtMs = json.optLong("enrolledAtMs", 0L)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error loading owner voice profile", e)
            null
        }
    }

    fun deleteProfile(): Boolean {
        return if (profileFile.exists()) profileFile.delete() else true
    }

    @SuppressLint("MissingPermission")
    suspend fun recordSinglePhrase(
        durationSec: Int = 3,
        onProgress: (Float) -> Unit
    ): Result<ShortArray> = withContext(Dispatchers.IO) {
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            4096
        )

        var audioRecord: AudioRecord? = null
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                return@withContext Result.failure(IllegalStateException("AudioRecord failed to initialize"))
            }

            audioRecord.startRecording()

            val totalSamplesToRead = SAMPLE_RATE * durationSec
            val audioData = ShortArray(totalSamplesToRead)
            var samplesRead = 0
            val readChunk = ShortArray(1024)

            while (samplesRead < totalSamplesToRead) {
                val read = audioRecord.read(readChunk, 0, minOf(readChunk.size, totalSamplesToRead - samplesRead))
                if (read > 0) {
                    System.arraycopy(readChunk, 0, audioData, samplesRead, read)
                    samplesRead += read
                    onProgress(samplesRead.toFloat() / totalSamplesToRead.toFloat())
                }
            }

            audioRecord.stop()
            audioRecord.release()
            audioRecord = null

            val rms = computeRMS(audioData)
            if (rms < 150) {
                return@withContext Result.failure(IllegalStateException("Voice recording was too quiet. Please speak louder."))
            }

            Result.success(audioData)
        } catch (e: Exception) {
            Log.e(TAG, "Exception recording phrase sample", e)
            try { audioRecord?.release() } catch (_: Exception) {}
            Result.failure(e)
        }
    }

    fun combineAndSaveThreePhrases(phraseSamples: List<ShortArray>): Result<OwnerVoiceProfile> {
        if (phraseSamples.isEmpty()) {
            return Result.failure(IllegalArgumentException("No phrase samples provided."))
        }

        val totalSize = phraseSamples.sumOf { it.size }
        val combinedData = ShortArray(totalSize)
        var offset = 0
        for (sample in phraseSamples) {
            System.arraycopy(sample, 0, combinedData, offset, sample.size)
            offset += sample.size
        }

        val profile = extractProfile(combinedData, totalSize)
            ?: return Result.failure(IllegalStateException("Voice sample features could not be extracted. Please speak clearly."))

        saveProfile(profile)
        Log.i(TAG, "3-Phrase owner voice profile saved: F0=${profile.f0Mean}Hz, Std=${profile.f0Std}, Centroid=${profile.spectralCentroid}")
        return Result.success(profile)
    }

    @SuppressLint("MissingPermission")
    suspend fun enrollOwnerVoice(
        onProgress: (Float) -> Unit
    ): Result<OwnerVoiceProfile> = withContext(Dispatchers.IO) {
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            4096
        )

        var audioRecord: AudioRecord? = null
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                return@withContext Result.failure(IllegalStateException("AudioRecord failed to initialize"))
            }

            audioRecord.startRecording()

            val totalSamplesToRead = SAMPLE_RATE * ENROLLMENT_DURATION_SEC
            val audioData = ShortArray(totalSamplesToRead)
            var samplesRead = 0
            val readChunk = ShortArray(1024)

            while (samplesRead < totalSamplesToRead) {
                val read = audioRecord.read(readChunk, 0, minOf(readChunk.size, totalSamplesToRead - samplesRead))
                if (read > 0) {
                    System.arraycopy(readChunk, 0, audioData, samplesRead, read)
                    samplesRead += read
                    onProgress(samplesRead.toFloat() / totalSamplesToRead.toFloat())
                }
            }

            audioRecord.stop()
            audioRecord.release()
            audioRecord = null

            // Analyze recorded voice profile
            val profile = extractProfile(audioData, samplesRead)
                ?: return@withContext Result.failure(IllegalStateException("Voice sample too quiet or unclear. Please speak louder."))

            saveProfile(profile)
            Log.i(TAG, "Owner voice enrolled successfully: F0=${profile.f0Mean}Hz, Centroid=${profile.spectralCentroid}")
            Result.success(profile)
        } catch (e: Exception) {
            Log.e(TAG, "Exception enrolling owner voice", e)
            try { audioRecord?.release() } catch (_: Exception) {}
            Result.failure(e)
        }
    }

    fun verifyOwnerVoice(buffer: ShortArray, readSize: Int): OwnerVoiceMatchResult {
        val profile = getProfile()
            ?: return OwnerVoiceMatchResult(true, 100, "No owner voice profile set (verification disabled).")

        if (readSize < 512) {
            return OwnerVoiceMatchResult(true, 100, "Audio buffer too small for verification.")
        }

        val frameF0 = estimateF0(buffer, readSize)
        val frameCentroid = estimateSpectralCentroid(buffer, readSize)

        if (frameF0 <= 0f) {
            // Unvoiced frame or silence
            return OwnerVoiceMatchResult(true, 75, "Low energy frame.")
        }

        val f0Diff = abs(frameF0 - profile.f0Mean)
        val maxF0DiffAllowed = maxOf(profile.f0Std * 3.5f, profile.f0Mean * 0.40f)

        val centroidRatio = if (profile.spectralCentroid > 0) abs(frameCentroid - profile.spectralCentroid) / profile.spectralCentroid else 0f

        val f0Score = (1.0f - (f0Diff / maxF0DiffAllowed).coerceIn(0f, 1f))
        val centroidScore = (1.0f - (centroidRatio / 0.8f).coerceIn(0f, 1f))

        val combinedScore = ((f0Score * 0.7f + centroidScore * 0.3f) * 100).toInt()
        val isMatch = combinedScore >= 50

        val msg = if (isMatch) "Owner voice verified ($combinedScore%)" else "Unrecognized voice ($combinedScore%)"
        return OwnerVoiceMatchResult(isMatch, combinedScore, msg)
    }

    private fun saveProfile(profile: OwnerVoiceProfile) {
        val json = JSONObject().apply {
            put("f0Mean", profile.f0Mean.toDouble())
            put("f0Std", profile.f0Std.toDouble())
            put("spectralCentroid", profile.spectralCentroid.toDouble())
            put("zcrMean", profile.zcrMean.toDouble())
            put("enrolledAtMs", profile.enrolledAtMs)
        }
        profileFile.writeText(json.toString())
    }

    private fun extractProfile(data: ShortArray, size: Int): OwnerVoiceProfile? {
        val frameSize = 1024
        val step = 512
        val f0List = mutableListOf<Float>()
        val centroidList = mutableListOf<Float>()
        val zcrList = mutableListOf<Float>()

        var idx = 0
        while (idx + frameSize <= size) {
            val frame = ShortArray(frameSize)
            System.arraycopy(data, idx, frame, 0, frameSize)

            val rms = computeRMS(frame)
            if (rms > 300) { // Speech energy threshold
                val f0 = estimateF0(frame, frameSize)
                if (f0 in 70f..400f) {
                    f0List.add(f0)
                }
                val centroid = estimateSpectralCentroid(frame, frameSize)
                centroidList.add(centroid)
                val zcr = computeZCR(frame, frameSize)
                zcrList.add(zcr)
            }
            idx += step
        }

        if (f0List.isEmpty()) return null

        val f0Mean = f0List.average().toFloat()
        val f0Variance = f0List.fold(0.0) { acc, v -> acc + (v - f0Mean) * (v - f0Mean) } / f0List.size
        val f0Std = sqrt(f0Variance).toFloat().coerceAtLeast(10f)

        val spectralCentroid = if (centroidList.isNotEmpty()) centroidList.average().toFloat() else 1200f
        val zcrMean = if (zcrList.isNotEmpty()) zcrList.average().toFloat() else 0.05f

        return OwnerVoiceProfile(f0Mean, f0Std, spectralCentroid, zcrMean)
    }

    private fun computeRMS(frame: ShortArray): Float {
        var sum = 0.0
        for (v in frame) {
            sum += v * v
        }
        return sqrt(sum / frame.size).toFloat()
    }

    private fun computeZCR(frame: ShortArray, size: Int): Float {
        var crossings = 0
        for (i in 1 until size) {
            if ((frame[i] >= 0 && frame[i - 1] < 0) || (frame[i] < 0 && frame[i - 1] >= 0)) {
                crossings++
            }
        }
        return crossings.toFloat() / size
    }

    private fun estimateF0(frame: ShortArray, size: Int): Float {
        // Autocorrelation pitch estimation
        val minLag = (SAMPLE_RATE / 400) // 400 Hz max
        val maxLag = (SAMPLE_RATE / 70)  // 70 Hz min

        var maxCorr = 0.0
        var bestLag = -1

        var energyZero = 0.0
        for (i in 0 until size) {
            energyZero += frame[i].toDouble() * frame[i].toDouble()
        }
        if (energyZero < 1000.0) return 0f

        for (lag in minLag..maxLag.coerceAtMost(size / 2)) {
            var corr = 0.0
            for (i in 0 until size - lag) {
                corr += frame[i].toDouble() * frame[i + lag].toDouble()
            }
            if (corr > maxCorr) {
                maxCorr = corr
                bestLag = lag
            }
        }

        return if (bestLag > 0 && (maxCorr / energyZero) > 0.30) {
            SAMPLE_RATE.toFloat() / bestLag.toFloat()
        } else {
            0f
        }
    }

    private fun estimateSpectralCentroid(frame: ShortArray, size: Int): Float {
        // Simplified zero-crossing spectral estimate proxy
        val zcr = computeZCR(frame, size)
        return zcr * (SAMPLE_RATE / 2f)
    }
}
