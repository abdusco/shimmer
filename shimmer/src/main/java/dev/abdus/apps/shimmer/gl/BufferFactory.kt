package dev.abdus.apps.shimmer.gl

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object BufferFactory {
    private const val BYTES_PER_FLOAT = 4

    fun createFloatBuffer(size: Int): FloatBuffer =
        ByteBuffer.allocateDirect(size * BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    fun FloatArray.toBuffer(): FloatBuffer =
        createFloatBuffer(size).apply {
            put(this@toBuffer)
            flip()
        }
}