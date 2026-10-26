package com.example.ui.overlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Robust host wrapping a ComposeView for display directly in WindowManager overlays.
 * Implements LifecycleOwner, SavedStateRegistryOwner, and ViewModelStoreOwner.
 * Performs savedStateRegistryController.performAttach() and performRestore(null)
 * BEFORE setViewTreeSavedStateRegistryOwner, sets all three ViewTree*Owners on the
 * ComposeView BEFORE windowManager.addView, moves the lifecycle to RESUMED on attach,
 * and to DESTROYED (clearing ViewModelStore) on detach.
 */
class ComposeOverlayHost(
    private val context: Context,
    private val windowManager: WindowManager
) : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    companion object {
        private const val TAG = "Aura"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store

    var composeView: ComposeView? = null
        private set

    var isAttached: Boolean = false
        private set

    init {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    /**
     * Attaches the ComposeView with all owners configured BEFORE windowManager.addView.
     * Always executed on the Main Thread.
     */
    fun attach(
        params: WindowManager.LayoutParams,
        onTouchListener: View.OnTouchListener? = null,
        content: @Composable () -> Unit
    ): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            var success = false
            mainHandler.post {
                success = attach(params, onTouchListener, content)
            }
            return success
        }

        if (isAttached) return true

        try {
            val view = ComposeView(context).apply {
                setViewTreeLifecycleOwner(this@ComposeOverlayHost)
                setViewTreeSavedStateRegistryOwner(this@ComposeOverlayHost)
                setViewTreeViewModelStoreOwner(this@ComposeOverlayHost)
                if (onTouchListener != null) {
                    setOnTouchListener(onTouchListener)
                }
                setContent(content)
            }

            windowManager.addView(view, params)
            composeView = view
            isAttached = true
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            Log.d(TAG, "ComposeOverlayHost successfully attached view to WindowManager")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "ComposeOverlayHost attach failed: ${e.message}", e)
            detach()
            return false
        }
    }

    fun updateLayout(params: WindowManager.LayoutParams) {
        if (!isAttached || composeView == null) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updateLayout(params) }
            return
        }
        try {
            windowManager.updateViewLayout(composeView, params)
        } catch (e: Throwable) {
            Log.e(TAG, "ComposeOverlayHost updateLayout error: ${e.message}", e)
        }
    }

    /**
     * Detaches view, cleans up lifecycle state and ViewModelStore.
     */
    fun detach() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { detach() }
            return
        }

        if (isAttached && composeView != null) {
            try {
                windowManager.removeView(composeView)
            } catch (e: Throwable) {
                Log.e(TAG, "ComposeOverlayHost removeView error: ${e.message}", e)
            }
        }

        try {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            store.clear()
        } catch (e: Throwable) {
            Log.e(TAG, "Error cleaning up ComposeOverlayHost lifecycle: ${e.message}", e)
        }

        composeView = null
        isAttached = false
    }
}
