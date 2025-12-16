package dev.abdus.apps.shimmer

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import android.view.animation.DecelerateInterpolator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

/**
 * Payload containing image data for the renderer.
 * @property original The original unprocessed bitmap
 * @property blurred List of progressively blurred bitmaps (excluding original).
 *                   The number of blur levels determines the keyframe count.
 *                   Examples:
 *                   - 0 levels: No blur animation (just original)
 *                   - 1 level: [100% blur] → 2 states (original, blurred)
 *                   - 2 levels: [50% blur, 100% blur] → 3 states (original, 50%, 100%)
 * @property blurRadii List of blur radii corresponding to each blurred bitmap (in pixels).
 *                     Size must match blurred.size. Used to map between different blur amounts.
 * @property id Optional URI identifying the source image. Used to detect when the same
 *                     image is reloaded with different blur settings.
 */
data class ImageSet(
    val original: Bitmap,
    val blurred: List<Bitmap> = emptyList(),
    val blurRadii: List<Float> = emptyList(),
    val id: String = "",
)

/**
 * Blend mode for duotone effect application.
 */
enum class DuotoneBlendMode {
    NORMAL,      // Simple linear interpolation (mix)
    SOFT_LIGHT, // Soft light blend mode
    SCREEN,     // Screen blend mode
}

/**
 * Duotone color effect configuration.
 * @property lightColor Color for highlights (default: white)
 * @property darkColor Color for shadows (default: black)
 * @property opacity Effect opacity (0.0 = disabled, 1.0 = full effect)
 * @property blendMode How the duotone effect is blended with the original image
 */
data class Duotone(
    val lightColor: Int = Color.WHITE,
    val darkColor: Int = Color.BLACK,
    val opacity: Float = 0f,
    val blendMode: DuotoneBlendMode = DuotoneBlendMode.NORMAL,
)

/**
 * Touch data for multitouch chromatic aberration.
 * @property id Unique identifier for the touch point
 * @property x Normalized x coordinate (0.0-1.0)
 * @property y Normalized y coordinate (0.0-1.0)
 * @property action The type of touch action (down, move, up)
 */
data class TouchData(
    val id: Int,
    val x: Float,
    val y: Float,
    val action: TouchAction,
)

/**
 * Touch action types for multitouch chromatic aberration.
 */
enum class TouchAction {
    DOWN, MOVE, UP,
}

/**
 * Represents a touch point for chromatic aberration effect (internal).
 * @property id Unique identifier for the touch point
 * @property x Normalized x coordinate (0.0-1.0)
 * @property y Normalized y coordinate (0.0-1.0)
 * @property radius Current circle radius (0.0-1.0)
 * @property intensity Current effect intensity (0.0-1.0, for fade-out)
 * @property radiusAnimator Animator for circle growth/shrinkage
 * @property fadeAnimator Animator for fade-out when touch is released
 */
private data class TouchPoint(
    val id: Int,
    var x: Float,
    var y: Float,
    var radius: Float = 0f,
    var intensity: Float = 1f,
    val radiusAnimator: TickingFloatAnimator = TickingFloatAnimator(4000, DecelerateInterpolator()),
    val fadeAnimator: TickingFloatAnimator = TickingFloatAnimator(2000, DecelerateInterpolator()),
    var isReleased: Boolean = false,
)

/**
 * OpenGL ES 2.0 renderer for the Shimmer wallpaper.
 * Handles rendering of images with blur, duotone effects, and parallax scrolling.
 */
class ShimmerRenderer(private val callbacks: Callbacks) :
    GLSurfaceView.Renderer {

    /**
     * Callbacks for the renderer to communicate with the host.
     */
    interface Callbacks {
        /** Request a new frame to be rendered */
        fun requestRender()

        /** Notifies when the renderer has finished its initial setup and is ready to receive commands. */
        fun onRendererReady()

        /** Notifies when all animations (blur, image fade) have completed and renderer is ready to accept a new image. */
        fun onReadyForNextImage()

        /** Notifies when surface dimensions change */
        fun onSurfaceDimensionsChanged(width: Int, height: Int)
    }

    // Effect transition duration (for blur, duotone, and image fade animations)
    private var effectTransitionDurationMillis = 1500

    // Chromatic aberration settings
    private var chromaticAberrationEnabled = true
    private var chromaticAberrationIntensity = 0.5f
    private var chromaticAberrationFadeDuration = 500

    // Image state
    // Managed by RenderState
    
    // Pending image to load when surface becomes available
    private var pendingImageSet: ImageSet? = null

    // Surface state
    private var surfaceCreated = false
    private var surfaceWidthPx: Int = 0
    private var surfaceHeightPx: Int = 0
    private var surfaceAspect = 1f
    private var bitmapAspect = 1f
    private var previousBitmapAspect: Float? = null

    // Transformation matrices
    private val mvpMatrix = FloatArray(16)
    private val previousMvpMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val previousProjectionMatrix = FloatArray(16)
    private val tempProjectionMatrix = FloatArray(16)
    private val viewModelMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)

    // OpenGL resources
    private lateinit var pictureHandles: PictureHandles
    private var pictureSet: GLPictureSet? = null
    private var previousPictureSet: GLPictureSet? = null
    private var tileSize: Int = 0

    // Animation state
    private val animationController = AnimationController(effectTransitionDurationMillis).apply {
        onImageAnimationComplete = {
            Log.d(TAG, "Animation completed, notifying host")
            callbacks.onReadyForNextImage()
        }
    }

    /**
     * Sets the wallpaper image with pre-processed blur levels.
     * @param imageSet Image payload containing original and blur level bitmaps
     */
    fun setImage(imageSet: ImageSet) {
        Log.d(TAG, "setImage: called, ${imageSet.original.width}x${imageSet.original.height}, blurred=${imageSet.blurred.size}")
        
        if (!surfaceCreated) {
            Log.w(TAG, "setImage: Surface not created, deferring image load")
            pendingImageSet = imageSet
            return
        }
        
        val baseState = animationController.targetRenderState
        val newTargetState = baseState.copy(imageSet = imageSet)

        if (pictureSet != null) {
            previousBitmapAspect = bitmapAspect
        }
        bitmapAspect = if (imageSet.original.height == 0) 1f else imageSet.original.width.toFloat() / imageSet.original.height

        updatePictureSet(imageSet)

        animationController.updateTargetState(newTargetState)
        recomputeProjectionMatrix()
    }

    /**
     * Sets the duration for effect transitions (blur, duotone, dim, and image fade animations).
     * @param durationMillis Duration in milliseconds
     */
    fun setEffectTransitionDuration(durationMillis: Long) {
        effectTransitionDurationMillis = durationMillis.toInt()
        animationController.setDuration(effectTransitionDurationMillis)
    }

    /**
     * Exposes whether the renderer currently has a valid surface/context.
     * Used by the host to decide when it is safe to issue GL work.
     */
    fun isSurfaceReady(): Boolean = surfaceCreated

    /**
     * Marks the surface/context as lost so subsequent commands are deferred.
     */
    fun onSurfaceDestroyed() {
        surfaceCreated = false
        pictureSet = null
        previousPictureSet = null
    }

    /**
     * Toggles the blur effect on/off with animation.
     */
    fun toggleBlur() {
        Log.d(TAG, "toggleBlur: called")
        val baseState = animationController.targetRenderState
        val newTargetState = baseState.copy(
            blurPercent = if (baseState.blurPercent > 0f) 0f else 1f
        )
        animationController.updateTargetState(newTargetState)
        callbacks.requestRender()
    }

    fun enableBlur(enable: Boolean = true, immediate: Boolean = false) {
        Log.d(TAG, "enableBlur: called with enable=$enable, immediate=$immediate")
        val baseState = animationController.targetRenderState
        val targetBlurAmount = if (enable) 1f else 0f

        if (baseState.blurPercent != targetBlurAmount) {
            val newTargetState = baseState.copy(blurPercent = targetBlurAmount)
            if (immediate) {
                animationController.setRenderStateImmediately(newTargetState)
                animationController.blurAmountAnimator.reset()
            } else {
                animationController.updateTargetState(newTargetState)
            }
            callbacks.requestRender()
        }
    }

    /**
     * Seeds the current render state to the desired blur without animation.
     * Intended for first frame / context restore before any commands animate.
     */
    fun enableBlurImmediately(enable: Boolean) {
        val target = animationController.targetRenderState.copy(
            blurPercent = if (enable) 1f else 0f
        )
        animationController.setRenderStateImmediately(target)
        animationController.blurAmountAnimator.reset()
    }

    /**
     * Sets the dim overlay amount when blurred.
     * @param amount Dim amount (0.0 = no dimming, 1.0 = full dimming)
     */
    fun setUserDimAmount(amount: Float) {
        val newAmount = amount.coerceIn(0f, 1f)
        val baseState = animationController.targetRenderState
        if (baseState.dimAmount != newAmount) {
            val newTargetState = baseState.copy(dimAmount = newAmount)
            animationController.updateTargetState(newTargetState)
            callbacks.requestRender()
        }
    }

    /**
     * Configures the duotone color effect.
     * @param enabled Whether the duotone effect is enabled
     * @param alwaysOn If true, effect is always visible; if false, only visible when blurred
     * @param duotone Duotone colors to blend when enabled
     */
    fun setDuotoneSettings(
        enabled: Boolean,
        alwaysOn: Boolean,
        duotone: Duotone,
    ) {
        val newTargetDuotone = Duotone(
            lightColor = duotone.lightColor,
            darkColor = duotone.darkColor,
            opacity = if (enabled) 1f else 0f,
            blendMode = duotone.blendMode
        )
        val baseState = animationController.targetRenderState
        if (baseState.duotone != newTargetDuotone || baseState.duotoneAlwaysOn != alwaysOn) {
            val newTargetState = baseState.copy(
                duotone = newTargetDuotone,
                duotoneAlwaysOn = alwaysOn
            )
            animationController.updateTargetState(newTargetState)
            callbacks.requestRender()
        }
    }

    /**
     * Configures the film grain overlay.
     * @param enabled Whether grain is applied
     * @param amount Strength of the grain (0.0 = off, 1.0 = strong)
     * @param scale Normalized grain size slider (0.0 = fine, 1.0 = coarse)
     */
    fun setGrainSettings(
        enabled: Boolean,
        amount: Float,
        scale: Float,
    ) {
        val clampedAmount = amount.coerceIn(0f, 1f)
        val clampedScale = scale.coerceIn(0f, 1f)
        val baseState = animationController.targetRenderState
        val newGrain = GrainSettings(
            enabled = enabled,
            amount = clampedAmount,
            scale = clampedScale
        )
        if (baseState.grain != newGrain) {
            val newTargetState = baseState.copy(grain = newGrain)
            animationController.updateTargetState(newTargetState)
            callbacks.requestRender()
        }
    }

    /**
     * Configures chromatic aberration effect settings.
     * @param enabled Whether the effect is enabled
     * @param intensity Effect intensity (0.0 - 1.0)
     * @param fadeDurationMillis Duration of fade-out animation in milliseconds
     */
    fun setChromaticAberrationSettings(enabled: Boolean, intensity: Float, fadeDurationMillis: Int) {
        chromaticAberrationEnabled = enabled
        chromaticAberrationIntensity = intensity.coerceIn(0f, 1f)
        chromaticAberrationFadeDuration = fadeDurationMillis.coerceAtLeast(0)
        
        // If disabled, clear all active touch points
        if (!enabled) {
            touchPoints.clear()
        }
        callbacks.requestRender()
    }

    /**
     * Sets touch points for chromatic aberration effect.
     * @param touches List of touch data with normalized coordinates (0-1 range)
     */
    fun setTouchPoints(touches: List<TouchData>) {
        if (!chromaticAberrationEnabled) return
        
        for (touch in touches) {
            val existingTouch = touchPoints.find { it.id == touch.id && !it.isReleased }
            when (touch.action) {
                TouchAction.DOWN -> {
                    if (existingTouch != null) {
                         // Already active, just update position
                        existingTouch.x = touch.x
                        existingTouch.y = touch.y
                    } else if (touchPoints.size < maxTouchPoints) {
                        // Create new touch point
                        val newTouch = TouchPoint(
                            id = touch.id,
                            x = touch.x,
                            y = touch.y,
                            radius = 0f,
                            intensity = 1f,
                            radiusAnimator = TickingFloatAnimator(4000, DecelerateInterpolator()),
                            fadeAnimator = TickingFloatAnimator(chromaticAberrationFadeDuration, DecelerateInterpolator())
                        )
                        newTouch.radiusAnimator.start(startValue = 0f, endValue = 1f)
                        // Set fadeAnimator to not be running, but with intensity at 1.0
                        newTouch.fadeAnimator.reset()
                        newTouch.fadeAnimator.currentValue = 1f
                        touchPoints.add(newTouch)
                    }
                }
                TouchAction.MOVE -> { 
                    existingTouch?.let { 
                        it.x = touch.x
                        it.y = touch.y
                    }
                }
                TouchAction.UP -> { 
                    existingTouch?.let { 
                        it.radiusAnimator.start(startValue = it.radius, endValue = 0f)
                        it.fadeAnimator.start(startValue = it.intensity, endValue = 0f)
                        it.isReleased = true
                    }
                }
            }
        }
        
        // Don't remove touches here - let updateTouchPointAnimations() handle cleanup
        // when animations complete. This allows fade-out animations to run.
        
        callbacks.requestRender()
    }

    // Touch point management for chromatic aberration - moved to class body
    private fun updateTouchPointAnimations() {
        val iterator = touchPoints.iterator()
        while (iterator.hasNext()) {
            val touchPoint = iterator.next()
            
            // Tick animators (they handle their own running state internally)
            touchPoint.radiusAnimator.tick()
            touchPoint.radius = touchPoint.radiusAnimator.currentValue
            
            touchPoint.fadeAnimator.tick()
            touchPoint.intensity = touchPoint.fadeAnimator.currentValue
            
            // Remove touch points that have fully faded out AND finished their radius animation
            if (!touchPoint.radiusAnimator.isRunning && !touchPoint.fadeAnimator.isRunning && 
                touchPoint.radius <= 0.01f && touchPoint.intensity <= 0.01f) {
                iterator.remove()
            }
        }
    }

    // Touch point management for chromatic aberration
    private val touchPoints = mutableListOf<TouchPoint>()
    private val maxTouchPoints = 10

    companion object {
        private const val TAG = "ShimmerRenderer"
        // Grain size in source image pixels
        const val GRAIN_SIZE_MIN_IMAGE_PX = 1.5f
        const val GRAIN_SIZE_MAX_IMAGE_PX = 3f
        // Limit max grain intensity to avoid garish look (user slider 1.0 -> this value)
        const val GRAIN_INTENSITY_MAX = 0.30f

        //language=c
        private const val PICTURE_VERTEX_SHADER_CODE = """
            uniform mat4 uMVPMatrix;
            attribute vec4 aPosition;
            attribute vec2 aTexCoords;
            varying vec2 vTexCoords;
            varying vec2 vPosition;

            void main() {
                vTexCoords = aTexCoords;
                vPosition = aPosition.xy;
                gl_Position = uMVPMatrix * aPosition;
            }
        """

        //language=glsl
        private const val PICTURE_FRAGMENT_SHADER_CODE = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif

            uniform sampler2D uTexture;
            uniform float uAlpha;
            uniform vec3 uDuotoneLightColor;
            uniform vec3 uDuotoneDarkColor;
            uniform float uDuotoneOpacity;
            uniform int uDuotoneBlendMode;
            uniform float uDimAmount;
            uniform float uGrainAmount;
            uniform vec2 uGrainCount;
            uniform int uTouchPointCount;
            uniform vec3 uTouchPoints[10];
            uniform float uTouchIntensities[10];
            uniform vec2 uScreenSize;
            varying vec2 vTexCoords;
            varying vec2 vPosition;

            // Luminosity calculation macro (using Rec. 709 luma coefficients)
            #define LUMINOSITY(c) (0.2126 * (c).r + 0.7152 * (c).g + 0.0722 * (c).b)

            // Soft light blend mode (Photoshop formula)
            vec3 blendSoftLight(vec3 base, vec3 blend) {
                // If blend <= 0.5: result = base - (1 - 2*blend) * base * (1 - base)
                // If blend > 0.5: result = base + (2*blend - 1) * (sqrt(base) - base)
                vec3 result1 = base - (1.0 - 2.0 * blend) * base * (1.0 - base);
                vec3 result2 = base + (2.0 * blend - 1.0) * (sqrt(base) - base);
                return mix(result1, result2, step(0.5, blend));
            }

            // Screen blend mode
            vec3 blendScreen(vec3 base, vec3 blend) {
                // Screen: 1 - (1 - base) * (1 - blend)
                return 1.0 - (1.0 - base) * (1.0 - blend);
            }

            // Apply duotone effect to a color
            vec3 applyDuotone(vec3 color) {
                float lum = LUMINOSITY(vec4(color, 1.0));
                vec3 duotone = mix(uDuotoneDarkColor, uDuotoneLightColor, lum);
                
                // Apply blend mode based on uDuotoneBlendMode
                vec3 blended;
                
                if (uDuotoneBlendMode == 0) {
                    // NORMAL
                    blended = duotone;
                } else if (uDuotoneBlendMode == 1) {
                    // SOFT_LIGHT
                    blended = blendSoftLight(color, duotone);
                } else {
                    // SCREEN
                    blended = blendScreen(color, duotone);
                }
                
                // Apply opacity: mix between original and blended result
                return mix(color, blended, uDuotoneOpacity);
            }

            // Simplex 2D noise
            // From: https://github.com/patriciogonzalezvivo/thebookofshaders/blob/master/glsl/noise/simplex_2d.glsl
            vec3 permute(vec3 x) { return mod(((x*34.0)+1.0)*x, 289.0); }

            float snoise(vec2 v){
                const vec4 C = vec4(0.211324865405187, 0.366025403784439, -0.577350269189626, 0.024390243902439);
                vec2 i  = floor(v + dot(v, C.yy) );
                vec2 x0 = v -   i + dot(i, C.xx);
                vec2 i1;
                i1 = (x0.x > x0.y) ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
                vec4 x12 = x0.xyxy + C.xxzz;
                x12.xy -= i1;
                i = mod(i, 289.0);
                vec3 p = permute( permute( i.y + vec3(0.0, i1.y, 1.0 ))
                + i.x + vec3(0.0, i1.x, 1.0 ));
                vec3 m = max(0.5 - vec3(dot(x0,x0), dot(x12.xy,x12.xy), dot(x12.zw,x12.zw)), 0.0);
                m = m*m ;
                m = m*m ;
                vec3 x = 2.0 * fract(p * C.www) - 1.0;
                vec3 h = abs(x) - 0.5;
                vec3 ox = floor(x + 0.5);
                vec3 a0 = x - ox;
                m *= 1.79284291400159 - 0.85373472095314 * ( a0*a0 + h*h );
                vec3 g;
                g.x  = a0.x  * x0.x  + h.x  * x0.y;
                g.yz = a0.yz * x12.xz + h.yz * x12.yw;
                return 130.0 * dot(m, g);
            }

            void main() {
                // Dithering (screen space is fine for dithering to break gradients)
                // Use a simple high-freq hash for dithering
                float noise = (fract(sin(dot(gl_FragCoord.xy, vec2(12.9898,78.233))) * 43758.5453) - 0.5) / 64.0;

                // Film grain in image space (attached to geometry)
                float grain = 0.0;
                if (uGrainAmount > 0.0) {
                    // Map -1..1 to 0..1
                    vec2 normPos = vPosition * 0.5 + 0.5;
                    
                    // Use Simplex noise which lacks the square grid artifacts of value/perlin noise
                    vec2 noiseCoords = normPos * uGrainCount;
                    grain = snoise(noiseCoords) * uGrainAmount;
                }

                // Chromatic aberration from touch points
                vec2 screenPos = vPosition * 0.5 + 0.5;
                
                // Calculate aspect ratio for circular (not oval) effect regions
                float aspectRatio = uScreenSize.x / uScreenSize.y;
                
                // Accumulate chromatic aberration offset
                vec2 totalChromaticOffset = vec2(0.0);
                float maxChromaticAbrStrength = 0.6;
                
                // Process all touch points and accumulate the effect
                for (int i = 0; i < 10; i++) {
                    if (i >= uTouchPointCount) break;
                    
                    vec3 touchPoint = uTouchPoints[i];
                    float touchIntensity = uTouchIntensities[i];
                    
                    if (touchIntensity > 0.0 && touchPoint.z > 0.0) {
                        vec2 touchCenter = touchPoint.xy;
                        
                        // Calculate delta in normalized coordinates
                        vec2 delta = screenPos - touchCenter;
                        
                        // Correct for aspect ratio to make circles appear circular
                        // Scale X up to compensate for narrow screens (aspectRatio < 1)
                        // or scale Y down for wide screens (aspectRatio > 1)
                        vec2 aspectCorrectedDelta = vec2(delta.x / aspectRatio, delta.y);
                        
                        float distance = length(aspectCorrectedDelta);
                        float normalizedDistance = distance / touchPoint.z;
                        
                        // Calculate effect strength with smooth falloff
                        float effectStrength = touchIntensity * (1.0 - smoothstep(0.0, 1.0, normalizedDistance));
                        
                        if (effectStrength > 0.0) {
                            // Direction from touch center (normalized)
                            vec2 direction = length(delta) > 0.0 ? normalize(delta) : vec2(0.0);
                            
                            // Accumulate chromatic offset - push colors outward from touch point
                            // The offset is in texture coordinate space
                            float offsetAmount = effectStrength * 0.1; // Adjust strength
                            totalChromaticOffset += direction * offsetAmount;
                            maxChromaticAbrStrength = max(maxChromaticAbrStrength, effectStrength);
                        }
                    }
                }
                
                // Sample RGB channels at different offsets and apply duotone to each
                // When there's no chromatic aberration, offsets are zero so all samples are identical
                vec4 colorR = texture2D(uTexture, vTexCoords + totalChromaticOffset);
                vec4 colorG = texture2D(uTexture, vTexCoords);
                vec4 colorB = texture2D(uTexture, vTexCoords - totalChromaticOffset);
                
                vec3 duotonedR = applyDuotone(colorR.rgb);
                vec3 duotonedG = applyDuotone(colorG.rgb);
                vec3 duotonedB = applyDuotone(colorB.rgb);
                
                // Combine RGB channels for chromatic aberration, or use center sample when no offset
                vec3 finalColor = length(totalChromaticOffset) > 0.001 
                    ? vec3(duotonedR.r, duotonedG.g, duotonedB.b)
                    : duotonedG;
                
                // Apply dimming
                finalColor = mix(finalColor, vec3(0.0), uDimAmount);

                finalColor = clamp(finalColor + noise + grain, 0.0, 1.0);
                gl_FragColor = vec4(finalColor, colorG.a * uAlpha);
            }
        """

    }


    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
        surfaceCreated = true
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        try {
            // Picture shader
            val pictureVertexShader = GLUtil.loadShader(GLES20.GL_VERTEX_SHADER, PICTURE_VERTEX_SHADER_CODE)
            val pictureFragmentShader = GLUtil.loadShader(GLES20.GL_FRAGMENT_SHADER, PICTURE_FRAGMENT_SHADER_CODE)
            val pictureProgram = GLUtil.createAndLinkProgram(pictureVertexShader, pictureFragmentShader)
        pictureHandles = PictureHandles(
            program = pictureProgram,
            attribPosition = GLES20.glGetAttribLocation(pictureProgram, "aPosition"),
            attribTexCoords = GLES20.glGetAttribLocation(pictureProgram, "aTexCoords"),
            uniformMvpMatrix = GLES20.glGetUniformLocation(pictureProgram, "uMVPMatrix"),
            uniformTexture = GLES20.glGetUniformLocation(pictureProgram, "uTexture"),
            uniformAlpha = GLES20.glGetUniformLocation(pictureProgram, "uAlpha"),
            uniformDuotoneLight = GLES20.glGetUniformLocation(pictureProgram, "uDuotoneLightColor"),
            uniformDuotoneDark = GLES20.glGetUniformLocation(pictureProgram, "uDuotoneDarkColor"),
            uniformDuotoneOpacity = GLES20.glGetUniformLocation(pictureProgram, "uDuotoneOpacity"),
            uniformDuotoneBlendMode = GLES20.glGetUniformLocation(pictureProgram, "uDuotoneBlendMode"),
            uniformDimAmount = GLES20.glGetUniformLocation(pictureProgram, "uDimAmount"),
            uniformGrainAmount = GLES20.glGetUniformLocation(pictureProgram, "uGrainAmount"),
            uniformGrainCount = GLES20.glGetUniformLocation(pictureProgram, "uGrainCount"),
            uniformTouchPointCount = GLES20.glGetUniformLocation(pictureProgram, "uTouchPointCount"),
            uniformTouchPoints = GLES20.glGetUniformLocation(pictureProgram, "uTouchPoints"),
            uniformTouchIntensities = GLES20.glGetUniformLocation(pictureProgram, "uTouchIntensities"),
            uniformScreenSize = GLES20.glGetUniformLocation(pictureProgram, "uScreenSize"),
        )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize shaders", e)
            surfaceCreated = false
            throw e
        }

        val maxTextureSize = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0)
        tileSize = kotlin.math.min(512, maxTextureSize[0])
        if (tileSize == 0) {
            tileSize = 512
        }

        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, 1f, 0f, 0f, -1f, 0f, 1f, 0f)

        // Load any pending image that was deferred
        pendingImageSet?.let { imageSet ->
            Log.d(TAG, "onSurfaceCreated: Loading pending image")
            pendingImageSet = null
            setImage(imageSet)
        }

        callbacks.onRendererReady()
    }

    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        surfaceWidthPx = width
        surfaceHeightPx = height
        surfaceAspect = if (height == 0) 1f else width.toFloat() / height
        recomputeProjectionMatrix()
        callbacks.onSurfaceDimensionsChanged(width, height)
        callbacks.requestRender()
    }

    override fun onDrawFrame(gl: GL10) {
        if (!surfaceCreated) {
            return
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val currentRenderState = animationController.currentRenderState

        // Recompute projection matrix with animated parallax offset
        recomputeProjectionMatrix()

        // Compute transformation matrices
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.multiplyMM(viewModelMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(previousMvpMatrix, 0, previousProjectionMatrix, 0, viewModelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewModelMatrix, 0)

        // Update animations
        val isAnimating = animationController.tick()

        // blurAmount is normalized 0-1, convert to actual keyframe count for rendering
        val blurPercent = currentRenderState.blurPercent.coerceIn(0f, 1f)

        val duotone = currentRenderState.duotone.copy(
            opacity = if (currentRenderState.duotoneAlwaysOn) currentRenderState.duotone.opacity else (currentRenderState.duotone.opacity * blurPercent),
            blendMode = currentRenderState.duotone.blendMode // Explicitly preserve blendMode
        )

        val finalDimAmount = currentRenderState.dimAmount * blurPercent

        val grainState = currentRenderState.grain
        // Apply intensity scaling here so the slider (0..1) maps to useful range (0..GRAIN_INTENSITY_MAX)
        val grainAmount = if (grainState.enabled) grainState.amount * GRAIN_INTENSITY_MAX else 0f
        
        var grainCountX = 0f
        var grainCountY = 0f
        if (grainAmount > 0f) {
            val imageWidth = currentRenderState.imageSet.original.width.toFloat()
            val imageHeight = currentRenderState.imageSet.original.height.toFloat()
            if (imageWidth > 0 && imageHeight > 0) {
                val clampedScale = grainState.scale.coerceIn(0f, 1f)
                val grainSizePx = GRAIN_SIZE_MIN_IMAGE_PX + (GRAIN_SIZE_MAX_IMAGE_PX - GRAIN_SIZE_MIN_IMAGE_PX) * clampedScale
                grainCountX = imageWidth / grainSizePx
                grainCountY = imageHeight / grainSizePx
            }
        }

        val currentImageAlpha = animationController.imageTransitionAnimator.currentValue
        val previousImageAlpha = 1f - animationController.imageTransitionAnimator.currentValue

        // Update touch point animations
        updateTouchPointAnimations()

        // Prepare touch point data for shader
        val touchPointCount = touchPoints.size.coerceAtMost(maxTouchPoints)
        val touchPointsArray = FloatArray(touchPointCount * 3)
        val touchIntensitiesArray = FloatArray(touchPointCount)
        for (i in 0 until touchPointCount) {
            val touch = touchPoints[i]
            touchPointsArray[i * 3] = touch.x
            touchPointsArray[i * 3 + 1] = touch.y
            touchPointsArray[i * 3 + 2] = touch.radius
            // Apply the global intensity multiplier
            touchIntensitiesArray[i] = touch.intensity * chromaticAberrationIntensity
        }
        val screenSizeArray = floatArrayOf(surfaceWidthPx.toFloat(), surfaceHeightPx.toFloat())

        // Draw previous image (fade out during transition)
        previousPictureSet?.drawFrame(
            pictureHandles,
            tileSize,
            previousMvpMatrix,
            blurPercent,
            previousImageAlpha,
            duotone,
            finalDimAmount,
            grainAmount,
            grainCountX,
            grainCountY,
            touchPointCount = touchPointCount,
            touchPoints = touchPointsArray,
            touchIntensities = touchIntensitiesArray,
            screenSize = screenSizeArray,
        )

        // Draw current image (fade in during transition)
        pictureSet?.drawFrame(
            pictureHandles,
            tileSize,
            mvpMatrix,
            blurPercent,
            currentImageAlpha,
            duotone,
            finalDimAmount,
            grainAmount,
            grainCountX,
            grainCountY,
            touchPointCount = touchPointCount,
            touchPoints = touchPointsArray,
            touchIntensities = touchIntensitiesArray,
            screenSize = screenSizeArray,
        )

        // Clean up previous picture set after fade completes
        if (!animationController.imageTransitionAnimator.isRunning && previousImageAlpha <= 0f) {
            previousPictureSet = null
            previousBitmapAspect = null
        }

        // Request another frame if any animation is still running or touch points are animating
        val touchPointsAnimating = touchPoints.any { it.radiusAnimator.isRunning || it.fadeAnimator.isRunning }
        if (isAnimating || touchPointsAnimating) {
            callbacks.requestRender()
        }
    }

    /**
     * Updates the OpenGL picture set with bitmaps and initiates crossfade animation.
     * @param imageSet The image set to load
     */
    private fun updatePictureSet(imageSet: ImageSet) {
        // Double-check surface is still available before creating textures
        if (!surfaceCreated) {
            Log.w(TAG, "updatePictureSet: Surface no longer available, aborting")
            return
        }

        previousPictureSet = pictureSet

        // Build the bitmap list: [original, ...blurLevels]
        val bitmapList = buildList {
            add(imageSet.original)
            addAll(imageSet.blurred)
        }

        try {
            pictureSet = GLPictureSet().apply {
                load(bitmapList, tileSize)
            }
            callbacks.requestRender()
        } catch (e: Exception) {
            Log.w(TAG, "updatePictureSet: Error loading textures, marking surface as unavailable (will retry on next surface)", e)
            surfaceCreated = false
            pendingImageSet = imageSet
            // Swallow and wait for surface recreation; GL context likely lost.
            return
        }
    }

    fun isBlurred(): Boolean {
        val blurAmount = animationController.blurAmountAnimator.currentValue
        return blurAmount > 0f
    }

    /**
     * Checks if any image-relevant animations (blur or image fade) are currently running.
     * @return true if blur or image fade animation is in progress
     */
    fun isAnimating(): Boolean {
        val blurRunning = animationController.blurAmountAnimator.isRunning
        val imageRunning = animationController.imageTransitionAnimator.isRunning
        val result = blurRunning || imageRunning
        Log.d(TAG, "isAnimating: blur=$blurRunning, image=$imageRunning, result=$result")
        return result
    }

    /**
     * Sets the parallax scroll offset for the wallpaper.
     * @param offset Normalized offset (0.0 = leftmost, 0.5 = center, 1.0 = rightmost)
     */
    fun setParallaxOffset(offset: Float) {
        val newOffset = offset.coerceIn(0f, 1f)
        val baseState = animationController.targetRenderState
        if (baseState.parallaxOffset != newOffset) {
            val newTargetState = baseState.copy(parallaxOffset = newOffset)
            animationController.updateTargetState(newTargetState)
            // Animation system will handle smooth interpolation; projection matrix 
            // is recalculated on every frame in onDrawFrame() using currentRenderState
            if (surfaceCreated) {
                callbacks.requestRender()
            }
        }
    }

    /**
     * Called when the wallpaper visibility changes. Syncs the parallax animator's current value
     * to match the target value, ensuring smooth animation starts from the correct position.
     * This prevents lag when the user starts swiping immediately after unlock.
     */
    fun onVisibilityChanged() {
        val targetOffset = animationController.targetRenderState.parallaxOffset
        animationController.parallaxOffsetAnimator.reset(targetOffset)
    }

    /**
     * Recomputes projection matrices for current and previous images.
     * Handles aspect ratio matching and parallax offset.
     */
    private fun recomputeProjectionMatrix() {
        val parallaxOffset = animationController.currentRenderState.parallaxOffset
        val safeSurfaceAspect = surfaceAspect.takeIf { it.isFinite() && it > 0f } ?: 1f
        val safeBitmapAspect = bitmapAspect.takeIf { it.isFinite() && it > 0f } ?: 1f
        val aspectRatio = safeSurfaceAspect / safeBitmapAspect

        buildProjectionMatrix(tempProjectionMatrix, aspectRatio, parallaxOffset)
        System.arraycopy(tempProjectionMatrix, 0, projectionMatrix, 0, tempProjectionMatrix.size)

        val prevAspect = previousBitmapAspect?.takeIf { it.isFinite() && it > 0f }
        if (prevAspect != null) {
            val prevAspectRatio = safeSurfaceAspect / prevAspect
            buildProjectionMatrix(previousProjectionMatrix, prevAspectRatio, parallaxOffset)
        } else {
            System.arraycopy(
                projectionMatrix,
                0,
                previousProjectionMatrix,
                0,
                projectionMatrix.size
            )
        }
    }

    /**
     * Builds an orthographic projection matrix with parallax scrolling support.
     * @param target Output array for the projection matrix
     * @param aspectRatio Ratio of screen aspect to bitmap aspect
     * @param parallaxOffset Parallax offset (0.0 = left, 0.5 = center, 1.0 = right)
     */
    private fun buildProjectionMatrix(target: FloatArray, aspectRatio: Float, parallaxOffset: Float) {
        if (aspectRatio == 0f) {
            Matrix.orthoM(target, 0, -1f, 1f, -1f, 1f, 0f, 1f)
            return
        }

        // Calculate zoom to ensure bitmap covers the screen
        val zoom = max(1f, aspectRatio)
        val scaledAspect = zoom / aspectRatio

        // Allow full panning across the entire image width
        val maxPanScreenWidths = scaledAspect

        // Calculate pan range
        val minPan = (1f - maxPanScreenWidths / scaledAspect) / 2f
        val maxPan = (1f + (maxPanScreenWidths - 2f) / scaledAspect) / 2f
        val panFraction = minPan + (maxPan - minPan) * parallaxOffset

        // Calculate orthographic projection bounds
        val left = -1f + 2f * panFraction
        val right = left + 2f / scaledAspect
        val bottom = -1f / zoom
        val top = 1f / zoom

        Matrix.orthoM(target, 0, left, right, bottom, top, 0f, 1f)
    }
}


/**
 * Utility functions for OpenGL ES operations.
 */
object GLUtil {
    private const val TAG = "GLUtil"
    /** Bytes per float value */
    const val BYTES_PER_FLOAT = 4

    /**
     * Compiles a shader from source code.
     * @param type Shader type (GL_VERTEX_SHADER or GL_FRAGMENT_SHADER)
     * @param shaderCode GLSL source code
     * @return Compiled shader handle
     * @throws RuntimeException if shader compilation fails
     */
    fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) {
            throw RuntimeException("Failed to create shader (glCreateShader returned 0)")
        }
        
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            val shaderTypeName = when (type) {
                GLES20.GL_VERTEX_SHADER -> "vertex"
                GLES20.GL_FRAGMENT_SHADER -> "fragment"
                else -> "unknown"
            }
            val errorMsg = "Shader compilation failed ($shaderTypeName shader):\n$log"
            Log.e(TAG, errorMsg)
            GLES20.glDeleteShader(shader)
            throw RuntimeException(errorMsg)
        }
        return shader
    }

    /**
     * Creates and links a shader program.
     * @param vertexShader Compiled vertex shader handle
     * @param fragmentShader Compiled fragment shader handle
     * @param attributes Optional array of attribute names to bind to specific locations
     * @return Linked program handle
     */
    fun createAndLinkProgram(
        vertexShader: Int,
        fragmentShader: Int,
        attributes: Array<String>? = null,
    ): Int {
        val program = GLES20.glCreateProgram()
        checkGlError("glCreateProgram")

        GLES20.glAttachShader(program, vertexShader)
        checkGlError("glAttachShader(vertex)")
        GLES20.glAttachShader(program, fragmentShader)
        checkGlError("glAttachShader(fragment)")

        attributes?.forEachIndexed { index, name ->
            GLES20.glBindAttribLocation(program, index, name)
        }

        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            val errorMsg = "Program linking failed:\n$log"
            Log.e(TAG, errorMsg)
            GLES20.glDeleteProgram(program)
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            throw RuntimeException(errorMsg)
        }

        // Clean up shaders (program retains compiled code)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        return program
    }

    /**
     * Loads a bitmap as an OpenGL texture with linear filtering and clamped edges.
     * @param bitmap The bitmap to upload
     * @return OpenGL texture handle
     * @throws IllegalStateException if texture creation fails or no GL context is available
     */
    fun loadTexture(bitmap: Bitmap): Int {
        val textureHandle = IntArray(1)
        GLES20.glGenTextures(1, textureHandle, 0)
        checkGlError("glGenTextures")

        if (textureHandle[0] != 0) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0])

            // Set texture parameters
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR
            )

            // Upload bitmap data
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            checkGlError("texImage2D")
        }

        if (textureHandle[0] == 0) {
            error("Error loading texture.")
        }

        return textureHandle[0]
    }

    /**
     * Converts a float array to a native-order FloatBuffer.
     * @param array Float array to convert
     * @return FloatBuffer ready for use with OpenGL
     */
    fun asFloatBuffer(array: FloatArray): FloatBuffer {
        return newFloatBuffer(array.size).apply {
            put(array)
            position(0)
        }
    }

    /**
     * Creates a new native-order FloatBuffer with the specified size.
     * @param size Number of float elements
     * @return Allocated FloatBuffer
     */
    fun newFloatBuffer(size: Int): FloatBuffer {
        return ByteBuffer.allocateDirect(size * BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { position(0) }
    }

    /**
     * Checks for OpenGL errors and throws an exception if one occurred.
     * @param op Description of the operation being checked
     * @throws RuntimeException if a GL error is detected
     */
    fun checkGlError(op: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            throw RuntimeException("$op: glError $error")
        }
    }
}
