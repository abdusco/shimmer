package dev.abdus.apps.shimmer.gl

import android.opengl.GLES30

class ShimmerProgram {
    val handles: ShaderHandles

    init {
        val vertexShader = ShaderCompiler.compile(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = ShaderCompiler.compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val program = ShaderCompiler.linkProgram(vertexShader, fragmentShader)

        handles = ShaderHandles(
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
            uniformAspectRatio = GLES30.glGetUniformLocation(program, "uAspectRatio"),
            uniformTime = GLES30.glGetUniformLocation(program, "uTime")
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
            uniform float uTime;
            in vec2 vTexCoords;
            in vec2 vPosition;
            out vec4 fragColor;

            #define LUMINOSITY(c) (0.2126 * (c).r + 0.7152 * (c).g + 0.0722 * (c).b)
            #define MAX_FINGERS 5
            #define DUOTONE_BLEND_MODE_SCREEN 1
            #define EPSILON 1e-7

            highp float organic_noise(vec2 p) {
                return fract(tan(distance(p * 1.61803398875, p) * 0.70710678118) * p.x);
            }

            float hash12(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }

            float valueNoise(vec2 p) {
                vec2 i = floor(p);
                vec2 f = fract(p);
                vec2 u = f * f * (3.0 - 2.0 * f);

                float a = hash12(i);
                float b = hash12(i + vec2(1.0, 0.0));
                float c = hash12(i + vec2(0.0, 1.0));
                float d = hash12(i + vec2(1.0, 1.0));

                return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
            }

            vec3 applyDuotone(vec3 color, float lum) {
                vec3 duotone = mix(uDuotoneDarkColor, uDuotoneLightColor, lum); // normal
                if (uDuotoneBlendMode == DUOTONE_BLEND_MODE_SCREEN) {
                    duotone = 1.0 - (1.0 - color) * (1.0 - duotone);
                }
                return mix(color, duotone, uDuotoneOpacity);
            }

            vec3 sampleBlurred(vec2 uv) {
                if (uBlurMix < 0.001) return texture(uTexture0, uv).rgb;
                if (uBlurMix > 0.999) return texture(uTexture1, uv).rgb;
                return mix(texture(uTexture0, uv).rgb, texture(uTexture1, uv).rgb, uBlurMix);
            }

            void main() {
                vec3 color = sampleBlurred(vTexCoords);
                float lum = LUMINOSITY(color);

                if (uDuotoneOpacity > 0.0) {
                    color = applyDuotone(color, lum);
                }

                vec2 screenPos = vPosition * 0.5 + 0.5;
                vec2 totalOffset = vec2(0.0);
                if (uTouchPointCount > 0) {
                    // we need both the dynamic count and the max count for loops for unrolling
                    for (int i = 0; i < MAX_FINGERS; i++) {
                        if (i >= uTouchPointCount) break;
                        vec2 touchPos = uTouchPoints[i].xy;
                        float touchRadius = uTouchPoints[i].z;

                        vec2 delta = screenPos - touchPos;
                        float dist = length(delta);

                        if (dist > touchRadius) continue;

                        // max effect at center, falloff to edge
                        float strength = 0.02 * uTouchIntensities[i] * (1.0 - smoothstep(0.0, touchRadius, dist));

                        // delta / dist = normalized direction vector from touch point to pixel
                        totalOffset += (delta / (dist + EPSILON)) * strength; // add EPSILON to avoid division by zero
                    }
                }

                bool hasDistortion = length(totalOffset) > 0.0001;
                if (hasDistortion) {
                    float t = uTime * 0.6;
                    float n1 = valueNoise(screenPos * 3.0 + vec2(t, t * 1.7));
                    float n2 = valueNoise(screenPos * 3.0 + vec2(13.7, 9.2) + vec2(t * 1.3, t * 0.8));
                    float noiseStrength = clamp(length(totalOffset) / 0.02, 0.0, 1.0);
                    totalOffset += (vec2(n1, n2) - 0.5) * 0.03 * noiseStrength;

                    vec3 cR = sampleBlurred(vTexCoords + totalOffset);
                    vec3 cG = color;
                    vec3 cB = sampleBlurred(vTexCoords - totalOffset);

                    if (uDuotoneOpacity > 0.0) {
                        cR = applyDuotone(cR, lum);
                        cB = applyDuotone(cB, lum);
                    }

                    color = vec3(cR.r, cG.g, cB.b);
                }

                if (uDimAmount > 0.0) {
                    color = mix(color, vec3(0.0), uDimAmount);
                }

                if (uGrainAmount > 0.0) {
                    vec2 grainCoords = vTexCoords * uGrainCount;
                    float noise = organic_noise(floor(grainCoords) + 0.2);
                    float mask = 1.0 - pow(abs(lum - 0.5) * 2.0, 2.0);
                    color += (noise - 0.5) * uGrainAmount * (0.1 + 0.5 * mask);
                } else {
                    // Apply dithering only if grain is not enabled, because grain itself provides good enough dithering
                    uint x = uint(gl_FragCoord.x);
                    uint y = uint(gl_FragCoord.y);
                    float dithering = float((x ^ y) * 14923u % 256u) / 255.0;
                    color += (dithering - 0.5) / 128.0;
                }

                fragColor = vec4(clamp(color, 0.0, 1.0), uAlpha);
            }
        """.trimIndent()
    }
}
