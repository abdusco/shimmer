package dev.abdus.apps.shimmer.components

import android.content.Context
import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.abdus.apps.shimmer.ShimmerRenderer
import dev.abdus.apps.shimmer.gl.GLWallpaperService
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Adapter that wraps ShimmerRenderer to make it compatible with GLSurfaceView.
 * This keeps ShimmerRenderer decoupled from UI/preview concerns.
 */
private class ShimmerRendererAdapter(
    private val renderer: ShimmerRenderer
) : GLSurfaceView.Renderer {
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        renderer.onSurfaceCreated()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        renderer.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        renderer.onDrawFrame()
    }
}

@Composable
fun WallpaperPreview(
    modifier: Modifier = Modifier,
    onRendererCreated: (ShimmerRenderer, GLSurfaceView) -> Unit = { _, _ -> },
    onRendererReady: () -> Unit = {}
) {
    AndroidView(
        factory = { context ->
            PreviewSurfaceView(context).apply {
                setEGLContextClientVersion(3)
                setEGLConfigChooser(8, 8, 8, 0, 0, 0)
                val renderer = ShimmerRenderer(object : ShimmerRenderer.Callbacks {
                    override fun requestRender() {
                        this@apply.requestRender()
                    }

                    override fun onRendererReady() {
                        onRendererReady()
                    }

                    override fun onReadyForNextImage() {
                        // Not used in preview
                    }

                    override fun onSurfaceDimensionsChanged(width: Int, height: Int) {
                        // Not used in preview
                    }
                })
                renderer.setEffectTransitionDuration(2000)
                val adapter = ShimmerRendererAdapter(renderer)
                setRenderer(adapter)
                renderMode = GLWallpaperService.RENDERMODE_WHEN_DIRTY
                onRendererCreated(renderer, this)
            }
        },
        modifier = modifier
    )
}

class PreviewSurfaceView(context: Context) : GLSurfaceView(context)
