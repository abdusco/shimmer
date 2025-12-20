package dev.abdus.apps.shimmer

import android.opengl.Matrix
import kotlin.math.max

class ViewportManager {
    private var surfaceAspectRatio = 1f
    private var currentImageAspectRatio = 1f
    private var previousImageAspectRatio: Float? = null
    
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val previousProjectionMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val previousMvpMatrix = FloatArray(16)
    private var projectionDirty = true
    
    val parallaxAnimator = SmoothingFloatAnimator().apply { reset(0.5f) }
    
    init {
        // Set up static view matrix (camera looking at origin)
        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, 1f, 0f, 0f, -1f, 0f, 1f, 0f)
        Matrix.setIdentityM(projectionMatrix, 0)
        Matrix.setIdentityM(previousProjectionMatrix, 0)
        Matrix.setIdentityM(mvpMatrix, 0)
        Matrix.setIdentityM(previousMvpMatrix, 0)
    }
    
    fun setSurfaceDimensions(dimensions: SurfaceDimensions) {
        surfaceAspectRatio = dimensions.aspectRatio.coerceAtLeast(0.01f)
        projectionDirty = true
    }
    
    fun setCurrentImageAspectRatio(aspectRatio: Float) {
        currentImageAspectRatio = aspectRatio
        projectionDirty = true
    }
    
    fun setImageTransitionState(previousAspectRatio: Float?) {
        previousImageAspectRatio = previousAspectRatio
        projectionDirty = true
    }
    
    fun setParallaxTarget(offset: Float) {
        parallaxAnimator.setTarget(offset)
    }
    
    fun resetParallax(offset: Float) {
        parallaxAnimator.reset(offset)
        projectionDirty = true
    }
    
    fun tick(): Boolean {
        val changed = parallaxAnimator.tick()
        if (changed) projectionDirty = true
        return changed
    }

    fun getMvpMatrices(): Pair<FloatArray, FloatArray?> {
        if (projectionDirty && currentImageAspectRatio > 0f) {
            computeProjectionMatrix(projectionMatrix, currentImageAspectRatio)
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
            projectionDirty = false
        }
        
        val prevMatrix = previousImageAspectRatio?.let { prevAspect ->
            if (prevAspect > 0f) {
                computeProjectionMatrix(previousProjectionMatrix, prevAspect)
                Matrix.multiplyMM(previousMvpMatrix, 0, previousProjectionMatrix, 0, viewMatrix, 0)
                previousMvpMatrix
            } else null
        }
        
        return mvpMatrix to prevMatrix
    }
    
    private fun getProjectionMatrices(): Pair<FloatArray, FloatArray?> {
        if (projectionDirty) {
            computeProjectionMatrix(projectionMatrix, currentImageAspectRatio)
            projectionDirty = false
        }
        
        val prevMatrix = previousImageAspectRatio?.let { prevAspect ->
            computeProjectionMatrix(previousProjectionMatrix, prevAspect)
            previousProjectionMatrix
        }
        
        return projectionMatrix to prevMatrix
    }
    
    private fun computeProjectionMatrix(target: FloatArray, imageAspectRatio: Float) {
        // Defensive checks to prevent NaN or invalid values
        if (surfaceAspectRatio <= 0f || imageAspectRatio <= 0f) return
        
        val ratio = surfaceAspectRatio / imageAspectRatio
        if (!ratio.isFinite()) return
        
        val zoom = max(1f, ratio)
        val scaledAspect = zoom / ratio
        if (!scaledAspect.isFinite()) return
        
        val panFraction = parallaxAnimator.currentValue * (scaledAspect - 1f) / scaledAspect
        if (!panFraction.isFinite()) return
        
        val left = -1f + 2f * panFraction
        val right = left + 2f / scaledAspect
        
        if (!left.isFinite() || !right.isFinite()) return
        if (left >= right) return // Invalid bounds
        
        Matrix.orthoM(target, 0, left, right, -1f / zoom, 1f / zoom, 0f, 1f)
    }
}