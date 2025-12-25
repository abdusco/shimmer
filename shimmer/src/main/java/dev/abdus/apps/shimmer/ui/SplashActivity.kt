package dev.abdus.apps.shimmer.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.DUOTONE_PRESETS
import dev.abdus.apps.shimmer.Duotone
import dev.abdus.apps.shimmer.ImageSet
import dev.abdus.apps.shimmer.R
import dev.abdus.apps.shimmer.ShimmerRenderer
import dev.abdus.apps.shimmer.WallpaperUtil
import dev.abdus.apps.shimmer.ui.settings.SettingsActivity
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
            ShimmerTheme {
                SplashScreen(
                    onSetWallpaper = { WallpaperUtil.openWallpaperPicker(this) },
                    onRendererCreated = { renderer, surfaceView ->
                        previewRenderer = renderer
                        previewSurfaceView = surfaceView as? PreviewSurfaceView
                    },
                    onDestroyRenderer = {
                        stopDuotoneRotation()
                        previewRenderer = null
                        previewSurfaceView = null
                    },
                    onRendererReady = {
                        loadPreviewImage()
                        duotoneRotationHandler.postDelayed({ startDuotoneRotation() }, 4000)
                    }
                )
            }
        }
    }


    private fun loadPreviewImage() {
        val renderer = previewRenderer ?: return
        val surfaceView = previewSurfaceView ?: return

        // Load default wallpaper on background thread
        Thread {
            val bitmap = BitmapFactory.decodeResource(resources, R.drawable.default_wallpaper, BitmapFactory.Options().apply {
                inSampleSize = 2
            })
            bitmap?.let {
                val payload = ImageSet(original = it)
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
                duotone = Duotone(
                    lightColor = preset.lightColor,
                    darkColor = preset.darkColor,
                    opacity = 1f,
                ),
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
    onRendererCreated: (ShimmerRenderer, GLSurfaceView) -> Unit,
    onDestroyRenderer: () -> Unit,
    onRendererReady: () -> Unit
) {
    DisposableEffect(Unit) {
        onDispose {
            onDestroyRenderer()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // OpenGL preview in background
        WallpaperPreview(
            modifier = Modifier.fillMaxSize(),
            onRendererCreated = onRendererCreated,
            onRendererReady = onRendererReady
        )

        // UI overlay
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent
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
                    Image(
                        painter = painterResource(id = R.drawable.shimmer),
                        contentDescription = "Shimmer Logo",
                        colorFilter = ColorFilter.tint(Color.White),
                        modifier = Modifier.size(160.dp)
                    )

                    Text(
                        text = "Shimmer",
                        style = MaterialTheme.typography.displayLarge,
                        textAlign = TextAlign.Center,
                        color = Color.White
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
