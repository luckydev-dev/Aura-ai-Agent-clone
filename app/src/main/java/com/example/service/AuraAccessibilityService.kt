package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import java.util.ArrayDeque
import com.example.core.agent.ScreenNode
import kotlinx.coroutines.*

class AuraAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AuraAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    data class ForegroundInfo(val foregroundPackage: String, val lastExternalPackage: String, val recentExternalPackages: List<Pair<String, Long>>)
    @Volatile private var lastExternalPackage = ""
    @Volatile private var foregroundPackage = ""
    private val recentExternalPackages = ArrayDeque<Pair<String, Long>>()
    private val ownPackage by lazy { packageName }
    private val ignoredPackages = setOf("com.android.systemui", "com.google.android.inputmethod.latin", "com.android.inputmethod.latin", "com.android.launcher", "com.android.launcher3", "com.google.android.apps.nexuslauncher")

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        runCatching {
            val info = serviceInfo
            info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            serviceInfo = info
        }.onFailure { Log.e("Aura", "Could not enable interactive-window retrieval", it) }
        updateForegroundFromWindows()
        runCatching { com.example.AuraApplication.instance.overlayController.edgeLightingLifecycleChanged() }
            .onFailure { error: Throwable -> Log.w("Aura", "Edge-lighting connect hook failed", error) }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        if (instance === this) instance = null
        runCatching { com.example.AuraApplication.instance.overlayController.edgeLightingLifecycleChanged() }
            .onFailure { error: Throwable -> Log.w("Aura", "Edge-lighting disconnect hook failed", error) }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString().orEmpty()
        if (pkg.isNotBlank() && pkg != ownPackage && pkg !in ignoredPackages && !pkg.contains("inputmethod", true)) {
            synchronized(recentExternalPackages) {
                if (lastExternalPackage != pkg) {
                    lastExternalPackage = pkg
                    recentExternalPackages.addLast(pkg to System.currentTimeMillis())
                    while (recentExternalPackages.size > 8) recentExternalPackages.removeFirst()
                }
            }
            if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ) foregroundPackage = pkg
        }
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) updateForegroundFromWindows()
    }

    fun getForegroundInfo(): ForegroundInfo {
        updateForegroundFromWindows()
        return ForegroundInfo(foregroundPackage, lastExternalPackage, synchronized(recentExternalPackages) { recentExternalPackages.toList() })
    }

    private fun updateForegroundFromWindows() {
        val candidate = runCatching {
            windows?.asSequence()
                ?.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                ?.sortedByDescending { it.layer }
                ?.mapNotNull { it.root?.packageName?.toString() }
                ?.firstOrNull { it.isNotBlank() && it != ownPackage && it !in ignoredPackages && !it.contains("inputmethod", true) }
        }.onFailure { Log.w("Aura", "Could not inspect accessibility windows", it) }.getOrNull()
        if (!candidate.isNullOrBlank()) {
            foregroundPackage = candidate
            if (candidate != lastExternalPackage) {
                lastExternalPackage = candidate
                synchronized(recentExternalPackages) {
                    recentExternalPackages.addLast(candidate to System.currentTimeMillis())
                    while (recentExternalPackages.size > 8) recentExternalPackages.removeFirst()
                }
            }
        }
    }

    private fun selectedExternalRoot(): AccessibilityNodeInfo? {
        updateForegroundFromWindows()
        val external = runCatching {
            windows?.asSequence()
                ?.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                ?.sortedByDescending { it.layer }
                ?.firstOrNull { win ->
                    val pkg = win.root?.packageName?.toString().orEmpty()
                    pkg.isNotBlank() && pkg != ownPackage && pkg !in ignoredPackages && !pkg.contains("inputmethod", true)
                }
        }.onFailure { Log.w("Aura", "Could not select external window", it) }.getOrNull()
        val root = external?.root ?: rootInActiveWindow?.takeIf { it.packageName?.toString() != ownPackage }
        if (root == null && foregroundPackage == ownPackage) Log.w("Aura", "Aura is foreground; refusing to return Aura UI as external screen")
        return root
    }

    override fun onInterrupt() {}

    /**
     * Finds nodes by text across active window.
     */
    fun findNodesByText(text: String): List<AccessibilityNodeInfo> {
        val root = selectedExternalRoot() ?: return emptyList()
        return root.findAccessibilityNodeInfosByText(text) ?: emptyList()
    }

    fun findNodeByQuery(query: String): AccessibilityNodeInfo? {
        val root = selectedExternalRoot() ?: return null
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            val t = node.text?.toString() ?: ""
            val d = node.contentDescription?.toString() ?: ""
            val id = node.viewIdResourceName?.toString() ?: ""
            if (t.contains(query, ignoreCase = true) || d.contains(query, ignoreCase = true) || id.contains(query, ignoreCase = true)) {
                return node
            }
            for (i in 0 until node.childCount) {
                val child = search(node.getChild(i))
                if (child != null) return child
            }
            return null
        }
        return search(root)
    }

    /**
     * Traverses in-memory active window hierarchy and extracts compact ScreenNodes (sub-30ms).
     */
    fun extractScreenNodes(): List<ScreenNode> {
        val root = rootInActiveWindow ?: return emptyList()
        val nodes = mutableListOf<ScreenNode>()
        var counter = 1

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val rect = Rect()
            node.getBoundsInScreen(rect)

            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val viewId = node.viewIdResourceName?.substringAfterLast("/") ?: ""
            val cls = node.className?.toString()?.substringAfterLast(".") ?: ""
            val pkg = node.packageName?.toString() ?: ""

            val isClickable = node.isClickable
            val isEditable = node.isEditable
            val isScrollable = node.isScrollable
            val isFocusable = node.isFocusable

            // Include interactive or informative nodes, skip 0-size invisible bounds
            if (pkg != ownPackage && pkg != "com.android.systemui" && rect.width() > 0 && rect.height() > 0) {
                if (text.isNotBlank() || desc.isNotBlank() || viewId.isNotBlank() || isClickable || isEditable || isScrollable) {
                    nodes.add(
                        ScreenNode(
                            text = text.trim(),
                            contentDescription = desc.trim(),
                            resourceId = viewId,
                            className = cls,
                            bounds = "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]",
                            centerX = rect.centerX(),
                            centerY = rect.centerY(),
                            isClickable = isClickable,
                            isFocusable = isFocusable,
                            index = counter++,
                            isEditable = isEditable,
                            isScrollable = isScrollable,
                            packageName = pkg
                        )
                    )
                }
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(root)
        if (nodes.isEmpty()) {
            val info = getForegroundInfo()
            Log.w("Aura", "External screen tree empty; foreground=" + info.foregroundPackage + "; lastExternal=" + info.lastExternalPackage)
        }
        return nodes
    }

    /**
     * Sets text on an editable node directly via accessibility arguments.
     */
    fun setTextOnActiveNode(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        return false
    }

    /**
     * Finds the deepest clickable node containing (x, y) or climbs up to a clickable ancestor.
     */
    fun findClickableNodeAt(x: Float, y: Float): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val ix = x.toInt()
        val iy = y.toInt()
        var bestNode: AccessibilityNodeInfo? = null

        fun search(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.contains(ix, iy)) {
                if (node.isClickable) {
                    bestNode = node
                }
                for (i in 0 until node.childCount) {
                    search(node.getChild(i))
                }
            }
        }
        search(root)

        if (bestNode != null) return bestNode

        fun findDeepest(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.contains(ix, iy)) {
                for (i in 0 until node.childCount) {
                    val childHit = findDeepest(node.getChild(i))
                    if (childHit != null) return childHit
                }
                return node
            }
            return null
        }

        var curr = findDeepest(root)
        while (curr != null) {
            if (curr.isClickable) return curr
            curr = curr.parent
        }
        return null
    }

    /**
     * Directly clicks the clickable node at (x, y) via Accessibility IPC,
     * completely bypassing InputDispatcher and any screen overlays.
     */
    fun performClickAt(x: Float, y: Float): Boolean {
        val node = findClickableNodeAt(x, y) ?: return false
        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return clicked
    }

    /**
     * Performs a click gesture via Accessibility API as fallback to root.
     */
    fun clickAt(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Locks the device screen via system Accessibility API.
     */
    fun lockDevice(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
        return false
    }
}
