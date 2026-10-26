package com.example.core.unlock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.Rect
import android.util.Log
import com.example.core.input.InputController
import com.example.core.root.RootManager
import com.example.core.settings.EncryptedKeyStorage
import com.example.service.AuraAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.hypot

data class GridDetectionResult(
    val points: List<Pair<Float, Float>>,
    val method: String, // "SAVED_CALIBRATION", "UI_TREE", "SCREENSHOT_BLOBS", "HEURISTIC_UNCALIBRATED"
    val isCalibrated: Boolean
)

/**
 * Accurately determines the real (x, y) screen pixel centres of the 9 pattern lock dots.
 * Corrects aspect-ratio square projection, respects user calibration, and checks OEM lockscreens.
 */
class PatternGridLocator(
    private val context: Context,
    private val rootManager: RootManager,
    private val inputController: InputController,
    private val encryptedStorage: EncryptedKeyStorage
) {
    companion object {
        private const val TAG = "Aura"
    }

    /**
     * Resolves the 9 dot centres ordered 0..8 (row-major).
     */
    suspend fun locatePatternGrid(): GridDetectionResult {
        // 1. Try Saved User Calibration first (highest precedence)
        val saved = detectFromSavedCalibration()
        if (saved != null && saved.size == 9) {
            Log.i(TAG, "Pattern grid loaded from saved user calibration")
            return GridDetectionResult(saved, "SAVED_CALIBRATION", isCalibrated = true)
        }

        // 2. Try UI tree inspection (Accessibility Node or uiautomator dump)
        val treePoints = detectFromUiTree()
        if (treePoints != null && treePoints.size == 9) {
            Log.i(TAG, "Pattern grid detected via UI tree LockPatternView bounds")
            return GridDetectionResult(treePoints, "UI_TREE", isCalibrated = true)
        }

        // 3. Try Screenshot computer vision (with cursor hidden)
        val cvPoints = detectFromScreenshot()
        if (cvPoints != null && cvPoints.size == 9) {
            Log.i(TAG, "Pattern grid detected via screenshot computer vision blobs")
            return GridDetectionResult(cvPoints, "SCREENSHOT_BLOBS", isCalibrated = true)
        }

        // 4. Last resort heuristic (AOSP centered square: centres at 1/6, 1/2, 5/6)
        val heuristic = computeHeuristicAosp()
        Log.w(TAG, "Pattern grid using last-resort uncalibrated AOSP heuristic")
        return GridDetectionResult(heuristic, "HEURISTIC_UNCALIBRATED", isCalibrated = false)
    }

    private suspend fun detectFromUiTree(): List<Pair<Float, Float>>? {
        val a11y = AuraAccessibilityService.instance
        if (a11y != null) {
            val root = a11y.rootInActiveWindow
            if (root != null) {
                val patternNode = findPatternNodeRecursive(root)
                if (patternNode != null) {
                    val rect = Rect()
                    patternNode.getBoundsInScreen(rect)
                    if (rect.width() > 100 && rect.height() > 100) {
                        return computeGridFromBounds(rect)
                    }
                }
            }
        }

        // Try root uiautomator dump wrapped with cursor hidden
        val dumpRes = inputController.withCursorHidden {
            rootManager.execute("uiautomator dump /data/local/tmp/aura_ui.xml && cat /data/local/tmp/aura_ui.xml")
        }
        if (dumpRes.success && dumpRes.stdout.isNotBlank()) {
            val xml = dumpRes.stdout
            val patternRegex = Regex("""class="[^"]*(?:LockPatternView|PatternView|pattern)[^"]*"\s+bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"""")
            val match = patternRegex.find(xml)
            if (match != null) {
                val (l, t, r, b) = match.destructured
                val rect = Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt())
                return computeGridFromBounds(rect)
            }
        }
        return null
    }

    private fun findPatternNodeRecursive(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
        val cls = node.className?.toString() ?: ""
        val id = node.viewIdResourceName?.toString() ?: ""
        if (cls.contains("LockPatternView", ignoreCase = true) ||
            cls.contains("PatternView", ignoreCase = true) ||
            id.contains("lockPattern", ignoreCase = true) ||
            id.contains("pattern_view", ignoreCase = true) ||
            id.contains("patternView", ignoreCase = true) ||
            id.contains("keyguard_pattern", ignoreCase = true) ||
            id.contains("patternContainer", ignoreCase = true)
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findPatternNodeRecursive(child)
            if (found != null) return found
        }
        return null
    }

    /**
     * Projects the 3x3 pattern grid as an exact square centered within rect,
     * avoiding rectangular vertical stretching that causes AI strokes to miss dots.
     */
    private fun computeGridFromBounds(rect: Rect): List<Pair<Float, Float>> {
        val points = mutableListOf<Pair<Float, Float>>()
        val rawW = rect.width().toFloat()
        val rawH = rect.height().toFloat()

        val side = minOf(rawW, rawH)
        val left = rect.left + (rawW - side) / 2f
        val top = rect.top + (rawH - side) / 2f

        val fractions = listOf(1f / 6f, 3f / 6f, 5f / 6f)
        for (r in fractions) {
            for (c in fractions) {
                points.add(Pair(left + c * side, top + r * side))
            }
        }
        return points
    }

    private fun detectFromSavedCalibration(): List<Pair<Float, Float>>? {
        val jsonStr = encryptedStorage.getPatternCalibration()
        if (jsonStr.isBlank()) return null
        return try {
            val obj = JSONObject(jsonStr)
            val arr = obj.getJSONArray("points")
            val list = mutableListOf<Pair<Float, Float>>()
            for (i in 0 until arr.length()) {
                val pt = arr.getJSONObject(i)
                list.add(Pair(pt.getDouble("x").toFloat(), pt.getDouble("y").toFloat()))
            }
            if (list.size == 9) list else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing saved pattern calibration: ${e.message}")
            null
        }
    }

    private suspend fun detectFromScreenshot(): List<Pair<Float, Float>>? {
        val shotPath = "/data/local/tmp/aura_lock_shot.png"
        val res = inputController.withCursorHidden {
            rootManager.execute("screencap -p $shotPath")
        }
        if (!res.success) return null

        val localFile = File(shotPath)
        if (!localFile.exists() || localFile.length() == 0L) return null

        val bitmap = BitmapFactory.decodeFile(shotPath) ?: return null
        val w = bitmap.width
        val h = bitmap.height

        // Search lower 65% of screen
        val startY = (h * 0.40f).toInt()
        val searchH = (h * 0.55f).toInt()

        val scale = 0.25f
        val scaledW = (w * scale).toInt()
        val scaledH = (searchH * scale).toInt()
        val scaledBitmap = Bitmap.createScaledBitmap(
            Bitmap.createBitmap(bitmap, 0, startY, w, searchH),
            scaledW, scaledH, true
        )

        val candidateBlobs = mutableListOf<Point>()
        val lumThreshold = 180

        for (y in 8 until scaledH - 8 step 4) {
            for (x in 8 until scaledW - 8 step 4) {
                val pixel = scaledBitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                if (lum > lumThreshold) {
                    candidateBlobs.add(Point(x, y))
                }
            }
        }

        if (candidateBlobs.size >= 9) {
            val clustered = clusterBlobsTo9Points(candidateBlobs, scaledW, scaledH)
            if (clustered != null && clustered.size == 9) {
                return clustered.map { pt ->
                    Pair(pt.x / scale, startY + (pt.y / scale))
                }
            }
        }
        return null
    }

    private fun clusterBlobsTo9Points(blobs: List<Point>, w: Int, h: Int): List<Point>? {
        val stepX = w / 3f
        val stepY = h / 3f
        val result = mutableListOf<Point>()

        for (r in 0..2) {
            for (c in 0..2) {
                val targetX = (c + 0.5f) * stepX
                val targetY = (r + 0.5f) * stepY
                val closest = blobs.minByOrNull { hypot(it.x - targetX, it.y - targetY) }
                if (closest != null && hypot(closest.x - targetX, closest.y - targetY) < stepX * 0.45f) {
                    result.add(closest)
                }
            }
        }
        return if (result.size == 9) result else null
    }

    fun computeHeuristicAosp(): List<Pair<Float, Float>> {
        val size = inputController.getDisplayMetrics()
        val w = size.x.toFloat()
        val h = size.y.toFloat()

        // Modern 19.5:9 and 20:9 displays place the square LockPatternView in the lower portion
        val side = (w * 0.78f).coerceAtMost(h * 0.38f)
        val left = (w - side) / 2f
        val top = h * 0.52f

        val points = mutableListOf<Pair<Float, Float>>()
        val fractions = listOf(1f / 6f, 3f / 6f, 5f / 6f)
        for (r in fractions) {
            for (c in fractions) {
                points.add(Pair(left + c * side, top + r * side))
            }
        }
        return points
    }

    /**
     * Generates a custom calibrated grid based on manual offset and scale adjustments.
     */
    fun computeCustomGrid(offsetX: Float, offsetY: Float, scaleFactor: Float): List<Pair<Float, Float>> {
        val size = inputController.getDisplayMetrics()
        val w = size.x.toFloat()
        val h = size.y.toFloat()

        val baseSide = ((w * 0.78f).coerceAtMost(h * 0.38f) * scaleFactor).coerceIn(w * 0.45f, w * 0.95f)
        val baseLeft = (w - baseSide) / 2f + offsetX
        val baseTop = (h * 0.52f) + offsetY

        val points = mutableListOf<Pair<Float, Float>>()
        val fractions = listOf(1f / 6f, 3f / 6f, 5f / 6f)
        for (r in fractions) {
            for (c in fractions) {
                points.add(Pair(baseLeft + c * baseSide, baseTop + r * baseSide))
            }
        }
        return points
    }

    fun getSavedCalibration(): List<Pair<Float, Float>>? = detectFromSavedCalibration()

    fun resetCalibration() {
        encryptedStorage.setPatternCalibration("")
    }

    /**
     * Saves user manual or wizard calibration.
     */
    fun saveCalibration(points: List<Pair<Float, Float>>) {
        val obj = JSONObject()
        val arr = JSONArray()
        points.forEach { pt ->
            val pObj = JSONObject().apply {
                put("x", pt.first.toDouble())
                put("y", pt.second.toDouble())
            }
            arr.put(pObj)
        }
        obj.put("points", arr)
        obj.put("timestamp", System.currentTimeMillis())
        encryptedStorage.setPatternCalibration(obj.toString())
    }
}
