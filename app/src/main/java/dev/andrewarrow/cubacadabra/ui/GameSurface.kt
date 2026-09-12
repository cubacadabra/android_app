package dev.andrewarrow.cubacadabra.ui

import android.content.Context
import android.graphics.PointF
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.andrewarrow.cubacadabra.game.GameViewModel

@Composable
internal fun RustGameSurface(
    model: GameViewModel,
    avatarPreviewMode: Boolean = false,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    AndroidView(
        modifier = modifier,
        factory = { context -> InteractiveGameSurface(context, model, avatarPreviewMode) },
        update = { view -> view.setAvatarPreviewMode(avatarPreviewMode) },
    )
}

private class InteractiveGameSurface(
    context: Context,
    private val model: GameViewModel,
    initialPreviewMode: Boolean,
) : SurfaceView(context), SurfaceHolder.Callback {
    private var previewMode = initialPreviewMode
    private val density = resources.displayMetrics.density.coerceAtLeast(.1f)
    private var safeInsets = Insets.NONE
    private val uiPointers = mutableSetOf<Int>()
    private val cameraTouches = linkedMapOf<Int, PointF>()
    private val cameraTouchStarts = linkedMapOf<Int, PointF>()
    private var previousPinchDistance: Float? = null
    private var cameraTouchMoved = false

    init {
        isFocusable = true
        isClickable = true
        holder.addCallback(this)
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            safeInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            updateUiViewport()
            insets
        }
        ViewCompat.requestApplyInsets(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        model.createRenderer(holder.surface, width.toFloat(), height.toFloat(), previewMode)
        setAvatarPreviewMode(previewMode)
        updateUiViewport()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (width > 0 && height > 0) {
            model.createRenderer(holder.surface, width.toFloat(), height.toFloat(), previewMode)
            model.resizeRenderer(width.toFloat(), height.toFloat(), previewMode)
            updateUiViewport()
        }
    }

    fun setAvatarPreviewMode(enabled: Boolean) {
        previewMode = enabled
        model.setAvatarPreviewMode(enabled, previewMode)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = model.destroyRenderer(previewMode)

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateUiViewport()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                beginPointer(event.getPointerId(index), event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    movePointer(event.getPointerId(index), event.getX(index), event.getY(index))
                }
                updatePinchDistance()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                finishPointer(event.getPointerId(index), event.getX(index), event.getY(index), 2, true)
                updatePinchDistance()
            }
            MotionEvent.ACTION_CANCEL -> {
                val ids = (uiPointers + cameraTouches.keys).toList()
                ids.forEach { pointerId ->
                    val point = cameraTouches[pointerId]
                    finishPointer(pointerId, (point?.x ?: 0f), (point?.y ?: 0f), 3, false)
                }
                updatePinchDistance()
            }
        }
        return true
    }

    private fun beginPointer(pointerId: Int, rawX: Float, rawY: Float) {
        val id = pointerId.toLong() + 1L
        val x = rawX / density
        val y = rawY / density
        if (!previewMode && model.uiPointer(id, 0, x, y)) {
            uiPointers += pointerId
        } else {
            cameraTouches[pointerId] = PointF(x, y)
            cameraTouchStarts[pointerId] = PointF(x, y)
            if (previewMode && cameraTouches.size >= 2) {
                model.morphPreviewMoveEnded()
                model.morphPreviewLookEnded()
                cameraTouchMoved = true
            }
        }
        updatePinchDistance()
    }

    private fun movePointer(pointerId: Int, rawX: Float, rawY: Float) {
        val id = pointerId.toLong() + 1L
        val x = rawX / density
        val y = rawY / density
        if (pointerId in uiPointers) {
            model.uiPointer(id, 1, x, y)
        } else if (cameraTouches[pointerId] != null) {
            val previous = cameraTouches[pointerId]!!
            if (cameraTouches.size == 1 && previousPinchDistance == null) {
                if (previewMode) {
                    val start = cameraTouchStarts[pointerId] ?: previous
                    if (start.x < width / density / 2f) model.morphPreviewMoveChanged(x - start.x, y - start.y)
                    else model.morphPreviewLookChanged(x - previous.x, y - previous.y)
                } else {
                    model.lookBy(x - previous.x, y - previous.y)
                }
                cameraTouchMoved = true
            }
            cameraTouches[pointerId] = PointF(x, y)
        }
    }

    private fun finishPointer(pointerId: Int, rawX: Float, rawY: Float, phase: Int, allowWorldTap: Boolean) {
        val id = pointerId.toLong() + 1L
        val x = rawX / density
        val y = rawY / density
        val wasCameraInteraction = cameraTouches.isNotEmpty()
        if (uiPointers.remove(pointerId)) {
            model.uiPointer(id, phase, x, y)
        } else {
            cameraTouches.remove(pointerId)
            cameraTouchStarts.remove(pointerId)
        }
        if (cameraTouches.isEmpty()) {
            if (previewMode) {
                model.morphPreviewMoveEnded()
                model.morphPreviewLookEnded()
            } else if (allowWorldTap && wasCameraInteraction && !cameraTouchMoved) {
                model.requestUsernameEdit()
            }
            cameraTouchMoved = false
        } else if (previewMode) {
            cameraTouchStarts.keys.toList().forEach { remaining ->
                cameraTouches[remaining]?.let { point -> cameraTouchStarts[remaining] = point }
            }
        }
    }

    private fun updatePinchDistance() {
        if (cameraTouches.size < 2) {
            previousPinchDistance = null
            return
        }
        val points = cameraTouches.values.take(2)
        val dx = points[0].x - points[1].x
        val dy = points[0].y - points[1].y
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        previousPinchDistance?.let { previous ->
            if (previewMode) model.morphPreviewZoomChangedBy(kotlin.math.ln((distance / previous).coerceAtLeast(.01f)))
            else model.zoomBy((1f + (distance - previous) / 100f).coerceAtLeast(.1f))
            cameraTouchMoved = true
        }
        if (previewMode) {
            model.morphPreviewMoveEnded()
            model.morphPreviewLookEnded()
        }
        previousPinchDistance = distance
    }

    private fun updateUiViewport() {
        if (width <= 0 || height <= 0) return
        model.setUiViewport(
            width = width / density,
            height = height / density,
            scale = density,
            safeTop = safeInsets.top / density,
            safeRight = safeInsets.right / density,
            safeBottom = safeInsets.bottom / density,
            safeLeft = safeInsets.left / density,
            avatarPreviewMode = previewMode,
        )
    }
}
