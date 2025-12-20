package dev.abdus.apps.shimmer.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils

object TextureLoader {
    // Simple texture loading for one-time use
    fun load(bitmap: Bitmap): Int {
        val handle = IntArray(1)
        GLES30.glGenTextures(1, handle, 0)
        require(handle[0] != 0) { "glGenTextures failed" }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, handle[0])
        setDefaultParameters()
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)

        return handle[0]
    }

    // Allocate immutable storage for reusable textures
    fun allocate(width: Int, height: Int): Int {
        val handle = IntArray(1)
        GLES30.glGenTextures(1, handle, 0)
        require(handle[0] != 0) { "glGenTextures failed" }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, handle[0])
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, width, height)
        setDefaultParameters()

        return handle[0]
    }

    // Upload bitmap data to existing texture
    fun upload(textureId: Int, bitmap: Bitmap) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, bitmap)
    }

    private fun setDefaultParameters() {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
    }
}