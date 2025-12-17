package dev.abdus.apps.shimmer.components

import android.content.Context
import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.abdus.apps.shimmer.ShimmerRenderer
import net.rbgrn.android.glwallpaperservice.GLWallpaperService

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
                setRenderer(renderer)
                renderMode = GLWallpaperService.RENDERMODE_WHEN_DIRTY
                onRendererCreated(renderer, this)
            }
        },
        modifier = modifier
    )
}

class PreviewSurfaceView(context: Context) : GLSurfaceView(context)
