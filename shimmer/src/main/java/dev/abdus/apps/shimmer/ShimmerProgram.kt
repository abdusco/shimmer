package dev.abdus.apps.shimmer

import android.opengl.GLES30

class ShimmerProgram {
    val handles: PictureHandles
    
    init {
        val vertexShader = GLUtil.loadShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = GLUtil.loadShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val program = GLUtil.createAndLinkProgram(vertexShader, fragmentShader)
        
        handles = PictureHandles(
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
    }
    
    fun release() {
        GLES30.glDeleteProgram(handles.program)
    }
    
    companion object {
        private val VERTEX_SHADER = """
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
        
        private val FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;
            
            uniform sampler2D uTexture0;
            uniform sampler2D uTexture1;
            uniform float uBlurMix;
            
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
                
                if (uTouchPointCount > 0) {
                    for (int i = 0; i < 10; i++) {
                        if (i >= uTouchPointCount) break;
                        vec2 delta = screenPos - uTouchPoints[i].xy;
                        float dist = length(vec2(delta.x * uAspectRatio, delta.y));
                        
                        if (dist > uTouchPoints[i].z + 0.05) continue;
                        
                        float effect = uTouchIntensities[i] * (1.0 - smoothstep(0.0, uTouchPoints[i].z, dist));
                        if (effect > 0.0) totalOffset += (length(delta) > 0.0 ? normalize(delta) : vec2(0.0)) * effect * 0.02;
                    }
                }
                
                vec3 cR, cG, cB;
                bool hasDistortion = length(totalOffset) > 0.0001;

                if (hasDistortion) {
                    vec2 uvR = vTexCoords + totalOffset;
                    vec2 uvG = vTexCoords;
                    vec2 uvB = vTexCoords - totalOffset;

                    if (uBlurMix < 0.001) {
                        cR = texture(uTexture0, uvR).rgb;
                        cG = texture(uTexture0, uvG).rgb;
                        cB = texture(uTexture0, uvB).rgb;
                    } else if (uBlurMix > 0.999) {
                        cR = texture(uTexture1, uvR).rgb;
                        cG = texture(uTexture1, uvG).rgb;
                        cB = texture(uTexture1, uvB).rgb;
                    } else {
                        cR = mix(texture(uTexture0, uvR).rgb, texture(uTexture1, uvR).rgb, uBlurMix);
                        cG = mix(texture(uTexture0, uvG).rgb, texture(uTexture1, uvG).rgb, uBlurMix);
                        cB = mix(texture(uTexture0, uvB).rgb, texture(uTexture1, uvB).rgb, uBlurMix);
                    }
                } else {
                    vec3 finalColor;
                    if (uBlurMix < 0.001) {
                        finalColor = texture(uTexture0, vTexCoords).rgb;
                    } else if (uBlurMix > 0.999) {
                        finalColor = texture(uTexture1, vTexCoords).rgb;
                    } else {
                        vec3 c0 = texture(uTexture0, vTexCoords).rgb;
                        vec3 c1 = texture(uTexture1, vTexCoords).rgb;
                        finalColor = mix(c0, c1, uBlurMix);
                    }
                    cR = finalColor;
                    cG = finalColor;
                    cB = finalColor;
                }
                
                vec3 color = vec3(cR.r, cG.g, cB.b);

                if (uDuotoneOpacity > 0.0) {
                    color = applyDuotone(color);
                }
                
                if (uDimAmount > 0.0) {
                    color = mix(color, vec3(0.0), uDimAmount);
                }

                if (uGrainAmount > 0.0) {
                    vec2 grainCoords = vTexCoords * uGrainCount;
                    float noise = organic_noise(floor(grainCoords) + 0.2);
                    float lum = LUMINOSITY(color);
                    float mask = 1.0 - pow(abs(lum - 0.5) * 2.0, 2.0);
                    vec3 grainEffect = vec3(noise - 0.5) * uGrainAmount;
                    color += grainEffect * (0.1 + 0.5 * mask); 
                }

                uint x = uint(gl_FragCoord.x);
                uint y = uint(gl_FragCoord.y);
                float dithering = float((x ^ y) * 14923u % 256u) / 255.0;
                color += (dithering - 0.5) / 128.0;
                
                fragColor = vec4(clamp(color, 0.0, 1.0), uAlpha);
            }
        """.trimIndent()
    }
}