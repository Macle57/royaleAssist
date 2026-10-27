package com.macle.royaleassist.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.abs

/**
 * Compose needs a LifecycleOwner, SavedStateRegistryOwner and ViewModelStoreOwner on the view
 * tree. Inside an Activity they come for free; for a WindowManager overlay we provide them.
 */
class OverlayViewOwner : SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    init {
        savedState.performAttach()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    fun attach(root: View) {
        root.setViewTreeLifecycleOwner(this)
        root.setViewTreeSavedStateRegistryOwner(this)
        root.setViewTreeViewModelStoreOwner(this)
    }

    fun resume() { registry.currentState = Lifecycle.State.RESUMED }

    fun destroy() {
        if (registry.currentState != Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}

/**
 * Container for the bubble that takes over all touches: drags move the window (using raw screen
 * coordinates, so moving the window under the finger does not cause jitter), short taps click.
 */
@SuppressLint("ViewConstructor")
class DragFrame(
    context: Context,
    private val onDrag: (dx: Int, dy: Int) -> Unit,
    private val onDragEnd: () -> Unit,
    private val onTap: () -> Unit,
) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    override fun onInterceptTouchEvent(ev: MotionEvent) = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY; lastX = downX; lastY = downY; dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (abs(ev.rawX - downX) > slop || abs(ev.rawY - downY) > slop)) dragging = true
                if (dragging) {
                    onDrag((ev.rawX - lastX).toInt(), (ev.rawY - lastY).toInt())
                    lastX = ev.rawX; lastY = ev.rawY
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) onDragEnd() else { performClick(); onTap() }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> { if (dragging) onDragEnd(); dragging = false }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
