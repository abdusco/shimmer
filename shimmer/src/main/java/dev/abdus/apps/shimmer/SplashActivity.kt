package dev.abdus.apps.shimmer

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import net.rbgrn.android.glwallpaperservice.GLWallpaperService
import kotlin.random.Random

class SplashActivity : ComponentActivity() {

    private var previewRenderer: ShimmerRenderer? = null
    private var previewSurfaceView: PreviewSurfaceView? = null
    private val duotoneRotationHandler = Handler(Looper.getMainLooper())
    private var currentPresetIndex = Random.nextInt(DUOTONE_PRESETS.size)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If wallpaper is already active, go directly to settings
        if (WallpaperUtil.isActiveWallpaper(this)) {
            navigateToSettings()
            return
        }

        setContent {
            SplashScreen(
                onSetWallpaper = { WallpaperUtil.openWallpaperPicker(this) },
                onCreateRenderer = { renderer, surfaceView ->
                    previewRenderer = renderer
                    previewSurfaceView = surfaceView
                    loadPreviewImage()
                    startDuotoneRotation()
                },
                onDestroyRenderer = {
                    stopDuotoneRotation()
                    previewRenderer = null
                    previewSurfaceView = null
                }
            )
        }
    }


    private fun loadPreviewImage() {
        val renderer = previewRenderer ?: return
        val surfaceView = previewSurfaceView ?: return

        // Load default wallpaper on background thread
        Thread {
            val bitmap = BitmapFactory.decodeResource(resources, R.drawable.default_wallpaper)
            bitmap?.let {
                val payload = RendererImagePayload(
                    original = it,
                    blurred = emptyList(), // No blur for splash screen
                    sourceUri = null
                )
                surfaceView.queueEvent {
                    renderer.setImage(payload)
                }
            }
        }.start()
    }

    private fun startDuotoneRotation() {
        rotateDuotone()
    }

    private fun rotateDuotone() {
        val renderer = previewRenderer ?: return
        val surfaceView = previewSurfaceView ?: return

        // Apply random duotone preset
        currentPresetIndex = (currentPresetIndex + 1) % DUOTONE_PRESETS.size
        val preset = DUOTONE_PRESETS[currentPresetIndex]

        surfaceView.queueEvent {
            renderer.setDuotoneSettings(
                enabled = true,
                alwaysOn = true,  // Always show duotone in splash
                lightColor = preset.lightColor,
                darkColor = preset.darkColor,
                animate = true
            )
        }

        // Schedule next rotation in 1 second
        duotoneRotationHandler.postDelayed({ rotateDuotone() }, 2500)
    }

    private fun stopDuotoneRotation() {
        duotoneRotationHandler.removeCallbacksAndMessages(null)
    }

    private fun navigateToSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
        finish()
    }

    override fun onResume() {
        super.onResume()

        // Check if wallpaper was just set and redirect to settings
        if (WallpaperUtil.isActiveWallpaper(this)) {
            navigateToSettings()
        }
    }

    override fun onDestroy() {
        stopDuotoneRotation()
        super.onDestroy()
    }
}

@Composable
private fun SplashScreen(
    onSetWallpaper: () -> Unit,
    onCreateRenderer: (ShimmerRenderer, PreviewSurfaceView) -> Unit,
    onDestroyRenderer: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // OpenGL preview in background
        AndroidView(
            factory = { context ->
                PreviewSurfaceView(context).apply {
                    setEGLContextClientVersion(2)
                    setEGLConfigChooser(8, 8, 8, 0, 0, 0)
                    val renderer = ShimmerRenderer(object : ShimmerRenderer.Callbacks {
                        override fun requestRender() {
                            this@apply.requestRender()
                        }
                    })
                    setRenderer(renderer)
                    renderMode = GLWallpaperService.GLEngine.RENDERMODE_WHEN_DIRTY
                    onCreateRenderer(renderer, this)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        DisposableEffect(Unit) {
            onDispose {
                onDestroyRenderer()
            }
        }

        // UI overlay
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background.copy(alpha = 0.3f)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(32.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Text(
                        text = "Shimmer",
                        style = MaterialTheme.typography.displayLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.inversePrimary
                    )

                    Button(
                        onClick = onSetWallpaper,
                        modifier = Modifier.padding(top = 16.dp)
                    ) {
                        Text(
                            text = "Set Wallpaper",
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }
}

private class PreviewSurfaceView(context: android.content.Context) : android.opengl.GLSurfaceView(context)

