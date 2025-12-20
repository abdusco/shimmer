package dev.abdus.apps.shimmer.gl

import android.opengl.GLES30

class ShimmerProgram {
    val handles: PictureHandles

    init {
        val vertexShader = ShaderCompiler.compile(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = ShaderCompiler.compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val program = ShaderCompiler.linkProgram(vertexShader, fragmentShader)

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
            #define MAX_FINGERS 5

            highp float organic_noise(vec2 p) {
                return fract(tan(distance(p * 1.61803398875, p) * 0.70710678118) * p.x);
            }

            vec3 applyDuotone(vec3 color, float lum) {
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

            vec3 sampleBlurred(vec2 uv) {
                if (uBlurMix < 0.001) return texture(uTexture0, uv).rgb;
                if (uBlurMix > 0.999) return texture(uTexture1, uv).rgb;
                return mix(texture(uTexture0, uv).rgb, texture(uTexture1, uv).rgb, uBlurMix);
            }

            void main() {
                vec2 screenPos = vPosition * 0.5 + 0.5;
                vec2 totalOffset = vec2(0.0);

                if (uTouchPointCount > 0) {
                    // we need both the dynamic count and the max count for loops for unrolling
                    for (int i = 0; i < MAX_FINGERS; i++) {
                        if (i >= uTouchPointCount) break;
                        vec2 delta = screenPos - uTouchPoints[i].xy;
                        float dist = length(vec2(delta.x * uAspectRatio, delta.y));

                        if (dist > uTouchPoints[i].z + 0.05) continue;

                        float effect = uTouchIntensities[i] * (1.0 - smoothstep(0.0, uTouchPoints[i].z, dist));
                        if (effect > 0.0) totalOffset += (length(delta) > 0.0 ? normalize(delta) : vec2(0.0)) * effect * 0.02;
                    }
                }

                bool hasDistortion = length(totalOffset) > 0.0001;

                vec3 color;
                if (hasDistortion) {
                    vec3 cR = sampleBlurred(vTexCoords + totalOffset);
                    vec3 cG = sampleBlurred(vTexCoords);
                    vec3 cB = sampleBlurred(vTexCoords - totalOffset);
                    color = vec3(cR.r, cG.g, cB.b);
                } else {
                    color = sampleBlurred(vTexCoords);
                }

                float lum = LUMINOSITY(color);

                if (uDuotoneOpacity > 0.0) {
                    color = applyDuotone(color, lum);
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
