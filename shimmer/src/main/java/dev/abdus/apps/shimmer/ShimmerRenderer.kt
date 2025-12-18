package dev.abdus.apps.shimmer

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import android.view.animation.DecelerateInterpolator
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

class ShimmerRenderer(private val callbacks: Callbacks) : GLSurfaceView.Renderer {

    interface Callbacks {
        fun requestRender()
        fun onRendererReady()
        fun onReadyForNextImage()
        fun onSurfaceDimensionsChanged(width: Int, height: Int)
    }

    companion object {
        private const val TAG = "ShimmerRenderer"
        private const val MAX_TOUCH_POINTS = 10
        private const val TARGET_RADIUS = 0.5f // Maximum spread of the ripple

        private const val GRAIN_SIZE_MIN_IMAGE_PX = 1.5f
        private const val GRAIN_SIZE_MAX_IMAGE_PX = 3.0f
    }

    private var currentImage = GLTextureImage()
    private var previousImage = GLTextureImage()
    private var pendingImageSet: ImageSet? = null

    private var effectTransitionDurationMillis = 1500
    private val animationController = AnimationController(effectTransitionDurationMillis).apply {
        onImageAnimationComplete = { callbacks.onReadyForNextImage() }
    }

    private var chromaticAberrationIntensity = 0.5f
    private var chromaticAberrationFadeMs = 500
    private val activeTouches = mutableListOf<TouchPoint>()
    private val touchPointsArray = FloatArray(MAX_TOUCH_POINTS * 3)
    private val touchIntensitiesArray = FloatArray(MAX_TOUCH_POINTS)

    private var surfaceCreated = false
    private var surfaceWidthPx = 0
    private var surfaceHeightPx = 0
    private var surfaceAspect = 1f
    private var bitmapAspect = 1f
    private var previousBitmapAspect: Float? = null

    private val projectionMatrix = FloatArray(16)
    private val previousProjectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val viewModelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val previousMvpMatrix = FloatArray(16)

    private lateinit var pictureHandles: PictureHandles

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        surfaceCreated = true
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glClearColor(0f, 0f, 0f, 1f)

        try {
            val vertexShader = GLUtil.loadShader(GLES30.GL_VERTEX_SHADER, PICTURE_VERTEX_SHADER_CODE)
            val fragmentShader = GLUtil.loadShader(GLES30.GL_FRAGMENT_SHADER, PICTURE_FRAGMENT_SHADER_CODE)
            val program = GLUtil.createAndLinkProgram(vertexShader, fragmentShader)

            pictureHandles = PictureHandles(
                program = program,
                attribPosition = GLES30.glGetAttribLocation(program, "aPosition"),
                attribTexCoords = GLES30.glGetAttribLocation(program, "aTexCoords"),
                uniformMvpMatrix = GLES30.glGetUniformLocation(program, "uMVPMatrix"),
                uniformTexture = GLES30.glGetUniformLocation(program, "uTexture"),
                uniformAlpha = GLES30.glGetUniformLocation(program, "uAlpha"),
                uniformDuotoneLight = GLES30.glGetUniformLocation(program, "uDuotoneLightColor"),
                uniformDuotoneDark = GLES30.glGetUniformLocation(program, "uDuotoneDarkColor"),
                uniformDuotoneOpacity = GLES30.glGetUniformLocation(program, "uDuotoneOpacity"),
                uniformDuotoneBlendMode = GLES30.glGetUniformLocation(program, "uDuotoneBlendMode"),
                uniformDimAmount = GLES30.glGetUniformLocation(program, "uDimAmount"),
                uniformGrainAmount = GLES30.glGetUniformLocation(program, "uGrainAmount"),
                uniformGrainCount = GLES30.glGetUniformLocation(program, "uGrainCount"),
                uniformTouchPointCount = GLES30.glGetUniformLocation(program, "uTouchPointCount"),
                uniformTouchPoints = GLES30.glGetUniformLocation(program, "uTouchPoints"),
                uniformTouchIntensities = GLES30.glGetUniformLocation(program, "uTouchIntensities"),
                uniformScreenSize = GLES30.glGetUniformLocation(program, "uScreenSize")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize shaders", e)
            return
        }

        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, 1f, 0f, 0f, -1f, 0f, 1f, 0f)
        pendingImageSet?.let { setImage(it); pendingImageSet = null }
        callbacks.onRendererReady()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        surfaceWidthPx = width
        surfaceHeightPx = height
        surfaceAspect = if (height == 0) 1f else width.toFloat() / height
        recomputeProjectionMatrix()
        callbacks.onSurfaceDimensionsChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!surfaceCreated) return
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        val isAnimating = animationController.tick()
        val state = animationController.currentRenderState
        val imageAlpha = animationController.imageTransitionAnimator.currentValue

        recomputeProjectionMatrix()
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.multiplyMM(viewModelMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewModelMatrix, 0)
        Matrix.multiplyMM(previousMvpMatrix, 0, previousProjectionMatrix, 0, viewModelMatrix, 0)

        val blurPercent = state.blurPercent.coerceIn(0f, 1f)
        val effectiveDuotone = state.duotone.copy(
            opacity = if (state.duotoneAlwaysOn) state.duotone.opacity else (state.duotone.opacity * blurPercent)
        )

        val grainCounts = if (state.grain.enabled) {
            val grainSizePx = GRAIN_SIZE_MIN_IMAGE_PX + (GRAIN_SIZE_MAX_IMAGE_PX - GRAIN_SIZE_MIN_IMAGE_PX) * state.grain.scale
            (state.imageSet.width.toFloat() / grainSizePx) to (state.imageSet.height.toFloat() / grainSizePx)
        } else 0f to 0f

        updateTouchPointAnimations()
        val touchCount = activeTouches.size.coerceAtMost(MAX_TOUCH_POINTS)
        for (i in 0 until touchCount) {
            val touch = activeTouches[i]
            touchPointsArray[i * 3] = touch.x
            touchPointsArray[i * 3 + 1] = touch.y
            touchPointsArray[i * 3 + 2] = touch.radius
            touchIntensitiesArray[i] = touch.intensity * chromaticAberrationIntensity
        }

        val screenSize = floatArrayOf(surfaceWidthPx.toFloat(), surfaceHeightPx.toFloat())

        if (imageAlpha < 1f) {
            previousImage.draw(
                pictureHandles, previousMvpMatrix, blurPercent, 1f - imageAlpha,
                effectiveDuotone, state.dimAmount * blurPercent, state.grain, grainCounts,
                touchCount, touchPointsArray, touchIntensitiesArray, screenSize
            )
        }

        currentImage.draw(
            pictureHandles, mvpMatrix, blurPercent, imageAlpha,
            effectiveDuotone, state.dimAmount * blurPercent, state.grain, grainCounts,
            touchCount, touchPointsArray, touchIntensitiesArray, screenSize
        )

        if (!animationController.imageTransitionAnimator.isRunning && imageAlpha >= 1f) {
            previousImage.release()
        }

        if (isAnimating || activeTouches.isNotEmpty()) callbacks.requestRender()
    }

    fun updateSettings(
        blurAmount: Float, dimAmount: Float, duotone: DuotoneSettings, grain: GrainSettings,
        chromatic: ChromaticAberrationSettings, transitionDuration: Long,
    ) {
        setEffectTransitionDuration(transitionDuration.toInt())
        setUserDimAmount(dimAmount)
        setDuotoneSettings(duotone.enabled, duotone.alwaysOn, Duotone(duotone.lightColor, duotone.darkColor, if (duotone.enabled) 1f else 0f, duotone.blendMode))
        setGrainSettings(grain.enabled, grain.amount, grain.scale)
        setChromaticAberrationSettings(chromatic.enabled, chromatic.intensity, chromatic.fadeDurationMillis.toInt())
    }

    fun setImage(imageSet: ImageSet) {
        if (!surfaceCreated) {
            pendingImageSet = imageSet; return
        }
        previousImage.release()
        previousImage = currentImage
        currentImage = GLTextureImage()

        previousBitmapAspect = bitmapAspect
        bitmapAspect = if (imageSet.height == 0) 1f else imageSet.width.toFloat() / imageSet.height

        currentImage.load(imageSet)
        animationController.updateTargetState(animationController.targetRenderState.copy(imageSet = imageSet))
        recomputeProjectionMatrix()
        callbacks.requestRender()
    }

    fun setEffectTransitionDuration(ms: Int) {
        effectTransitionDurationMillis = ms
        animationController.setDuration(ms)
    }

    fun setUserDimAmount(amount: Float) {
        animationController.updateTargetState(animationController.targetRenderState.copy(dimAmount = amount))
    }

    fun setDuotoneSettings(enabled: Boolean, alwaysOn: Boolean, duotone: Duotone) {
        animationController.updateTargetState(animationController.targetRenderState.copy(duotone = duotone, duotoneAlwaysOn = alwaysOn))
    }

    fun setGrainSettings(enabled: Boolean, amount: Float, scale: Float) {
        animationController.updateTargetState(animationController.targetRenderState.copy(grain = GrainSettings(enabled, amount, scale)))
    }

    fun setChromaticAberrationSettings(enabled: Boolean, intensity: Float, fadeMs: Int) {
        chromaticAberrationIntensity = if (enabled) intensity.coerceIn(0f, 1f) else 0f
        chromaticAberrationFadeMs = fadeMs
        if (!enabled) activeTouches.clear()
        callbacks.requestRender()
    }

    fun setTouchPoints(touches: List<TouchData>) {
        if (chromaticAberrationIntensity <= 0) return

        for (touch in touches) {
            when (touch.action) {
                TouchAction.DOWN -> {
                    // Find existing non-released touch with this pointer ID
                    val existingTouch = activeTouches.find { it.id == touch.id && !it.isReleased }
                    if (existingTouch != null) {
                        // Already active, just update position
                        existingTouch.x = touch.x
                        existingTouch.y = touch.y
                    } else if (activeTouches.size < MAX_TOUCH_POINTS) {
                        // Create new touch point (even if there's a released touch with same ID)
                        val newTouch = createTouchPoint(touch)
                        activeTouches.add(newTouch)
                    }
                }
                TouchAction.MOVE -> {
                    activeTouches.find { it.id == touch.id && !it.isReleased }?.let {
                        it.x = touch.x
                        it.y = touch.y
                    }
                }
                TouchAction.UP -> {
                    activeTouches.find { it.id == touch.id && !it.isReleased }?.let {
                        releaseTouch(it)
                    }
                }
            }
        }

        // Don't remove touches here - let updateTouchPointAnimations() handle cleanup
        // when animations complete. This allows fade-out animations to run.

        callbacks.requestRender()
    }

    private fun createTouchPoint(touch: TouchData): TouchPoint {
        val newTouch = TouchPoint(
            id = touch.id,
            x = touch.x,
            y = touch.y,
            radius = 0f,
            intensity = 1f,
            radiusAnimator = TickingFloatAnimator(4000, DecelerateInterpolator()),
            fadeAnimator = TickingFloatAnimator(chromaticAberrationFadeMs, DecelerateInterpolator())
        )
        newTouch.radiusAnimator.start(startValue = 0f, endValue = 1f)
        // Set fadeAnimator to not be running, but with intensity at 1.0
        newTouch.fadeAnimator.reset()
        newTouch.fadeAnimator.currentValue = 1f
        return newTouch
    }

    private fun releaseTouch(touchPoint: TouchPoint) {
        touchPoint.radiusAnimator.start(startValue = touchPoint.radius, endValue = 0f)
        touchPoint.fadeAnimator.start(startValue = touchPoint.intensity, endValue = 0f)
        touchPoint.isReleased = true
    }

    // Touch point management for chromatic aberration - moved to class body
    private fun updateTouchPointAnimations() {
        val touchesToRemove = mutableListOf<TouchPoint>()
        for (touchPoint in activeTouches) {
            // Tick animators (they handle their own running state internally)
            touchPoint.radiusAnimator.tick()
            touchPoint.radius = touchPoint.radiusAnimator.currentValue

            touchPoint.fadeAnimator.tick()
            touchPoint.intensity = touchPoint.fadeAnimator.currentValue

            // Remove touch points that have fully faded out AND finished their radius animation
            if (!touchPoint.radiusAnimator.isRunning && !touchPoint.fadeAnimator.isRunning &&
                touchPoint.radius <= 0.01f && touchPoint.intensity <= 0.01f) {
                touchesToRemove.add(touchPoint)
            }
        }
        // Remove completed touch points
        activeTouches.removeAll(touchesToRemove)
    }


    fun enableBlur(enabled: Boolean, immediate: Boolean) {
        val target = if (enabled) 1f else 0f
        val newState = animationController.targetRenderState.copy(blurPercent = target)
        if (immediate) {
            animationController.setRenderStateImmediately(newState)
            animationController.blurAmountAnimator.reset()
        } else {
            animationController.updateTargetState(newState)
        }
        callbacks.requestRender()
    }

    fun onVisibilityChanged() {
        animationController.parallaxOffsetAnimator.reset(animationController.targetRenderState.parallaxOffset)
    }

    fun setParallaxOffset(offset: Float) {
        animationController.updateTargetState(animationController.targetRenderState.copy(parallaxOffset = offset))
        callbacks.requestRender()
    }

    fun onSurfaceDestroyed() {
        currentImage.release()
        previousImage.release()
        surfaceCreated = false
    }

    fun isBlurred(): Boolean = animationController.currentRenderState.blurPercent > 0.01f
    fun isAnimating(): Boolean = animationController.blurAmountAnimator.isRunning || animationController.imageTransitionAnimator.isRunning

    private fun recomputeProjectionMatrix() {
        val parallax = animationController.currentRenderState.parallaxOffset
        buildProjectionMatrix(projectionMatrix, surfaceAspect / bitmapAspect, parallax)
        previousBitmapAspect?.let { buildProjectionMatrix(previousProjectionMatrix, surfaceAspect / it, parallax) }
    }

    private fun buildProjectionMatrix(target: FloatArray, ratio: Float, pan: Float) {
        val zoom = max(1f, ratio)
        val scaledAspect = zoom / ratio
        val panFraction = pan * (scaledAspect - 1f) / scaledAspect
        val left = -1f + 2f * panFraction
        val right = left + 2f / scaledAspect
        Matrix.orthoM(target, 0, left, right, -1f / zoom, 1f / zoom, 0f, 1f)
    }



    private val PICTURE_VERTEX_SHADER_CODE = """
        #version 300 es
        uniform mat4 uMVPMatrix;
        in vec4 aPosition;
        in vec2 aTexCoords;
        out vec2 vTexCoords;
        out vec2 vPosition;
        void main() {
            vTexCoords = aTexCoords;
            vPosition = aPosition.xy;
            gl_Position = uMVPMatrix * aPosition;
        }
    """.trimIndent()

    private val PICTURE_FRAGMENT_SHADER_CODE = """
        #version 300 es
        precision highp float;
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
        in vec2 vTexCoords;
        in vec2 vPosition;
        out vec4 fragColor;

        #define LUMINOSITY(c) (0.2126 * (c).r + 0.7152 * (c).g + 0.0722 * (c).b)

        vec3 applyDuotone(vec3 color) {
            float lum = LUMINOSITY(color);
            vec3 duotone = mix(uDuotoneDarkColor, uDuotoneLightColor, lum);
            if (uDuotoneBlendMode == 1) { // SOFT_LIGHT
                vec3 res1 = color - (1.0 - 2.0 * duotone) * color * (1.0 - color);
                vec3 res2 = color + (2.0 * duotone - 1.0) * (sqrt(color) - color);
                duotone = mix(res1, res2, step(0.5, duotone));
            } else if (uDuotoneBlendMode == 2) { // SCREEN
                duotone = 1.0 - (1.0 - color) * (1.0 - duotone);
            }
            return mix(color, duotone, uDuotoneOpacity);
        }

        vec3 permute(vec3 x) { return mod(((x*34.0)+1.0)*x, 289.0); }
        float snoise(vec2 v){
            const vec4 C = vec4(0.211324865405187, 0.366025403784439, -0.577350269189626, 0.024390243902439);
            vec2 i  = floor(v + dot(v, C.yy) );
            vec2 x0 = v -   i + dot(i, C.xx);
            vec2 i1 = (x0.x > x0.y) ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
            vec4 x12 = x0.xyxy + C.xxzz;
            x12.xy -= i1;
            i = mod(i, 289.0);
            vec3 p = permute( permute( i.y + vec3(0.0, i1.y, 1.0 )) + i.x + vec3(0.0, i1.x, 1.0 ));
            vec3 m = max(0.5 - vec3(dot(x0,x0), dot(x12.xy,x12.xy), dot(x12.zw,x12.zw)), 0.0);
            m = m*m*m*m;
            vec3 x = 2.0 * fract(p * C.www) - 1.0;
            vec3 h = abs(x) - 0.5;
            vec3 a0 = x - floor(x + 0.5);
            m *= 1.79284291400159 - 0.85373472095314 * ( a0*a0 + h*h );
            vec3 g = vec3(a0.x * x0.x + h.x * x0.y, a0.yz * x12.xz + h.yz * x12.yw);
            return 130.0 * dot(m, g);
        }

        void main() {
            vec2 screenPos = vPosition * 0.5 + 0.5;
            float aspect = uScreenSize.x / uScreenSize.y;
            vec2 totalOffset = vec2(0.0);
            for (int i = 0; i < 10; i++) {
                if (i >= uTouchPointCount) break;
                vec2 delta = screenPos - uTouchPoints[i].xy;
                float dist = length(vec2(delta.x * aspect, delta.y));
                float effect = uTouchIntensities[i] * (1.0 - smoothstep(0.0, uTouchPoints[i].z, dist));
                if (effect > 0.0) totalOffset += (length(delta) > 0.0 ? normalize(delta) : vec2(0.0)) * effect * 0.02;
            }
            
            vec3 cR = applyDuotone(texture(uTexture, vTexCoords + totalOffset).rgb);
            vec3 cG = applyDuotone(texture(uTexture, vTexCoords).rgb);
            vec3 cB = applyDuotone(texture(uTexture, vTexCoords - totalOffset).rgb);
            
            vec3 color = length(totalOffset) > 0.001 ? vec3(cR.r, cG.g, cB.b) : cG;
            color = mix(color, vec3(0.0), uDimAmount);
            float grain = uGrainAmount > 0.0 ? snoise((vPosition * 0.5 + 0.5) * uGrainCount) * uGrainAmount : 0.0;
            float dithering = (fract(sin(dot(gl_FragCoord.xy, vec2(12.9898,78.233))) * 43758.5453) - 0.5) / 64.0;
            fragColor = vec4(clamp(color + grain + dithering, 0.0, 1.0), uAlpha);
        }
    """.trimIndent()
}