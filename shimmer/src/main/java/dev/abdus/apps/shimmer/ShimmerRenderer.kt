package dev.abdus.apps.shimmer

import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import android.view.animation.DecelerateInterpolator
import dev.abdus.apps.shimmer.gl.GLWallpaperService

class ShimmerRenderer(private val callbacks: Callbacks) : GLWallpaperService.Renderer {

    interface Callbacks {
        fun requestRender()
        fun onRendererReady()
        fun onReadyForNextImage()
        fun onSurfaceDimensionsChanged(width: Int, height: Int)
    }

    companion object {
        private const val TAG = "ShimmerRenderer"
    }

    private var currentImage = GLTextureImage()
    private var previousImage = GLTextureImage()
    private var pendingImageSet: ImageSet? = null

    private val animationController = AnimationController().apply {
        onImageAnimationComplete = { callbacks.onReadyForNextImage() }
    }


    private var surfaceCreated = false
    private var surfaceDimensions = SurfaceDimensions(0, 0)

    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val viewModelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val previousMvpMatrix = FloatArray(16)

    private lateinit var pictureHandles: PictureHandles


    override fun onSurfaceCreated() {
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

    override fun onSurfaceChanged(width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        surfaceDimensions = SurfaceDimensions(width, height)
        animationController.setSurfaceDimensions(surfaceDimensions)
        callbacks.onSurfaceDimensionsChanged(width, height)
    }

    override fun onDrawFrame() {
        if (!surfaceCreated) return
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        val isAnimating = animationController.tick()
        val state = animationController.currentRenderState
        val imageAlpha = animationController.imageTransitionAnimator.currentValue

        val (projMatrix, prevProjMatrix) = animationController.recomputeProjectionMatrices()
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.multiplyMM(viewModelMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, viewModelMatrix, 0)
        prevProjMatrix?.let {
            Matrix.multiplyMM(previousMvpMatrix, 0, it, 0, viewModelMatrix, 0)
        }

        val blurPercent = state.blurPercent.coerceIn(0f, 1f)
        val effectiveDuotone = state.duotone.copy(
            opacity = if (state.duotoneAlwaysOn) state.duotone.opacity else (state.duotone.opacity * blurPercent)
        )

        val grainCounts = if (state.grain.enabled) {
            val grainSizePx = GrainSettings.GRAIN_SIZE_MIN_IMAGE_PX + (GrainSettings.GRAIN_SIZE_MAX_IMAGE_PX - GrainSettings.GRAIN_SIZE_MIN_IMAGE_PX) * state.grain.scale
            (state.imageSet.width.toFloat() / grainSizePx) to (state.imageSet.height.toFloat() / grainSizePx)
        } else 0f to 0f

        val (touchPointsArray, touchIntensitiesArray) = animationController.getTouchPointArrays()

        val screenSize = floatArrayOf(surfaceDimensions.width.toFloat(), surfaceDimensions.height.toFloat())

        if (imageAlpha < 1f) {
            previousImage.draw(
                pictureHandles, previousMvpMatrix, blurPercent, 1f - imageAlpha,
                effectiveDuotone, state.dimAmount * blurPercent, state.grain, grainCounts,
                touchPointsArray, touchIntensitiesArray, screenSize
            )
        }

        currentImage.draw(
            pictureHandles, mvpMatrix, blurPercent, imageAlpha,
            effectiveDuotone, state.dimAmount * blurPercent, state.grain, grainCounts,
            touchPointsArray, touchIntensitiesArray, screenSize
        )

        if (!animationController.imageTransitionAnimator.isRunning && imageAlpha >= 1f) {
            previousImage.release()
        }

        if (isAnimating || animationController.hasActiveTouches()) callbacks.requestRender()
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

        currentImage.load(imageSet)
        animationController.updateTargetState(animationController.targetRenderState.copy(imageSet = imageSet))
        callbacks.requestRender()
    }

    fun setEffectTransitionDuration(ms: Int) {
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
        val newSettings = ChromaticAberrationSettings(
            enabled = enabled,
            intensity = intensity.coerceIn(0f, 1f),
            fadeDurationMillis = fadeMs.toLong()
        )
        animationController.updateTargetState(animationController.targetRenderState.copy(chromaticAberration = newSettings))
        callbacks.requestRender()
    }

    fun setTouchPoints(touches: List<TouchData>) {
        animationController.setTouchPoints(touches)
        callbacks.requestRender()
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

    override fun onSurfaceDestroyed() {
        previousImage.release()
        surfaceCreated = false
    }

    fun isBlurred(): Boolean = animationController.currentRenderState.blurPercent > 0.01f
    fun isAnimating(): Boolean = animationController.blurAmountAnimator.isRunning || animationController.imageTransitionAnimator.isRunning



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

        // A fast, bit-wise hash that is 100% deterministic on all GLES 3.0 devices.
        // It converts coordinates to integers, eliminating sub-pixel jitter/flicker.
        float pcg_hash(vec2 p) {
            uvec2 q = uvec2(ivec2(p)); // Quantize to integer "cells"
            uint h = q.x * 747796405u + q.y + 2891336453u;
            h = ((h >> ((h >> 28u) + 4u)) ^ h) * 277803737u;
            h = (h >> 22u) ^ h;
            return float(h) * (1.0 / float(0xffffffffu));
        }

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

            float grain = 0.0;
            if (uGrainAmount > 0.0) {
                // By flooring the uGrainCount-scaled UVs, we create a stable integer grid
                // This stops the flickering caused by sub-pixel motion.
                vec2 grainCoords = floor(vTexCoords * uGrainCount);
                float noise = pcg_hash(grainCoords);
                grain = (noise - 0.5) * uGrainAmount;
            }

            // DITHERING: Fixed to window pixels, no sin() sensitivity
            // Using a simple XOR-style bit hash for dithering instead of sin()
            uint x = uint(gl_FragCoord.x);
            uint y = uint(gl_FragCoord.y);
            float dithering = float((x ^ y) * 14923u % 256u) / 255.0;
            dithering = (dithering - 0.5) / 128.0;
            
            fragColor = vec4(clamp(color + grain + dithering, 0.0, 1.0), uAlpha);
        }
    """.trimIndent()
}
