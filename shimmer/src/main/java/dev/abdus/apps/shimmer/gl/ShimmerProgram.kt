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

            #define LUMINOSITY(c) (dot(c, vec3(0.2126, 0.7152, 0.0722)))
            #define MAX_FINGERS 5
            #define DUOTONE_BLEND_MODE_SCREEN 1
            #define EPSILON 1e-7
            #define MAX_DISTORTION_OFFSET 0.02
            #define MAX_JITTER_AMPLITUDE 0.03

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
                float d = hash12(i + vec2(1.0));
                return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
            }

            vec3 sampleBlurred(vec2 uv) {
                if (uBlurMix < 0.001) return texture(uTexture0, uv).rgb;
                if (uBlurMix > 0.999) return texture(uTexture1, uv).rgb;
                return mix(texture(uTexture0, uv).rgb, texture(uTexture1, uv).rgb, uBlurMix);
            }

            vec3 applyDuotone(vec3 color, float lum) {
                vec3 duotone = mix(uDuotoneDarkColor, uDuotoneLightColor, lum);
                if (uDuotoneBlendMode == DUOTONE_BLEND_MODE_SCREEN) {
                    duotone = 1.0 - (1.0 - color) * (1.0 - duotone);
                }
                return mix(color, duotone, uDuotoneOpacity);
            }

            vec3 applyDithering(vec3 color) {
                uint x = uint(gl_FragCoord.x);
                uint y = uint(gl_FragCoord.y);
                float dither = float((x ^ y) * 14923u % 256u) / 255.0;
                return color + (dither - 0.5) / 128.0;
            }

            vec3 applyNoise(vec3 color, float lum, float amount) {
                vec2 grainCoords = vTexCoords * uGrainCount;
                float noise = valueNoise(floor(grainCoords) + 0.2);
                float mask = 1.0 - pow(abs(lum - 0.5) * 2.0, 2.0);
                return color + (noise - 0.5) * amount * (0.1 + 0.5 * mask);
            }

            vec2 calculateTouchOffset() {
                vec2 screenPos = vPosition * 0.5 + 0.5;
                vec2 offset = vec2(0.0);

                for (int i = 0; i < MAX_FINGERS; i++) {
                    if (i >= uTouchPointCount) break;

                    vec2 touchPos = uTouchPoints[i].xy;
                    vec2 delta = screenPos - touchPos;
                    float dist = length(delta);
                    float radius = uTouchPoints[i].z;

                    // Linear falloff instead of smoothstep
                    float falloff = max(0.0, 1.0 - dist / radius);

                    if (dist <= radius) {
                        float strength = MAX_DISTORTION_OFFSET * uTouchIntensities[i] * falloff;
                        offset += normalize(delta + EPSILON) * strength;
                    }
                }
                return offset;
            }

            vec2 getNoiseVector() {
                float t = uTime * 0.5;
                float n1 = valueNoise(vTexCoords * 5.0 + vec2(t, t * 1.3));
                float n2 = valueNoise(vTexCoords * 5.0 + vec2(7.1, 4.3) + vec2(t * 1.7, t * 0.9));
                return (vec2(n1, n2) - 0.5) * 2.0;
            }

            vec3 applyChromaticAberration(vec2 uv, vec2 offset) {
                vec2 jitterVec = getNoiseVector();
                float noiseStrength = clamp(length(offset) / MAX_DISTORTION_OFFSET, 0.0, 1.0);
                vec2 jitter = jitterVec * MAX_JITTER_AMPLITUDE * noiseStrength;

                vec3 rawR = sampleBlurred(uv + offset + jitter);
                vec3 rawG = sampleBlurred(uv);
                vec3 rawB = sampleBlurred(uv - offset - jitter);

                vec3 r = applyDuotone(rawR, LUMINOSITY(rawR));
                vec3 g = applyDuotone(rawG, LUMINOSITY(rawG));
                vec3 b = applyDuotone(rawB, LUMINOSITY(rawB));

                return vec3(r.r, g.g, b.b);
            }

            void main() {
                vec3 base = sampleBlurred(vTexCoords);
                vec3 color = applyDuotone(base, LUMINOSITY(base));

                if (uTouchPointCount > 0) {
                    vec2 touchOffset = calculateTouchOffset();
                    if (length(touchOffset) > 0.0001) {
                        color = applyChromaticAberration(vTexCoords, touchOffset);
                    }
                }

                // apply dimming
                color *= (1.0 - uDimAmount);

                float lum = LUMINOSITY(color);
                if (uGrainAmount > 0.0) {
                    color = applyNoise(color, lum, uGrainAmount);
                } else {
                    color = applyDithering(color);
                }

                fragColor = vec4(clamp(color, 0.0, 1.0), uAlpha);
            }
        """.trimIndent()
    }
}
