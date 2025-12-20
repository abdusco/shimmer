package dev.abdus.apps.shimmer

import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import dev.abdus.apps.shimmer.gl.GLWallpaperService
import java.util.concurrent.atomic.AtomicReference

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
    private val pendingParallaxOffset = AtomicReference<Float?>(null)
    private val pendingTouches = AtomicReference<List<TouchData>?>(null)


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
                uniformTexture0 = GLES30.glGetUniformLocation(program, "uTexture0"),
                uniformTexture1 = GLES30.glGetUniformLocation(program, "uTexture1"),
                uniformBlurMix = GLES30.glGetUniformLocation(program, "uBlurMix"),
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
                uniformAspectRatio = GLES30.glGetUniformLocation(program, "uAspectRatio")
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
        
        val newParallax = pendingParallaxOffset.getAndSet(null)
        if (newParallax != null) {
            animationController.updateTargetState(
                animationController.targetRenderState.copy(parallaxOffset = newParallax)
            )
        }

        val newTouches = pendingTouches.getAndSet(null)
        if (newTouches != null) {
            animationController.setTouchPoints(newTouches)
        }
        
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

        val grainCounts =
        if (state.grain.enabled) {
            val grainSizePx = GrainSettings.GRAIN_SIZE_MIN_IMAGE_PX + (GrainSettings.GRAIN_SIZE_MAX_IMAGE_PX - GrainSettings.GRAIN_SIZE_MIN_IMAGE_PX) * state.grain.scale
            (state.imageSet.width.toFloat() / grainSizePx) to (state.imageSet.height.toFloat() / grainSizePx)
        } else 0f to 0f

        val (touchPointsArray, touchIntensitiesArray) = animationController.getTouchPointArrays()

        val aspectRatio = surfaceDimensions.aspectRatio

        if (imageAlpha < 1f) {
            // 1. Draw the PREVIOUS image as a solid, opaque base.
            // We do NOT use (1f - imageAlpha) here.
            // Even if we are at the start of the fade, this image sits there at 100% opacity.
            previousImage.draw(
                pictureHandles, previousMvpMatrix, blurPercent, 1f, // FORCE 1.0
                effectiveDuotone, state.dimAmount * blurPercent, state.grain, grainCounts,
                touchPointsArray, touchIntensitiesArray, aspectRatio
            )
        }

        // 2. Draw the CURRENT image on top with the actual transition alpha (0.0 -> 1.0).
        currentImage.draw(
            pictureHandles, mvpMatrix, blurPercent, imageAlpha,
            effectiveDuotone, state.dimAmount * blurPercent, state.grain, grainCounts,
            touchPointsArray, touchIntensitiesArray, aspectRatio
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
        pendingTouches.set(touches)
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
        pendingParallaxOffset.set(offset)
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
        
        uniform sampler2D uTexture0;
        uniform sampler2D uTexture1;
        uniform float uBlurMix; // 0.0 to 1.0
        
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
        uniform float uAspectRatio;
        in vec2 vTexCoords;
        in vec2 vPosition;
        out vec4 fragColor;

        #define LUMINOSITY(c) (0.2126 * (c).r + 0.7152 * (c).g + 0.0722 * (c).b)

        // Gold Noise: Very fast, non-repeating, and organic looking
        // Uses the Golden Ratio and the Square Root of 2
        highp float organic_noise(vec2 p) {
            return fract(tan(distance(p * 1.61803398875, p) * 0.70710678118) * p.x);
        }
        
        vec3 applyDuotone(vec3 color) {
            float lum = LUMINOSITY(color);
            vec3 duotone = mix(uDuotoneDarkColor, uDuotoneLightColor, lum);
            if (uDuotoneBlendMode == 1) { 
                vec3 res1 = color - (1.0 - 2.0 * duotone) * color * (1.0 - color);
                vec3 res2 = color + (2.0 * duotone - 1.0) * (sqrt(color) - color);
                duotone = mix(res1, res2, step(0.5, duotone));
            } else if (uDuotoneBlendMode == 2) {
                duotone = 1.0 - (1.0 - color) * (1.0 - duotone);
            }
            return mix(color, duotone, uDuotoneOpacity);
        }

        void main() {
            vec2 screenPos = vPosition * 0.5 + 0.5;
            vec2 totalOffset = vec2(0.0);
            
            // OPTIMIZATION 1: Efficient Touch Loop
            // Only loop if we actually have touches
            if (uTouchPointCount > 0) {
                for (int i = 0; i < 10; i++) {
                    if (i >= uTouchPointCount) break;
                    vec2 delta = screenPos - uTouchPoints[i].xy;
                    float dist = length(vec2(delta.x * uAspectRatio, delta.y));
                    
                    // Early exit for pixels far from the touch point
                    if (dist > uTouchPoints[i].z + 0.05) continue;
                    
                    float effect = uTouchIntensities[i] * (1.0 - smoothstep(0.0, uTouchPoints[i].z, dist));
                    if (effect > 0.0) totalOffset += (length(delta) > 0.0 ? normalize(delta) : vec2(0.0)) * effect * 0.02;
                }
            }
            
            // OPTIMIZATION 2: Smart Texture Fetching
            // We drastically reduce texture lookups by checking if we actually need them.
            
            vec3 cR, cG, cB;
            bool hasDistortion = length(totalOffset) > 0.0001;

            if (hasDistortion) {
                // distortion is active (touching screen), we must pay the cost of 3x fetches for chromatic aberration
                vec2 uvR = vTexCoords + totalOffset;
                vec2 uvG = vTexCoords;
                vec2 uvB = vTexCoords - totalOffset;

                if (uBlurMix < 0.001) {
                    // Only fetch Sharp
                    cR = texture(uTexture0, uvR).rgb;
                    cG = texture(uTexture0, uvG).rgb;
                    cB = texture(uTexture0, uvB).rgb;
                } else if (uBlurMix > 0.999) {
                    // Only fetch Blurred
                    cR = texture(uTexture1, uvR).rgb;
                    cG = texture(uTexture1, uvG).rgb;
                    cB = texture(uTexture1, uvB).rgb;
                } else {
                    // Fetch Both & Mix (Most expensive case, but rare)
                    cR = mix(texture(uTexture0, uvR).rgb, texture(uTexture1, uvR).rgb, uBlurMix);
                    cG = mix(texture(uTexture0, uvG).rgb, texture(uTexture1, uvG).rgb, uBlurMix);
                    cB = mix(texture(uTexture0, uvB).rgb, texture(uTexture1, uvB).rgb, uBlurMix);
                }
            } else {
                // NO DISTORTION: The Common Case (99% of the time)
                // We only need 1 fetch per texture, not 3.
                
                vec3 finalColor;
                if (uBlurMix < 0.001) {
                    // Super Fast Path: Just draw the sharp image
                    finalColor = texture(uTexture0, vTexCoords).rgb;
                } else if (uBlurMix > 0.999) {
                    // Super Fast Path: Just draw the blurred image
                    finalColor = texture(uTexture1, vTexCoords).rgb;
                } else {
                    // Fast Path: Mix standard textures
                    vec3 c0 = texture(uTexture0, vTexCoords).rgb;
                    vec3 c1 = texture(uTexture1, vTexCoords).rgb;
                    finalColor = mix(c0, c1, uBlurMix);
                }
                cR = finalColor;
                cG = finalColor;
                cB = finalColor;
            }
            
            vec3 color = vec3(cR.r, cG.g, cB.b);

            // 3. Apply expensive effects ONLY if enabled
            if (uDuotoneOpacity > 0.0) {
                color = applyDuotone(color);
            }
            
            if (uDimAmount > 0.0) {
                color = mix(color, vec3(0.0), uDimAmount);
            }

            if (uGrainAmount > 0.0) {
                // 1. Calculate base grain using screen-space for stability
                vec2 grainCoords = vTexCoords * uGrainCount;
                
                // 2. Add a small 'jitter' to the coordinates based on the pixel itself
                // to break up the grid alignment (makes it feel more organic)
                float noise = organic_noise(floor(grainCoords) + 0.2);
                
                // 3. Optional: Mid-tone masking 
                // Real grain is most visible in mid-tones, less in pure blacks/whites
                float lum = LUMINOSITY(color);
                float mask = 1.0 - pow(abs(lum - 0.5) * 2.0, 2.0);
                
                // 4. Apply grain with a neutral offset
                // We multiply by mask to make it look "seated" in the image
                vec3 grainEffect = vec3(noise - 0.5) * uGrainAmount;
                color += grainEffect * (0.1 + 0.5 * mask); 
            }

            // DITHERING
            uint x = uint(gl_FragCoord.x);
            uint y = uint(gl_FragCoord.y);
            float dithering = float((x ^ y) * 14923u % 256u) / 255.0;
            color += (dithering - 0.5) / 128.0;
            
            fragColor = vec4(clamp(color, 0.0, 1.0), uAlpha);
        }
    """.trimIndent()
}
